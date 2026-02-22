package dev.aceclaw.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Persistent store for learning candidates with merge/dedup behavior.
 */
public final class CandidateStore {

    private static final Logger log = LoggerFactory.getLogger(CandidateStore.class);

    private static final String MEMORY_DIR = "memory";
    private static final String KEY_FILE = "memory.key";
    private static final String CANDIDATES_FILE = "candidates.jsonl";
    private static final int KEY_SIZE_BYTES = 32;
    private static final Duration DEFAULT_RECENT_WINDOW = Duration.ofDays(30);
    private static final double DEFAULT_MERGE_THRESHOLD = 0.50;

    private final Path memoryDir;
    private final Path candidatesFile;
    private final ObjectMapper mapper;
    private final MemorySigner signer;
    private final CopyOnWriteArrayList<LearningCandidate> candidates;
    private final ReentrantLock fileLock = new ReentrantLock();
    private final Duration recentWindow;
    private final double mergeThreshold;

    public CandidateStore(Path aceclawHome) throws IOException {
        this(aceclawHome, DEFAULT_RECENT_WINDOW, DEFAULT_MERGE_THRESHOLD);
    }

    CandidateStore(Path aceclawHome, Duration recentWindow, double mergeThreshold) throws IOException {
        this.memoryDir = aceclawHome.resolve(MEMORY_DIR);
        this.candidatesFile = memoryDir.resolve(CANDIDATES_FILE);
        this.recentWindow = Objects.requireNonNull(recentWindow, "recentWindow");
        this.mergeThreshold = mergeThreshold;
        Files.createDirectories(memoryDir);

        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.signer = new MemorySigner(loadOrCreateKey());
        this.candidates = new CopyOnWriteArrayList<>();
    }

    public void load() {
        fileLock.lock();
        try {
            candidates.clear();
            loadFile();
        } finally {
            fileLock.unlock();
        }
    }

    public List<LearningCandidate> all() {
        return List.copyOf(candidates);
    }

    /**
     * Upserts an observation into the candidate store.
     * Matching key is category + toolTag + similarity(content/tags) in a recent window.
     */
    public LearningCandidate upsert(CandidateObservation observation) {
        Objects.requireNonNull(observation, "observation");
        fileLock.lock();
        try {
            var incoming = createUnsignedCandidate(observation);
            int mergeIdx = findMergeIndex(incoming);
            LearningCandidate stored;
            if (mergeIdx >= 0) {
                var merged = candidates.get(mergeIdx).mergeWith(incoming);
                stored = sign(merged);
                candidates.set(mergeIdx, stored);
                rewriteFile();
            } else {
                stored = sign(incoming);
                candidates.add(stored);
                append(stored);
            }
            return stored;
        } finally {
            fileLock.unlock();
        }
    }

    private int findMergeIndex(LearningCandidate incoming) {
        Instant cutoff = incoming.lastSeenAt().minus(recentWindow);
        int bestIdx = -1;
        double bestScore = -1.0;
        for (int i = 0; i < candidates.size(); i++) {
            var existing = candidates.get(i);
            if (existing.lastSeenAt().isBefore(cutoff)) {
                continue;
            }
            double score = CandidateSimilarity.score(existing, incoming);
            if (score >= mergeThreshold && score > bestScore) {
                bestScore = score;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    private LearningCandidate createUnsignedCandidate(CandidateObservation observation) {
        var now = observation.occurredAt() == null ? Instant.now() : observation.occurredAt();
        return new LearningCandidate(
                UUID.randomUUID().toString(),
                observation.category(),
                observation.kind(),
                CandidateState.SHADOW,
                observation.content(),
                normalizeToolTag(observation.toolTag()),
                List.copyOf(observation.tags()),
                observation.score(),
                1,
                Math.max(0, observation.successDelta()),
                Math.max(0, observation.failureDelta()),
                now,
                now,
                observation.sourceRef() == null || observation.sourceRef().isBlank()
                        ? List.of()
                        : List.of(observation.sourceRef()),
                null
        );
    }

    private LearningCandidate sign(LearningCandidate candidate) {
        return new LearningCandidate(
                candidate.id(),
                candidate.category(),
                candidate.kind(),
                candidate.state(),
                candidate.content(),
                candidate.toolTag(),
                candidate.tags(),
                candidate.score(),
                candidate.evidenceCount(),
                candidate.successCount(),
                candidate.failureCount(),
                candidate.firstSeenAt(),
                candidate.lastSeenAt(),
                candidate.sourceRefs(),
                signer.sign(candidate.signablePayload()));
    }

    private void loadFile() {
        if (!Files.isRegularFile(candidatesFile)) return;
        try {
            var lines = Files.readAllLines(candidatesFile);
            int loaded = 0;
            int skipped = 0;
            for (var line : lines) {
                if (line.isBlank()) continue;
                try {
                    var candidate = mapper.readValue(line, LearningCandidate.class);
                    if (candidate.hmac() != null && signer.verify(candidate.signablePayload(), candidate.hmac())) {
                        candidates.add(candidate);
                        loaded++;
                    } else {
                        skipped++;
                        log.warn("Skipped tampered candidate: id={}", candidate.id());
                    }
                } catch (Exception e) {
                    skipped++;
                    log.warn("Skipped malformed candidate entry: {}", e.getMessage());
                }
            }
            candidates.sort(Comparator.comparing(LearningCandidate::lastSeenAt).reversed());
            log.debug("Loaded {} candidates ({} skipped)", loaded, skipped);
        } catch (IOException e) {
            log.warn("Failed to read candidate file {}: {}", candidatesFile, e.getMessage());
        }
    }

    private void rewriteFile() {
        try {
            var lines = new ArrayList<String>(candidates.size());
            for (var candidate : candidates) {
                lines.add(mapper.writeValueAsString(candidate));
            }
            Path tmp = candidatesFile.resolveSibling(candidatesFile.getFileName() + ".tmp");
            Files.write(tmp, lines, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, candidatesFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.error("Failed to persist candidate store: {}", e.getMessage());
        }
    }

    private void append(LearningCandidate candidate) {
        try {
            Files.writeString(
                    candidatesFile,
                    mapper.writeValueAsString(candidate) + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            log.error("Failed to append candidate to store: {}", e.getMessage());
        }
    }

    private byte[] loadOrCreateKey() throws IOException {
        Path keyFile = memoryDir.resolve(KEY_FILE);
        if (Files.isRegularFile(keyFile)) {
            byte[] key = Files.readAllBytes(keyFile);
            if (key.length >= KEY_SIZE_BYTES) {
                return key;
            }
            log.warn("Candidate signing key file too short ({}B), regenerating", key.length);
        }
        byte[] key = new byte[KEY_SIZE_BYTES];
        new SecureRandom().nextBytes(key);
        Files.write(keyFile, key, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.setPosixFilePermissions(keyFile, java.util.Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException e) {
            log.debug("POSIX permissions not supported on this filesystem");
        }
        return key;
    }

    private static String normalizeToolTag(String toolTag) {
        if (toolTag == null || toolTag.isBlank()) {
            return "general";
        }
        return toolTag.toLowerCase();
    }

    public record CandidateObservation(
            MemoryEntry.Category category,
            CandidateKind kind,
            String content,
            String toolTag,
            List<String> tags,
            double score,
            int successDelta,
            int failureDelta,
            String sourceRef,
            Instant occurredAt
    ) {
        public CandidateObservation {
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(tags, "tags");
            if (score < 0.0 || score > 1.0) {
                throw new IllegalArgumentException("score must be in [0.0, 1.0], got: " + score);
            }
        }
    }
}

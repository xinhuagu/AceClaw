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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
    private static final String TRANSITIONS_FILE = "candidate-transitions.jsonl";
    private static final int KEY_SIZE_BYTES = 32;
    private static final Duration DEFAULT_RECENT_WINDOW = Duration.ofDays(30);
    private static final double DEFAULT_MERGE_THRESHOLD = 0.50;
    private static final Duration DEFAULT_RETENTION = Duration.ofDays(90);
    private static final Duration DEFAULT_DECAY_HALF_LIFE = Duration.ofDays(30);
    private static final Duration DEFAULT_DECAY_GRACE = Duration.ofDays(7);
    private static final Duration DEFAULT_MAINTENANCE_INTERVAL = Duration.ofHours(24);

    private final Path memoryDir;
    private final Path candidatesFile;
    private final Path transitionsFile;
    private final ObjectMapper mapper;
    private final MemorySigner signer;
    private final CopyOnWriteArrayList<LearningCandidate> candidates;
    private final ReentrantLock fileLock = new ReentrantLock();
    private final Duration recentWindow;
    private final double mergeThreshold;
    private final CandidateStateMachine stateMachine;
    private final Duration retention;
    private final Duration decayHalfLife;
    private final Duration decayGrace;
    private final Duration maintenanceInterval;
    private final Clock clock;
    private Instant lastMaintenanceAt;

    public CandidateStore(Path aceclawHome) throws IOException {
        this(aceclawHome, DEFAULT_RECENT_WINDOW, DEFAULT_MERGE_THRESHOLD);
    }

    public CandidateStore(Path aceclawHome, CandidateStateMachine.Config smConfig) throws IOException {
        this(aceclawHome, DEFAULT_RECENT_WINDOW, DEFAULT_MERGE_THRESHOLD, smConfig);
    }

    CandidateStore(Path aceclawHome, Duration recentWindow, double mergeThreshold) throws IOException {
        this(aceclawHome, recentWindow, mergeThreshold, CandidateStateMachine.Config.defaults());
    }

    CandidateStore(Path aceclawHome, Duration recentWindow, double mergeThreshold,
                   CandidateStateMachine.Config smConfig) throws IOException {
        this(aceclawHome, recentWindow, mergeThreshold, smConfig,
                DEFAULT_RETENTION, DEFAULT_DECAY_HALF_LIFE, DEFAULT_DECAY_GRACE,
                DEFAULT_MAINTENANCE_INTERVAL, Clock.systemUTC());
    }

    CandidateStore(Path aceclawHome, Duration recentWindow, double mergeThreshold,
                   CandidateStateMachine.Config smConfig, Duration retention,
                   Duration decayHalfLife, Duration decayGrace, Duration maintenanceInterval,
                   Clock clock) throws IOException {
        Objects.requireNonNull(smConfig, "smConfig");
        this.memoryDir = aceclawHome.resolve(MEMORY_DIR);
        this.candidatesFile = memoryDir.resolve(CANDIDATES_FILE);
        this.transitionsFile = memoryDir.resolve(TRANSITIONS_FILE);
        this.recentWindow = Objects.requireNonNull(recentWindow, "recentWindow");
        this.mergeThreshold = mergeThreshold;
        this.retention = Objects.requireNonNull(retention, "retention");
        this.decayHalfLife = Objects.requireNonNull(decayHalfLife, "decayHalfLife");
        this.decayGrace = Objects.requireNonNull(decayGrace, "decayGrace");
        this.maintenanceInterval = Objects.requireNonNull(maintenanceInterval, "maintenanceInterval");
        this.clock = Objects.requireNonNull(clock, "clock");
        Files.createDirectories(memoryDir);

        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.signer = new MemorySigner(loadOrCreateKey());
        this.candidates = new CopyOnWriteArrayList<>();
        this.stateMachine = new CandidateStateMachine(smConfig, clock);
        this.lastMaintenanceAt = now();
    }

    public void load() {
        fileLock.lock();
        try {
            candidates.clear();
            boolean migrated = loadFile();
            var maintenance = runMaintenanceLocked(true);
            boolean needsRewrite = migrated || maintenance.hadChanges();
            if (needsRewrite) {
                rewriteFile();
            }
            if (migrated) {
                log.info("Candidate store migration complete: persisted v{} schema",
                        LearningCandidate.CURRENT_VERSION);
            }
            if (maintenance.hadChanges()) {
                log.info("Candidate store maintenance applied: removed={}, decayed={}",
                        maintenance.removedCount(), maintenance.decayedCount());
            }
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
            } else {
                stored = sign(incoming);
                candidates.add(stored);
            }
            // Always rewrite atomically; avoids partial append corruption on crash.
            rewriteFile();
            return stored;
        } finally {
            fileLock.unlock();
        }
    }

    /**
     * Finds a candidate by its ID.
     */
    public Optional<LearningCandidate> byId(String id) {
        Objects.requireNonNull(id, "id");
        return candidates.stream()
                .filter(c -> c.id().equals(id))
                .findFirst();
    }

    /**
     * Returns all candidates in the given state.
     */
    public List<LearningCandidate> byState(CandidateState state) {
        Objects.requireNonNull(state, "state");
        return candidates.stream()
                .filter(c -> c.state() == state)
                .toList();
    }

    /**
     * Attempts to transition a candidate to the target state.
     * Validates through the state machine, applies the change, re-signs, and persists.
     *
     * @param candidateId the candidate to transition
     * @param targetState the desired new state
     * @param reason      human-readable reason for the transition
     * @return the transition record if successful, empty if gate check failed or candidate not found
     */
    public Optional<CandidateTransition> transition(String candidateId, CandidateState targetState,
                                                     String reason) {
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(targetState, "targetState");
        fileLock.lock();
        try {
            int idx = findIndexById(candidateId);
            if (idx < 0) {
                return Optional.empty();
            }
            var candidate = candidates.get(idx);
            var transitionOpt = stateMachine.evaluate(candidate, targetState, reason);
            if (transitionOpt.isEmpty()) {
                return Optional.empty();
            }
            var transition = transitionOpt.get();
            var updated = applyTransition(candidate, transition);
            candidates.set(idx, updated);
            rewriteFile();
            appendTransition(transition);
            return Optional.of(transition);
        } finally {
            fileLock.unlock();
        }
    }

    /**
     * Applies a transition bypassing promotion/demotion gates, while still enforcing
     * structural transition validity. Intended for manual rollback operations.
     */
    public Optional<CandidateTransition> forceTransition(String candidateId, CandidateState targetState,
                                                          String reason, String reasonCode) {
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(targetState, "targetState");
        fileLock.lock();
        try {
            int idx = findIndexById(candidateId);
            if (idx < 0) {
                return Optional.empty();
            }
            var candidate = candidates.get(idx);
            if (!CandidateStateMachine.isValidTransition(candidate.state(), targetState)) {
                return Optional.empty();
            }
            var transition = new CandidateTransition(
                    candidate.id(), candidate.state(), targetState,
                    reason == null || reason.isBlank() ? "manual-force-transition" : reason,
                    reasonCode == null || reasonCode.isBlank() ? "MANUAL_FORCE" : reasonCode,
                    "manual", 0, 0.0, 0, null, now());
            var updated = applyTransition(candidate, transition);
            candidates.set(idx, updated);
            rewriteFile();
            appendTransition(transition);
            return Optional.of(transition);
        } finally {
            fileLock.unlock();
        }
    }

    /**
     * Manual rollback helper: promoted candidate back to demoted state.
     */
    public Optional<CandidateTransition> rollbackPromoted(String candidateId, String reason) {
        return forceTransition(candidateId, CandidateState.DEMOTED, reason, "MANUAL_ROLLBACK");
    }

    /**
     * Writes back a runtime outcome to a specific candidate ID.
     */
    public Optional<LearningCandidate> recordOutcome(String candidateId, CandidateOutcome outcome) {
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(outcome, "outcome");
        fileLock.lock();
        try {
            int idx = findIndexById(candidateId);
            if (idx < 0) {
                return Optional.empty();
            }
            var candidate = candidates.get(idx);
            var delta = createOutcomeDelta(candidate, outcome);
            var merged = sign(candidate.mergeWith(delta));
            candidates.set(idx, merged);
            rewriteFile();
            return Optional.of(merged);
        } finally {
            fileLock.unlock();
        }
    }

    /**
     * Evaluates all candidates for automatic promotion/demotion.
     * SHADOW candidates are checked for promotion; PROMOTED candidates are checked for demotion.
     *
     * @return list of transitions applied
     */
    public List<CandidateTransition> evaluateAll() {
        fileLock.lock();
        try {
            var maintenance = runMaintenanceLocked(false);
            var transitions = new ArrayList<CandidateTransition>();
            var promotedThisPass = new java.util.HashSet<String>();

            // Evaluate SHADOW/DEMOTED candidates for promotion.
            // DEMOTED candidates are eligible for re-promotion once gates pass again.
            for (int i = 0; i < candidates.size(); i++) {
                var candidate = candidates.get(i);
                if (candidate.state() != CandidateState.SHADOW
                        && candidate.state() != CandidateState.DEMOTED) continue;
                if (hasBlockingAntiPatternForTool(candidate.toolTag())) {
                    continue;
                }

                var transitionOpt = stateMachine.evaluateForPromotion(candidate);
                if (transitionOpt.isPresent()) {
                    var transition = transitionOpt.get();
                    var updated = applyTransition(candidate, transition);
                    candidates.set(i, updated);
                    transitions.add(transition);
                    promotedThisPass.add(candidate.id());
                }
            }

            // Evaluate PROMOTED candidates for demotion.
            // Skip candidates promoted in this pass to avoid same-cycle churn.
            for (int i = 0; i < candidates.size(); i++) {
                var candidate = candidates.get(i);
                if (candidate.state() != CandidateState.PROMOTED) continue;
                if (promotedThisPass.contains(candidate.id())) continue;

                var transitionOpt = stateMachine.evaluateForDemotion(candidate);
                if (transitionOpt.isPresent()) {
                    var transition = transitionOpt.get();
                    var updated = applyTransition(candidate, transition);
                    candidates.set(i, updated);
                    transitions.add(transition);
                }
            }

            if (!transitions.isEmpty() || maintenance.hadChanges()) {
                rewriteFile();
            }
            if (!transitions.isEmpty()) {
                for (var t : transitions) {
                    appendTransition(t);
                }
                log.info("evaluateAll: {} transitions applied", transitions.size());
            }
            if (maintenance.hadChanges()) {
                log.info("evaluateAll maintenance applied: removed={}, decayed={}",
                        maintenance.removedCount(), maintenance.decayedCount());
            }

            return List.copyOf(transitions);
        } finally {
            fileLock.unlock();
        }
    }

    private int findIndexById(String id) {
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private boolean hasBlockingAntiPatternForTool(String toolTag) {
        if (toolTag == null || toolTag.isBlank()) {
            return false;
        }
        var normalized = normalizeToolTag(toolTag);
        var cutoff = now().minus(recentWindow);
        for (var candidate : candidates) {
            if (candidate.kind() != CandidateKind.ANTI_PATTERN) {
                continue;
            }
            if (!normalized.equals(normalizeToolTag(candidate.toolTag()))) {
                continue;
            }
            if (candidate.lastSeenAt().isBefore(cutoff)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private void appendTransition(CandidateTransition transition) {
        try {
            Files.writeString(
                    transitionsFile,
                    mapper.writeValueAsString(transition) + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            log.error("Failed to append transition to audit log: {}", e.getMessage());
        }
    }

    private LearningCandidate applyTransition(LearningCandidate candidate, CandidateTransition transition) {
        var updated = candidate.withState(transition.toState());
        if (transition.toState() == CandidateState.PROMOTED) {
            updated = updated.withCooldownUntil(null);
        } else if (transition.toState() == CandidateState.DEMOTED && transition.cooldownUntil() != null) {
            updated = updated.withCooldownUntil(transition.cooldownUntil());
        }
        return sign(updated);
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
        var now = observation.occurredAt() == null ? now() : observation.occurredAt();
        var sourceRef = observation.sourceRef() == null || observation.sourceRef().isBlank()
                ? ""
                : observation.sourceRef();
        var evidenceEvent = new LearningCandidate.EvidenceEvent(
                sourceRef,
                now,
                Math.max(0, observation.successDelta()),
                Math.max(0, observation.failureDelta()),
                observation.severeFailure(),
                observation.correctionConflict(),
                observation.note()
        );
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
                observation.cooldownUntil(),
                LearningCandidate.CURRENT_VERSION,
                List.of(evidenceEvent),
                sourceRef.isBlank() ? List.of() : List.of(sourceRef),
                null
        );
    }

    private LearningCandidate createOutcomeDelta(LearningCandidate candidate, CandidateOutcome outcome) {
        var observedAt = outcome.occurredAt() == null ? now() : outcome.occurredAt();
        int successDelta = outcome.success() ? 1 : 0;
        int failureDelta = outcome.success() ? 0 : 1;
        double adjustedScore = applyOutcomeScoreDelta(candidate.score(), outcome.success());
        var sourceRef = outcome.sourceRef() == null ? "" : outcome.sourceRef();
        var note = outcome.note() == null ? "" : outcome.note();
        var event = new LearningCandidate.EvidenceEvent(
                sourceRef,
                observedAt,
                successDelta,
                failureDelta,
                outcome.severeFailure(),
                outcome.correctionConflict(),
                note
        );
        return new LearningCandidate(
                candidate.id(),
                candidate.category(),
                candidate.kind(),
                candidate.state(),
                candidate.content(),
                candidate.toolTag(),
                candidate.tags(),
                adjustedScore,
                1,
                successDelta,
                failureDelta,
                observedAt,
                observedAt,
                outcome.cooldownUntil(),
                LearningCandidate.CURRENT_VERSION,
                List.of(event),
                sourceRef.isBlank() ? List.of() : List.of(sourceRef),
                null
        );
    }

    private MaintenanceResult runMaintenanceLocked(boolean force) {
        var now = now();
        if (!force) {
            var elapsed = Duration.between(lastMaintenanceAt, now);
            if (!elapsed.isNegative() && elapsed.compareTo(maintenanceInterval) < 0) {
                return MaintenanceResult.NO_CHANGES;
            }
        }
        int removed = 0;
        int decayed = 0;
        long elapsedSeconds = Math.max(0L, Duration.between(lastMaintenanceAt, now).toSeconds());
        var staleCutoff = now.minus(retention);
        var next = new ArrayList<LearningCandidate>(candidates.size());
        for (var candidate : candidates) {
            if (candidate.lastSeenAt().isBefore(staleCutoff)) {
                removed++;
                continue;
            }
            var age = Duration.between(candidate.lastSeenAt(), now);
            if (age.compareTo(decayGrace) < 0 || elapsedSeconds == 0) {
                next.add(candidate);
                continue;
            }
            double decayFactor = Math.exp(-Math.log(2.0) * (elapsedSeconds / (double) decayHalfLife.toSeconds()));
            double decayedScore = clampScore(candidate.score() * decayFactor);
            if (Math.abs(decayedScore - candidate.score()) > 1e-9) {
                next.add(sign(candidate.withScore(decayedScore)));
                decayed++;
            } else {
                next.add(candidate);
            }
        }
        if (removed > 0 || decayed > 0) {
            candidates.clear();
            candidates.addAll(next);
        }
        lastMaintenanceAt = now;
        return new MaintenanceResult(removed, decayed);
    }

    private static double applyOutcomeScoreDelta(double score, boolean success) {
        return clampScore(success ? score + 0.02 : score - 0.05);
    }

    private static double clampScore(double score) {
        if (score < 0.0) return 0.0;
        if (score > 1.0) return 1.0;
        return score;
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
                candidate.cooldownUntil(),
                candidate.version(),
                candidate.evidence(),
                candidate.sourceRefs(),
                signer.sign(candidate.signablePayload()));
    }

    private boolean loadFile() {
        boolean migrated = false;
        if (!Files.isRegularFile(candidatesFile)) return false;
        try {
            var lines = Files.readAllLines(candidatesFile);
            int loaded = 0;
            int skipped = 0;
            for (var line : lines) {
                if (line.isBlank()) continue;
                try {
                    var candidate = mapper.readValue(line, LearningCandidate.class);
                    if (candidate.hmac() != null && verifyCandidate(candidate)) {
                        if (candidate.isLegacyVersion()) {
                            candidate = sign(candidate.migrateToV2());
                            migrated = true;
                        }
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
        return migrated;
    }

    private boolean verifyCandidate(LearningCandidate candidate) {
        if (signer.verify(candidate.signablePayload(), candidate.hmac())) {
            return true;
        }
        if (!candidate.isLegacyVersion()) {
            return false;
        }
        return signer.verify(legacyPayload(candidate), candidate.hmac());
    }

    private static String legacyPayload(LearningCandidate candidate) {
        return candidate.id() + "|" + candidate.category() + "|" + candidate.kind() + "|"
                + candidate.state() + "|" + candidate.content() + "|" + candidate.toolTag() + "|"
                + String.join(",", candidate.tags()) + "|" + candidate.score() + "|"
                + candidate.evidenceCount() + "|" + candidate.successCount() + "|"
                + candidate.failureCount() + "|" + candidate.firstSeenAt() + "|"
                + candidate.lastSeenAt() + "|" + String.join(",", candidate.sourceRefs());
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

    private Instant now() {
        return Instant.now(clock);
    }

    public record CandidateOutcome(
            boolean success,
            boolean severeFailure,
            boolean correctionConflict,
            String sourceRef,
            String note,
            Instant occurredAt,
            Instant cooldownUntil
    ) {}

    private record MaintenanceResult(int removedCount, int decayedCount) {
        static final MaintenanceResult NO_CHANGES = new MaintenanceResult(0, 0);

        boolean hadChanges() {
            return removedCount > 0 || decayedCount > 0;
        }
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
            Instant occurredAt,
            boolean severeFailure,
            boolean correctionConflict,
            String note,
            Instant cooldownUntil
    ) {
        public CandidateObservation(
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
            this(category, kind, content, toolTag, tags, score, successDelta, failureDelta,
                    sourceRef, occurredAt, false, false, null, null);
        }

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

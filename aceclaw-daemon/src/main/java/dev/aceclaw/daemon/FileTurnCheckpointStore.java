package dev.aceclaw.daemon;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.aceclaw.core.planner.PlanCheckpoint.CheckpointStatus;
import dev.aceclaw.core.planner.TurnCheckpoint;
import dev.aceclaw.core.planner.TurnCheckpointStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * File-based turn checkpoint persistence. Sibling to
 * {@link FilePlanCheckpointStore}; same atomic write pattern (tmp + rename).
 *
 * <p>Layout: {@code {baseDir}/{turnId}.turn.json}
 *
 * <p>Writes compact JSON (no pretty-printer) — turns checkpoint more often
 * than plans, and the file is internal so readability isn't a goal.
 */
public final class FileTurnCheckpointStore implements TurnCheckpointStore {

    private static final Logger log = LoggerFactory.getLogger(FileTurnCheckpointStore.class);
    static final String CHECKPOINT_SUFFIX = ".turn.json";

    private final Path baseDir;
    private final ObjectMapper mapper;
    private final ReentrantLock lock = new ReentrantLock();

    public FileTurnCheckpointStore(Path baseDir, ObjectMapper sharedMapper) {
        Objects.requireNonNull(baseDir, "baseDir");
        Objects.requireNonNull(sharedMapper, "sharedMapper");
        this.baseDir = baseDir;
        this.mapper = sharedMapper.copy()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public void save(TurnCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        lock.lock();
        try {
            persist(checkpointPath(checkpoint.turnId()), CheckpointDto.from(checkpoint));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<TurnCheckpoint> load(String turnId) {
        Objects.requireNonNull(turnId, "turnId");
        lock.lock();
        try {
            return readCheckpoint(checkpointPath(turnId));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<TurnCheckpoint> findResumable(String workspaceHash) {
        Objects.requireNonNull(workspaceHash, "workspaceHash");
        lock.lock();
        try {
            return loadAll().stream()
                    .filter(cp -> workspaceHash.equals(cp.workspaceHash()))
                    .filter(cp -> isResumable(cp.status()))
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<TurnCheckpoint> findBySession(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        lock.lock();
        try {
            return loadAll().stream()
                    .filter(cp -> sessionId.equals(cp.sessionId()))
                    .filter(cp -> isResumable(cp.status()))
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void markResumed(String turnId) {
        updateStatus(turnId, CheckpointStatus.RESUMED);
    }

    @Override
    public void markCompleted(String turnId) {
        updateStatus(turnId, CheckpointStatus.COMPLETED);
    }

    @Override
    public void markFailed(String turnId) {
        updateStatus(turnId, CheckpointStatus.FAILED);
    }

    @Override
    public void markInterrupted(String turnId) {
        updateStatus(turnId, CheckpointStatus.INTERRUPTED);
    }

    @Override
    public void delete(String turnId) {
        Objects.requireNonNull(turnId, "turnId");
        lock.lock();
        try {
            try {
                Files.deleteIfExists(checkpointPath(turnId));
            } catch (IOException e) {
                log.debug("Failed to delete turn checkpoint {}: {}", turnId, e.getMessage());
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int cleanup(int maxAgeDays) {
        lock.lock();
        try {
            if (!Files.isDirectory(baseDir)) {
                return 0;
            }
            var threshold = Instant.now().minus(Duration.ofDays(maxAgeDays));
            int deleted = 0;
            try (var files = Files.list(baseDir)) {
                var candidates = files
                        .filter(p -> p.getFileName().toString().endsWith(CHECKPOINT_SUFFIX))
                        .toList();
                for (var path : candidates) {
                    var parsed = readCheckpoint(path);
                    if (parsed.isEmpty()) {
                        // Corrupt or unparseable — same policy as FilePlanCheckpointStore:
                        // a file we can't read is worse than no file, so prune it.
                        try {
                            if (Files.deleteIfExists(path)) {
                                deleted++;
                                log.debug("Deleted corrupt turn checkpoint: {}", path);
                            }
                        } catch (IOException e) {
                            log.debug("Failed to delete corrupt turn checkpoint {}: {}",
                                    path, e.getMessage());
                        }
                        continue;
                    }
                    var cp = parsed.get();
                    if (cp.updatedAt().isBefore(threshold)) {
                        try {
                            if (Files.deleteIfExists(path)) {
                                deleted++;
                            }
                        } catch (IOException e) {
                            log.debug("Failed to delete old turn checkpoint {}: {}",
                                    path, e.getMessage());
                        }
                    }
                }
            } catch (IOException e) {
                log.warn("Failed to list turn checkpoint directory {}: {}",
                        baseDir, e.getMessage());
            }
            return deleted;
        } finally {
            lock.unlock();
        }
    }

    private void updateStatus(String turnId, CheckpointStatus newStatus) {
        lock.lock();
        try {
            readCheckpoint(checkpointPath(turnId)).ifPresent(cp -> {
                var updated = cp.withStatus(newStatus);
                persist(checkpointPath(turnId), CheckpointDto.from(updated));
            });
        } finally {
            lock.unlock();
        }
    }

    private List<TurnCheckpoint> loadAll() {
        var result = new ArrayList<TurnCheckpoint>();
        if (!Files.isDirectory(baseDir)) {
            return result;
        }
        try (var files = Files.list(baseDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(CHECKPOINT_SUFFIX))
                    .forEach(path -> readCheckpoint(path).ifPresent(result::add));
        } catch (IOException e) {
            log.warn("Failed to list turn checkpoint directory {}: {}", baseDir, e.getMessage());
        }
        return result;
    }

    private Optional<TurnCheckpoint> readCheckpoint(Path path) {
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            var dto = mapper.readValue(path.toFile(), CheckpointDto.class);
            return Optional.of(dto.toDomain());
        } catch (Exception e) {
            log.warn("Failed reading turn checkpoint {}: {}", path, e.getMessage());
            return Optional.empty();
        }
    }

    private Path checkpointPath(String turnId) {
        return baseDir.resolve(turnId + CHECKPOINT_SUFFIX);
    }

    private void persist(Path file, CheckpointDto dto) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            // Compact JSON — turns checkpoint more often than plans and the file
            // is checkpoint-internal so readability isn't a goal.
            String json = mapper.writeValueAsString(dto);
            Files.writeString(tmp, json,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("Failed persisting turn checkpoint {}: {}", file, e.getMessage());
        }
    }

    private static boolean isResumable(CheckpointStatus status) {
        return status == CheckpointStatus.ACTIVE || status == CheckpointStatus.INTERRUPTED;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CheckpointDto(
            String turnId,
            String sessionId,
            String workspaceHash,
            String originalPrompt,
            List<String> conversationSnapshot,
            int completedIterations,
            String lastToolUseId,
            List<String> artifacts,
            String status,
            String createdAt,
            String updatedAt
    ) {
        CheckpointDto {
            conversationSnapshot = conversationSnapshot != null
                    ? List.copyOf(conversationSnapshot) : List.of();
            artifacts = artifacts != null ? List.copyOf(artifacts) : List.of();
        }

        static CheckpointDto from(TurnCheckpoint cp) {
            return new CheckpointDto(
                    cp.turnId(),
                    cp.sessionId(),
                    cp.workspaceHash(),
                    cp.originalPrompt(),
                    cp.conversationSnapshot(),
                    cp.completedIterations(),
                    cp.lastToolUseId(),
                    cp.artifacts(),
                    cp.status().name(),
                    cp.createdAt().toString(),
                    cp.updatedAt().toString()
            );
        }

        TurnCheckpoint toDomain() {
            CheckpointStatus domainStatus;
            try {
                domainStatus = CheckpointStatus.valueOf(status);
            } catch (IllegalArgumentException | NullPointerException e) {
                domainStatus = CheckpointStatus.INTERRUPTED;
            }
            Instant parsedCreatedAt;
            Instant parsedUpdatedAt;
            try {
                parsedCreatedAt = Instant.parse(createdAt);
            } catch (Exception e) {
                parsedCreatedAt = Instant.now();
            }
            try {
                parsedUpdatedAt = Instant.parse(updatedAt);
            } catch (Exception e) {
                parsedUpdatedAt = Instant.now();
            }
            String safePrompt = originalPrompt != null ? originalPrompt : "unknown";
            return new TurnCheckpoint(
                    turnId,
                    sessionId,
                    workspaceHash,
                    safePrompt,
                    conversationSnapshot,
                    completedIterations,
                    lastToolUseId,
                    artifacts,
                    domainStatus,
                    parsedCreatedAt,
                    parsedUpdatedAt
            );
        }
    }
}

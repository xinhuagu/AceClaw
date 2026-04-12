package dev.aceclaw.daemon;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.aceclaw.core.planner.PlanCheckpoint;
import dev.aceclaw.core.planner.PlanCheckpoint.CheckpointStatus;
import dev.aceclaw.core.planner.PlanCheckpointStore;
import dev.aceclaw.core.planner.PlannedStep;
import dev.aceclaw.core.planner.PlanStatus;
import dev.aceclaw.core.planner.StepResult;
import dev.aceclaw.core.planner.StepStatus;
import dev.aceclaw.core.planner.TaskPlan;
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
 * File-based plan checkpoint persistence.
 *
 * <p>Layout: {@code {baseDir}/{planId}.checkpoint.json}
 *
 * <p>Uses atomic write (tmp + rename) for crash safety, following the same
 * pattern as {@code ResumeCheckpointStore} in aceclaw-cli.
 */
public final class FilePlanCheckpointStore implements PlanCheckpointStore {

    private static final Logger log = LoggerFactory.getLogger(FilePlanCheckpointStore.class);
    private static final String CHECKPOINT_SUFFIX = ".checkpoint.json";

    private final Path baseDir;
    private final ObjectMapper mapper;
    private final ReentrantLock lock = new ReentrantLock();

    public FilePlanCheckpointStore(Path baseDir, ObjectMapper sharedMapper) {
        Objects.requireNonNull(baseDir, "baseDir");
        Objects.requireNonNull(sharedMapper, "sharedMapper");
        this.baseDir = baseDir;
        // Create a dedicated mapper for checkpoints with JavaTimeModule
        this.mapper = sharedMapper.copy()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public void save(PlanCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        lock.lock();
        try {
            var dto = CheckpointDto.from(checkpoint);
            persist(checkpointPath(checkpoint.planId()), dto);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<PlanCheckpoint> load(String planId) {
        Objects.requireNonNull(planId, "planId");
        lock.lock();
        try {
            return readCheckpoint(checkpointPath(planId));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<PlanCheckpoint> findResumable(String workspaceHash) {
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
    public List<PlanCheckpoint> findBySession(String sessionId) {
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
    public void markResumed(String planId) {
        updateStatus(planId, CheckpointStatus.RESUMED);
    }

    @Override
    public void markCompleted(String planId) {
        updateStatus(planId, CheckpointStatus.COMPLETED);
    }

    @Override
    public void markFailed(String planId) {
        updateStatus(planId, CheckpointStatus.FAILED);
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
                var toDelete = files
                        .filter(p -> p.getFileName().toString().endsWith(CHECKPOINT_SUFFIX))
                        .toList();
                for (var path : toDelete) {
                    var parsed = readCheckpoint(path);
                    if (parsed.isEmpty()) {
                        // Corrupt or unparseable file — delete it
                        try {
                            if (Files.deleteIfExists(path)) {
                                deleted++;
                                log.debug("Deleted corrupt checkpoint file: {}", path);
                            }
                        } catch (IOException e) {
                            log.debug("Failed to delete corrupt checkpoint {}: {}", path, e.getMessage());
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
                            log.debug("Failed to delete old checkpoint {}: {}", path, e.getMessage());
                        }
                    }
                }
            } catch (IOException e) {
                log.warn("Failed to list checkpoint directory {}: {}", baseDir, e.getMessage());
            }
            return deleted;
        } finally {
            lock.unlock();
        }
    }

    private void updateStatus(String planId, CheckpointStatus newStatus) {
        lock.lock();
        try {
            readCheckpoint(checkpointPath(planId)).ifPresent(cp -> {
                var updated = cp.withStatus(newStatus);
                persist(checkpointPath(planId), CheckpointDto.from(updated));
            });
        } finally {
            lock.unlock();
        }
    }

    private List<PlanCheckpoint> loadAll() {
        var result = new ArrayList<PlanCheckpoint>();
        if (!Files.isDirectory(baseDir)) {
            return result;
        }
        try (var files = Files.list(baseDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(CHECKPOINT_SUFFIX))
                    .forEach(path -> readCheckpoint(path).ifPresent(result::add));
        } catch (IOException e) {
            log.warn("Failed to list checkpoint directory {}: {}", baseDir, e.getMessage());
        }
        return result;
    }

    private Optional<PlanCheckpoint> readCheckpoint(Path path) {
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            var dto = mapper.readValue(path.toFile(), CheckpointDto.class);
            return Optional.of(dto.toDomain());
        } catch (Exception e) {
            log.warn("Failed reading checkpoint {}: {}", path, e.getMessage());
            return Optional.empty();
        }
    }

    private Path checkpointPath(String planId) {
        return baseDir.resolve(planId + CHECKPOINT_SUFFIX);
    }

    private void persist(Path file, CheckpointDto dto) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(dto);
            Files.writeString(tmp, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("Failed persisting plan checkpoint {}: {}", file, e.getMessage());
        }
    }

    private static boolean isResumable(CheckpointStatus status) {
        return status == CheckpointStatus.ACTIVE || status == CheckpointStatus.INTERRUPTED;
    }

    /**
     * DTO for JSON serialization of PlanCheckpoint.
     * Uses simple types to decouple persistence format from domain records.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CheckpointDto(
            String planId,
            String sessionId,
            String workspaceHash,
            String originalGoal,
            PlanDto plan,
            List<StepResultDto> completedStepResults,
            int lastCompletedStepIndex,
            List<String> conversationSnapshot,
            String status,
            String resumeHint,
            List<String> artifacts,
            String createdAt,
            String updatedAt
    ) {
        CheckpointDto {
            completedStepResults = completedStepResults != null ? List.copyOf(completedStepResults) : List.of();
            conversationSnapshot = conversationSnapshot != null ? List.copyOf(conversationSnapshot) : List.of();
            artifacts = artifacts != null ? List.copyOf(artifacts) : List.of();
        }

        static CheckpointDto from(PlanCheckpoint cp) {
            return new CheckpointDto(
                    cp.planId(),
                    cp.sessionId(),
                    cp.workspaceHash(),
                    cp.originalGoal(),
                    PlanDto.from(cp.plan()),
                    cp.completedStepResults().stream().map(StepResultDto::from).toList(),
                    cp.lastCompletedStepIndex(),
                    cp.conversationSnapshot(),
                    cp.status().name(),
                    cp.resumeHint(),
                    cp.artifacts(),
                    cp.createdAt().toString(),
                    cp.updatedAt().toString()
            );
        }

        PlanCheckpoint toDomain() {
            CheckpointStatus domainStatus;
            try {
                domainStatus = CheckpointStatus.valueOf(status);
            } catch (IllegalArgumentException | NullPointerException e) {
                domainStatus = CheckpointStatus.INTERRUPTED; // safe fallback for corrupt data
            }
            Instant parsedCreatedAt;
            Instant parsedUpdatedAt;
            try {
                parsedCreatedAt = Instant.parse(createdAt);
            } catch (Exception e) {
                parsedCreatedAt = Instant.now(); // fallback for corrupt timestamp
            }
            try {
                parsedUpdatedAt = Instant.parse(updatedAt);
            } catch (Exception e) {
                parsedUpdatedAt = Instant.now(); // fallback for corrupt timestamp
            }
            String safeGoal = originalGoal != null ? originalGoal : "unknown";
            return new PlanCheckpoint(
                    planId,
                    sessionId,
                    workspaceHash,
                    safeGoal,
                    plan != null ? plan.toDomain() : new TaskPlan("unknown", safeGoal, List.of(),
                            new PlanStatus.Draft(), parsedCreatedAt),
                    completedStepResults.stream().map(StepResultDto::toDomain).toList(),
                    lastCompletedStepIndex,
                    conversationSnapshot,
                    domainStatus,
                    resumeHint,
                    artifacts,
                    parsedCreatedAt,
                    parsedUpdatedAt
            );
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PlanDto(
            String planId,
            String originalGoal,
            List<StepDto> steps,
            String status,
            String statusDetail,
            String createdAt
    ) {
        PlanDto {
            steps = steps != null ? List.copyOf(steps) : List.of();
        }

        static PlanDto from(TaskPlan plan) {
            String statusName;
            String detail = null;
            switch (plan.status()) {
                case PlanStatus.Draft _ -> statusName = "Draft";
                case PlanStatus.Executing e -> {
                    statusName = "Executing";
                    detail = e.completedSteps() + "/" + e.totalSteps();
                }
                case PlanStatus.Completed c -> {
                    statusName = "Completed";
                    detail = c.totalDuration().toMillis() + "ms";
                }
                case PlanStatus.Failed f -> {
                    statusName = "Failed";
                    detail = f.reason();
                }
            }
            return new PlanDto(
                    plan.planId(),
                    plan.originalGoal(),
                    plan.steps().stream().map(StepDto::from).toList(),
                    statusName,
                    detail,
                    plan.createdAt().toString()
            );
        }

        TaskPlan toDomain() {
            var domainSteps = steps.stream().map(StepDto::toDomain).toList();
            PlanStatus domainStatus = switch (status) {
                case "Executing" -> {
                    if (statusDetail != null && statusDetail.contains("/")) {
                        var parts = statusDetail.split("/");
                        try {
                            yield new PlanStatus.Executing(
                                    Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
                        } catch (NumberFormatException ignored) {
                            // Corrupt status detail — fall through to default
                        }
                    }
                    yield new PlanStatus.Executing(0, domainSteps.size());
                }
                case "Completed" -> {
                    long ms = 0;
                    if (statusDetail != null) {
                        try { ms = Long.parseLong(statusDetail.replace("ms", "")); }
                        catch (NumberFormatException ignored) {}
                    }
                    yield new PlanStatus.Completed(Duration.ofMillis(ms));
                }
                case "Failed" -> new PlanStatus.Failed(statusDetail, null);
                default -> new PlanStatus.Draft();
            };
            return new TaskPlan(planId, originalGoal, domainSteps, domainStatus,
                    Instant.parse(createdAt));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StepDto(
            String stepId,
            String name,
            String description,
            List<String> requiredTools,
            String fallbackApproach,
            String status
    ) {
        StepDto {
            requiredTools = requiredTools != null ? List.copyOf(requiredTools) : List.of();
        }

        static StepDto from(PlannedStep step) {
            return new StepDto(
                    step.stepId(), step.name(), step.description(),
                    step.requiredTools(), step.fallbackApproach(),
                    step.status().name()
            );
        }

        PlannedStep toDomain() {
            StepStatus domainStatus;
            try {
                domainStatus = StepStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
                domainStatus = StepStatus.PENDING;
            }
            return new PlannedStep(stepId, name, description, requiredTools,
                    fallbackApproach, domainStatus);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StepResultDto(
            boolean success,
            String output,
            String error,
            long durationMs,
            int inputTokens,
            int outputTokens,
            int llmRequestCount
    ) {
        static StepResultDto from(StepResult result) {
            return new StepResultDto(
                    result.success(), result.output(), result.error(),
                    result.durationMs(), result.inputTokens(), result.outputTokens(),
                    result.llmRequestCount()
            );
        }

        StepResult toDomain() {
            return new StepResult(success, output, error, durationMs, inputTokens,
                    outputTokens, llmRequestCount);
        }
    }
}

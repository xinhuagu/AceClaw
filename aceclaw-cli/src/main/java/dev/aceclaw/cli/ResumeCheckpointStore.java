package dev.aceclaw.cli;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Persists task resume checkpoints and resolves deterministic continue routing.
 */
final class ResumeCheckpointStore {
    private static final Logger log = LoggerFactory.getLogger(ResumeCheckpointStore.class);
    private static final String CHECKPOINT_SUFFIX = ".checkpoint.json";

    private final Path sessionsDir;
    private final ObjectMapper mapper;
    private final ReentrantLock lock = new ReentrantLock();

    ResumeCheckpointStore(Path homeDir) {
        Objects.requireNonNull(homeDir, "homeDir");
        this.sessionsDir = homeDir.resolve("sessions");
        this.mapper = new ObjectMapper();
    }

    void recordTaskSubmitted(
            String sessionId,
            String taskId,
            String workspaceHash,
            String clientType,
            String clientInstanceId,
            String userGoal,
            boolean foreground) {
        lock.lock();
        try {
            var now = Instant.now().toString();
            var existing = loadCheckpointQuietly(sessionId, taskId).orElse(null);
            var checkpoint = new Checkpoint(
                    taskId,
                    sessionId,
                    existing != null ? existing.createdAt : now,
                    now,
                    Status.RUNNING.name(),
                    workspaceHash,
                    clientType,
                    clientInstanceId,
                    userGoal,
                    existing != null ? existing.currentStep : "Task running",
                    existing != null ? existing.planSteps : List.of(),
                    existing != null ? existing.artifacts : List.of(),
                    existing != null ? existing.lastToolEvents : List.of(),
                    existing != null ? existing.resumeHint : "",
                    foreground
            );
            persist(checkpointPath(sessionId, taskId), checkpoint);
        } finally {
            lock.unlock();
        }
    }

    void recordTaskCompletion(
            String sessionId,
            String taskId,
            Status finalStatus,
            String currentStep,
            String resumeHint,
            List<TaskHandle.ToolEvent> toolEvents) {
        lock.lock();
        try {
            var existing = loadCheckpointQuietly(sessionId, taskId).orElse(null);
            if (existing == null) {
                return;
            }
            var now = Instant.now().toString();
            var convertedEvents = new ArrayList<ToolEvent>();
            if (toolEvents != null) {
                for (var event : toolEvents) {
                    convertedEvents.add(new ToolEvent(
                            event.toolName(),
                            event.eventType(),
                            event.timestamp().toString(),
                            event.isError(),
                            event.durationMs(),
                            event.summary()
                    ));
                }
            }
            var checkpoint = new Checkpoint(
                    existing.taskId,
                    existing.sessionId,
                    existing.createdAt,
                    now,
                    finalStatus.name(),
                    existing.workspaceHash,
                    existing.clientType,
                    existing.clientInstanceId,
                    existing.userGoal,
                    currentStep,
                    existing.planSteps,
                    existing.artifacts,
                    convertedEvents.isEmpty() ? existing.lastToolEvents : List.copyOf(convertedEvents),
                    resumeHint,
                    false
            );
            persist(checkpointPath(sessionId, taskId), checkpoint);
        } finally {
            lock.unlock();
        }
    }

    void markForeground(String sessionId, String taskId, boolean foreground) {
        lock.lock();
        try {
            var existing = loadCheckpointQuietly(sessionId, taskId).orElse(null);
            if (existing == null) {
                return;
            }
            var checkpoint = new Checkpoint(
                    existing.taskId, existing.sessionId, existing.createdAt, Instant.now().toString(),
                    existing.status, existing.workspaceHash, existing.clientType, existing.clientInstanceId,
                    existing.userGoal, existing.currentStep, existing.planSteps, existing.artifacts,
                    existing.lastToolEvents, existing.resumeHint, foreground
            );
            persist(checkpointPath(sessionId, taskId), checkpoint);
        } finally {
            lock.unlock();
        }
    }

    RouteResult routeForContinue(String sessionId, String workspaceHash, String clientInstanceId) {
        lock.lock();
        try {
            var resumable = loadAllResumable();
            var bySession = resumable.stream()
                    .filter(c -> sessionId.equals(c.sessionId))
                    .toList();
            var selected = select(bySession);
            if (selected.isPresent()) {
                return new RouteResult(selected.get(), "session", false);
            }
            var byClientWorkspace = resumable.stream()
                    .filter(c -> workspaceHash.equals(c.workspaceHash) && clientInstanceId.equals(c.clientInstanceId))
                    .toList();
            selected = select(byClientWorkspace);
            if (selected.isPresent()) {
                return new RouteResult(selected.get(), "client-instance", false);
            }
            var byWorkspace = resumable.stream()
                    .filter(c -> workspaceHash.equals(c.workspaceHash))
                    .toList();
            selected = select(byWorkspace);
            if (selected.isPresent()) {
                boolean ambiguous = isAmbiguous(byWorkspace);
                return new RouteResult(selected.get(), "workspace", ambiguous);
            }
            return new RouteResult(null, "fallback", false);
        } finally {
            lock.unlock();
        }
    }

    static String buildResumePrompt(Checkpoint checkpoint, String additionalInstruction) {
        String extra = additionalInstruction == null ? "" : additionalInstruction.trim();
        String lastBlocker = checkpoint.resumeHint == null || checkpoint.resumeHint.isBlank()
                ? checkpoint.currentStep
                : checkpoint.resumeHint;
        String base = """
                [RESUME_CONTEXT]
                sessionId: %s
                taskId: %s
                where_resumed: %s
                goal: %s
                current_step: %s
                last_failure_or_blocker: %s
                next_action: Continue from the current step and finish the task without restarting exploration.
                [/RESUME_CONTEXT]
                """.formatted(
                checkpoint.sessionId,
                checkpoint.taskId,
                checkpoint.updatedAt,
                safe(checkpoint.userGoal),
                safe(checkpoint.currentStep),
                safe(lastBlocker)
        ).trim();
        if (extra.isBlank()) {
            return base;
        }
        return base + "\n\nAdditional instruction:\n" + extra;
    }

    private Optional<Checkpoint> select(List<Checkpoint> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        return candidates.stream().sorted(routeComparator()).findFirst();
    }

    private boolean isAmbiguous(List<Checkpoint> candidates) {
        if (candidates == null || candidates.size() < 2) {
            return false;
        }
        var ordered = candidates.stream().sorted(routeComparator()).toList();
        var first = ordered.get(0);
        var second = ordered.get(1);
        return first.foreground == second.foreground && Objects.equals(first.updatedAt, second.updatedAt);
    }

    private Comparator<Checkpoint> routeComparator() {
        return Comparator
                .comparing((Checkpoint c) -> c.foreground).reversed()
                .thenComparing((Checkpoint c) -> parseTime(c.updatedAt), Comparator.reverseOrder())
                .thenComparing(c -> c.taskId, Comparator.reverseOrder());
    }

    private List<Checkpoint> loadAllResumable() {
        var result = new ArrayList<Checkpoint>();
        if (!Files.isDirectory(sessionsDir)) {
            return result;
        }
        try (var sessions = Files.list(sessionsDir)) {
            sessions.filter(Files::isDirectory).forEach(sessionDir -> {
                var tasksDir = sessionDir.resolve("tasks");
                if (!Files.isDirectory(tasksDir)) {
                    return;
                }
                try (var files = Files.list(tasksDir)) {
                    files.filter(path -> path.getFileName().toString().endsWith(CHECKPOINT_SUFFIX))
                            .forEach(path -> readCheckpoint(path).ifPresent(checkpoint -> {
                                if (isResumable(checkpoint.status)) {
                                    result.add(checkpoint);
                                }
                            }));
                } catch (IOException e) {
                    log.debug("Failed listing checkpoint files in {}: {}", tasksDir, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.debug("Failed listing sessions dir {}: {}", sessionsDir, e.getMessage());
        }
        return result;
    }

    private boolean isResumable(String status) {
        return Status.RUNNING.name().equals(status) || Status.PAUSED.name().equals(status);
    }

    private Optional<Checkpoint> loadCheckpointQuietly(String sessionId, String taskId) {
        return readCheckpoint(checkpointPath(sessionId, taskId));
    }

    private Optional<Checkpoint> readCheckpoint(Path path) {
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            var checkpoint = mapper.readValue(path.toFile(), Checkpoint.class);
            return Optional.of(checkpoint);
        } catch (Exception e) {
            log.warn("Failed reading checkpoint {}: {}", path, e.getMessage());
            return Optional.empty();
        }
    }

    private Path checkpointPath(String sessionId, String taskId) {
        return sessionsDir.resolve(sessionId).resolve("tasks").resolve(taskId + CHECKPOINT_SUFFIX);
    }

    private void persist(Path file, Checkpoint checkpoint) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(checkpoint);
            Files.writeString(tmp, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("Failed persisting resume checkpoint {}: {}", file, e.getMessage());
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static Instant parseTime(String value) {
        if (value == null || value.isBlank()) {
            return Instant.EPOCH;
        }
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            return Instant.EPOCH;
        }
    }

    enum Status {
        RUNNING,
        PAUSED,
        DONE,
        CANCELLED
    }

    record RouteResult(Checkpoint checkpoint, String route, boolean ambiguous) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Checkpoint(
            String taskId,
            String sessionId,
            String createdAt,
            String updatedAt,
            String status,
            String workspaceHash,
            String clientType,
            String clientInstanceId,
            String userGoal,
            String currentStep,
            List<String> planSteps,
            List<String> artifacts,
            List<ToolEvent> lastToolEvents,
            String resumeHint,
            boolean foreground
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ToolEvent(
            String toolName,
            String eventType,
            String timestamp,
            boolean isError,
            long durationMs,
            String summary
    ) {}
}

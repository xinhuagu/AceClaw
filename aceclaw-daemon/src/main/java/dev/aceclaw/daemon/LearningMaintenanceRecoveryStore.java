package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Persists per-project maintenance recovery state so interrupted runs can be retried safely.
 */
public final class LearningMaintenanceRecoveryStore {

    private static final String FILE_NAME = ".aceclaw/metrics/learning-maintenance-state.json";

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public RecoveryState markStarted(Path projectRoot, String workspaceHash, String trigger) throws IOException {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(workspaceHash, "workspaceHash");
        Objects.requireNonNull(trigger, "trigger");
        if (workspaceHash.isBlank() || trigger.isBlank()) {
            throw new IllegalArgumentException("workspaceHash and trigger must not be blank");
        }
        var previous = load(projectRoot).orElse(null);
        int attempt = previous == null ? 1 : previous.attempt() + 1;
        Instant now = Instant.now();
        var next = new RecoveryState(
                workspaceHash,
                projectRoot.toAbsolutePath().normalize().toString(),
                trigger,
                RecoveryStatus.RUNNING,
                attempt,
                previous == null ? now : previous.firstStartedAt(),
                now,
                "");
        write(projectRoot, next);
        return next;
    }

    public void markFailed(Path projectRoot, String workspaceHash, String trigger, Exception error) throws IOException {
        Objects.requireNonNull(error, "error");
        var current = load(projectRoot).orElse(null);
        Instant now = Instant.now();
        var next = new RecoveryState(
                workspaceHash,
                projectRoot.toAbsolutePath().normalize().toString(),
                trigger,
                RecoveryStatus.FAILED,
                current == null ? 1 : current.attempt(),
                current == null ? now : current.firstStartedAt(),
                now,
                truncate(error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()), 300));
        write(projectRoot, next);
    }

    public void clear(Path projectRoot) throws IOException {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Files.deleteIfExists(stateFile(projectRoot));
    }

    public Optional<RecoveryState> load(Path projectRoot) throws IOException {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Path file = stateFile(projectRoot);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            var json = Files.readString(file);
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(mapper.readValue(json, RecoveryState.class));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    public boolean needsRecovery(Path projectRoot, String workspaceHash) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(workspaceHash, "workspaceHash");
        if (workspaceHash.isBlank()) {
            throw new IllegalArgumentException("workspaceHash must not be blank");
        }
        try {
            return load(projectRoot)
                    .filter(state -> workspaceHash.equals(state.workspaceHash()))
                    .filter(state -> state.status() == RecoveryStatus.RUNNING || state.status() == RecoveryStatus.FAILED)
                    .isPresent();
        } catch (IOException e) {
            return false;
        }
    }

    Path stateFile(Path projectRoot) {
        return projectRoot.resolve(FILE_NAME);
    }

    private void write(Path projectRoot, RecoveryState state) throws IOException {
        Path file = stateFile(projectRoot);
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, mapper.writeValueAsString(state),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best effort cleanup
            }
            throw e;
        }
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value == null ? "" : value;
        }
        return value.substring(0, max);
    }

    public record RecoveryState(
            String workspaceHash,
            String projectPath,
            String trigger,
            RecoveryStatus status,
            int attempt,
            Instant firstStartedAt,
            Instant updatedAt,
            String lastError
    ) {
        public RecoveryState {
            workspaceHash = Objects.requireNonNull(workspaceHash, "workspaceHash");
            projectPath = projectPath == null ? "" : projectPath;
            trigger = Objects.requireNonNull(trigger, "trigger");
            status = Objects.requireNonNull(status, "status");
            firstStartedAt = firstStartedAt == null ? Instant.now() : firstStartedAt;
            updatedAt = updatedAt == null ? Instant.now() : updatedAt;
            lastError = lastError == null ? "" : lastError;
            if (workspaceHash.isBlank() || trigger.isBlank()) {
                throw new IllegalArgumentException("workspaceHash and trigger must not be blank");
            }
            if (attempt <= 0) {
                throw new IllegalArgumentException("attempt must be positive");
            }
        }
    }

    public enum RecoveryStatus {
        RUNNING,
        FAILED
    }
}

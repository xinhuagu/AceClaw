package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Stores deferred learning-maintenance summaries as project-local JSONL.
 */
public final class LearningMaintenanceRunStore {

    private static final String FILE_NAME = ".aceclaw/metrics/learning-maintenance-runs.jsonl";

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public void append(Path projectRoot, LearningMaintenanceRun run) throws IOException {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(run, "run");
        Path file = runsFile(projectRoot);
        Files.createDirectories(file.getParent());
        try (var channel = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
             FileLock ignored = channel.lock()) {
            channel.write(java.nio.charset.StandardCharsets.UTF_8.encode(
                    mapper.writeValueAsString(run) + "\n"));
        }
    }

    public List<LearningMaintenanceRun> recent(Path projectRoot, int limit) throws IOException {
        Objects.requireNonNull(projectRoot, "projectRoot");
        int bounded = Math.max(1, limit);
        Path file = runsFile(projectRoot);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        var runs = new ArrayList<LearningMaintenanceRun>();
        for (var line : Files.readAllLines(file)) {
            if (line == null || line.isBlank()) {
                continue;
            }
            try {
                runs.add(mapper.readValue(line, LearningMaintenanceRun.class));
            } catch (Exception ignored) {
                // Best-effort observability only.
            }
        }
        runs.sort(Comparator.comparing(LearningMaintenanceRun::timestamp).reversed());
        if (runs.size() > bounded) {
            return List.copyOf(runs.subList(0, bounded));
        }
        return List.copyOf(runs);
    }

    Path runsFile(Path projectRoot) {
        return projectRoot.resolve(FILE_NAME);
    }
}

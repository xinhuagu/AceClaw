package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Persistent JSONL store for learned-behavior validation records.
 */
public final class LearningValidationStore {

    private static final String METRICS_DIR = ".aceclaw/metrics";
    private static final String FILE_NAME = "learning-validations.jsonl";

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public void append(Path projectRoot, LearningValidation validation) throws java.io.IOException {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(validation, "validation");
        Path file = validationsFile(projectRoot);
        Files.createDirectories(file.getParent());
        try (var channel = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
             var ignored = channel.lock()) {
            Files.writeString(file,
                    mapper.writeValueAsString(validation) + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        }
    }

    public List<LearningValidation> recent(Path projectRoot, int limit) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        int safeLimit = Math.max(1, limit);
        Path file = validationsFile(projectRoot);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            var rows = new ArrayList<LearningValidation>();
            for (var line : Files.readAllLines(file)) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                try {
                    rows.add(mapper.readValue(line, LearningValidation.class));
                } catch (Exception ignored) {
                    // Skip malformed rows; validation history is best-effort.
                }
            }
            return rows.stream()
                    .sorted(Comparator.comparing(LearningValidation::timestamp).reversed())
                    .limit(safeLimit)
                    .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    Path validationsFile(Path projectRoot) {
        return projectRoot.resolve(METRICS_DIR).resolve(FILE_NAME);
    }
}

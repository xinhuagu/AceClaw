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
 * Stores learning explanations as project-local JSONL.
 */
public final class LearningExplanationStore {

    private static final String EXPLANATIONS_FILE = ".aceclaw/metrics/learning-explanations.jsonl";

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public void append(Path projectRoot, LearningExplanation explanation) throws IOException {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(explanation, "explanation");
        Path file = explanationsFile(projectRoot);
        Files.createDirectories(file.getParent());
        try (var channel = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
             FileLock ignored = channel.lock()) {
            channel.write(java.nio.charset.StandardCharsets.UTF_8.encode(
                    mapper.writeValueAsString(explanation) + "\n"));
        }
    }

    public List<LearningExplanation> recent(Path projectRoot, int limit) throws IOException {
        Objects.requireNonNull(projectRoot, "projectRoot");
        int bounded = Math.max(1, limit);
        Path file = explanationsFile(projectRoot);
        if (!Files.exists(file)) {
            return List.of();
        }
        var explanations = new ArrayList<LearningExplanation>();
        for (var line : Files.readAllLines(file)) {
            if (line == null || line.isBlank()) {
                continue;
            }
            try {
                explanations.add(mapper.readValue(line, LearningExplanation.class));
            } catch (Exception ignored) {
                // Skip malformed rows; explainability should be best-effort.
            }
        }
        explanations.sort(Comparator.comparing(LearningExplanation::timestamp).reversed());
        if (explanations.size() > bounded) {
            return List.copyOf(explanations.subList(0, bounded));
        }
        return List.copyOf(explanations);
    }

    Path explanationsFile(Path projectRoot) {
        return projectRoot.resolve(EXPLANATIONS_FILE);
    }
}

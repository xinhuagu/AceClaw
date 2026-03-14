package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Persistent JSONL store for human review of learned signals.
 */
public final class LearningSignalReviewStore {

    private static final String REVIEWS_FILE = ".aceclaw/metrics/learning-signal-reviews.jsonl";

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public void append(Path projectRoot, LearningSignalReview review) throws IOException {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(review, "review");
        Path file = reviewsFile(projectRoot);
        Files.createDirectories(file.getParent());
        try (var channel = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
             var ignored = channel.lock()) {
            var buffer = StandardCharsets.UTF_8.encode(mapper.writeValueAsString(review) + "\n");
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        }
    }

    public List<LearningSignalReview> recent(Path projectRoot, int limit) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        int safeLimit = Math.max(1, limit);
        Path file = reviewsFile(projectRoot);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            var rows = new ArrayList<LearningSignalReview>();
            for (var line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                try {
                    rows.add(mapper.readValue(line, LearningSignalReview.class));
                } catch (Exception ignored) {
                    // Skip malformed rows; review surface is best-effort.
                }
            }
            return rows.stream()
                    .sorted(Comparator.comparing(LearningSignalReview::timestamp).reversed())
                    .limit(safeLimit)
                    .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    public Map<String, LearningSignalReview> latestByTarget(Path projectRoot, int scanLimit) {
        var latest = new LinkedHashMap<String, LearningSignalReview>();
        for (var review : recent(projectRoot, Math.max(1, scanLimit))) {
            latest.putIfAbsent(review.targetKey(), review);
        }
        return Map.copyOf(latest);
    }

    Path reviewsFile(Path projectRoot) {
        return projectRoot.resolve(REVIEWS_FILE);
    }
}

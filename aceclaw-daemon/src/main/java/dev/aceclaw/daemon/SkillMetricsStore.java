package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.aceclaw.core.agent.SkillMetrics;
import dev.aceclaw.core.agent.SkillOutcome;
import dev.aceclaw.core.agent.SkillOutcomeTracker;
import dev.aceclaw.core.agent.SkillRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Persists per-skill metrics sidecars next to the resolved skill directory.
 *
 * <p>This keeps project-scoped skills under {@code project/.aceclaw/skills/...}
 * and user-scoped skills under {@code ~/.aceclaw/skills/...}, matching the
 * existing skill resolution model.
 */
public final class SkillMetricsStore {

    private static final String METRICS_FILE = "metrics.json";

    private final ObjectMapper mapper;

    public SkillMetricsStore() {
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * Loads all persisted skill outcomes for a project into a tracker.
     */
    public SkillOutcomeTracker load(Path projectPath) {
        Objects.requireNonNull(projectPath, "projectPath");
        var tracker = new SkillOutcomeTracker();
        var registry = SkillRegistry.load(projectPath);
        for (var skillName : registry.names()) {
            var config = registry.get(skillName).orElse(null);
            if (config == null) {
                continue;
            }
            Path metricsFile = config.directory().resolve(METRICS_FILE);
            if (!Files.isRegularFile(metricsFile)) {
                continue;
            }
            try {
                JsonNode root = mapper.readTree(metricsFile.toFile());
                for (var outcome : parseOutcomes(root.path("outcomes"))) {
                    tracker.record(skillName, outcome);
                }
            } catch (Exception ignored) {
                // Corrupt metrics sidecars should not block the agent runtime.
            }
        }
        return tracker;
    }

    /**
     * Persists a single skill's outcome history and current metrics.
     */
    public void persist(Path projectPath, String skillName, SkillOutcomeTracker tracker) throws IOException {
        Objects.requireNonNull(projectPath, "projectPath");
        Objects.requireNonNull(skillName, "skillName");
        Objects.requireNonNull(tracker, "tracker");
        var metrics = tracker.getMetrics(skillName).orElse(null);
        if (metrics == null) {
            return;
        }
        persist(projectPath, skillName, tracker, metrics);
    }

    public void persist(Path projectPath, String skillName, SkillOutcomeTracker tracker, SkillMetrics metrics)
            throws IOException {
        Objects.requireNonNull(projectPath, "projectPath");
        Objects.requireNonNull(skillName, "skillName");
        Objects.requireNonNull(tracker, "tracker");
        Objects.requireNonNull(metrics, "metrics");
        var config = SkillRegistry.load(projectPath).get(skillName).orElse(null);
        if (config == null) {
            return;
        }

        Path metricsFile = config.directory().resolve(METRICS_FILE);
        Files.createDirectories(metricsFile.getParent());

        var root = mapper.createObjectNode();
        root.put("skillName", skillName);
        root.set("metrics", mapper.valueToTree(metrics));
        root.set("outcomes", mapper.valueToTree(encodeOutcomes(tracker.outcomes(skillName))));
        Files.writeString(
                metricsFile,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static List<PersistedOutcome> encodeOutcomes(List<SkillOutcome> outcomes) {
        var encoded = new ArrayList<PersistedOutcome>(outcomes.size());
        for (var outcome : outcomes) {
            switch (outcome) {
                case SkillOutcome.Success success -> encoded.add(
                        new PersistedOutcome("success", success.timestamp(), null, null, success.turnsUsed()));
                case SkillOutcome.Failure failure -> encoded.add(
                        new PersistedOutcome("failure", failure.timestamp(), failure.reason(), null, null));
                case SkillOutcome.UserCorrected corrected -> encoded.add(
                        new PersistedOutcome("user_corrected", corrected.timestamp(), null, corrected.correction(), null));
            }
        }
        return encoded;
    }

    private static List<SkillOutcome> parseOutcomes(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        var outcomes = new ArrayList<SkillOutcome>();
        for (var entry : node) {
            String type = entry.path("type").asText("");
            java.time.Instant timestamp;
            try {
                timestamp = entry.hasNonNull("timestamp")
                        ? java.time.Instant.parse(entry.get("timestamp").asText())
                        : java.time.Instant.now();
            } catch (Exception ignored) {
                timestamp = java.time.Instant.now();
            }
            switch (type) {
                case "success" -> outcomes.add(new SkillOutcome.Success(
                        timestamp,
                        Math.max(1, entry.path("turnsUsed").asInt(1))));
                case "failure" -> outcomes.add(new SkillOutcome.Failure(
                        timestamp,
                        entry.path("reason").asText("")));
                case "user_corrected" -> outcomes.add(new SkillOutcome.UserCorrected(
                        timestamp,
                        entry.path("correction").asText("")));
                default -> {
                    // Skip unknown outcome kinds to preserve forward compatibility.
                }
            }
        }
        return List.copyOf(outcomes);
    }

    private record PersistedOutcome(
            String type,
            java.time.Instant timestamp,
            String reason,
            String correction,
            Integer turnsUsed
    ) {}
}

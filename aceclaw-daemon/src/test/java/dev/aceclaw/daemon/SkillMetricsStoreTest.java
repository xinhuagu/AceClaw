package dev.aceclaw.daemon;

import dev.aceclaw.core.agent.SkillOutcome;
import dev.aceclaw.core.agent.SkillOutcomeTracker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillMetricsStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void persistAndLoadRoundTripsMetrics() throws Exception {
        Path skillDir = tempDir.resolve(".aceclaw/skills/review");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                description: "Review code changes"
                context: inline
                ---

                # Review
                Use explicit types.
                """);

        var tracker = new SkillOutcomeTracker();
        tracker.record("review", new SkillOutcome.Success(Instant.parse("2026-03-01T10:00:00Z"), 2));
        tracker.record("review", new SkillOutcome.UserCorrected(
                Instant.parse("2026-03-02T10:00:00Z"), "use explicit types instead"));

        var store = new SkillMetricsStore();
        store.persist(tempDir, "review", tracker);

        Path metricsFile = skillDir.resolve("metrics.json");
        assertThat(metricsFile).exists();

        var loaded = store.load(tempDir);
        var metrics = loaded.getMetrics("review").orElseThrow();
        assertThat(metrics.invocationCount()).isEqualTo(1);
        assertThat(metrics.successCount()).isEqualTo(1);
        assertThat(metrics.correctionCount()).isEqualTo(1);
        assertThat(metrics.avgTurnsUsed()).isEqualTo(2.0);
    }

    @Test
    void loadToleratesMalformedTimestamp() throws Exception {
        Path skillDir = tempDir.resolve(".aceclaw/skills/review");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                description: "Review code changes"
                context: inline
                ---

                # Review
                Use explicit types.
                """);
        Files.writeString(skillDir.resolve("metrics.json"), """
                {
                  "skillName": "review",
                  "outcomes": [
                    { "type": "success", "timestamp": "not-a-timestamp", "turnsUsed": 2 }
                  ]
                }
                """);

        var store = new SkillMetricsStore();
        var loaded = store.load(tempDir);
        var metrics = loaded.getMetrics("review").orElseThrow();
        assertThat(metrics.successCount()).isEqualTo(1);
    }

    @Test
    void publicApisNullGuardInputs() {
        var store = new SkillMetricsStore();
        var tracker = new SkillOutcomeTracker();

        assertThatThrownBy(() -> store.load(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("projectPath");
        assertThatThrownBy(() -> store.persist(tempDir, null, tracker))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("skillName");
        assertThatThrownBy(() -> store.persist(tempDir, "review", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("tracker");
    }
}

package dev.aceclaw.daemon;

import dev.aceclaw.core.agent.SkillMetrics;
import dev.aceclaw.core.agent.SkillOutcome;
import dev.aceclaw.memory.AutoMemoryStore;
import dev.aceclaw.memory.MemoryEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkillMemoryFeedbackTest {

    @TempDir
    Path tempDir;

    private AutoMemoryStore store;
    private SkillMemoryFeedback feedback;

    @BeforeEach
    void setUp() throws Exception {
        store = new AutoMemoryStore(tempDir);
        store.load(tempDir);
        feedback = new SkillMemoryFeedback(store);
    }

    @Test
    void successCreatesSuccessfulStrategyMemory() {
        feedback.onOutcome(
                "review",
                new SkillOutcome.Success(Instant.now(), 2),
                new SkillMetrics("review", 1, 1, 0, 0, 2.0, Instant.now(), 1.0),
                tempDir);

        var memories = store.query(MemoryEntry.Category.SUCCESSFUL_STRATEGY, List.of("review"), 10);
        assertThat(memories).hasSize(1);
        assertThat(memories.getFirst().content()).contains("review");
    }

    @Test
    void failureCreatesAntiPatternAndRecoveryRecipeAfterThreshold() {
        feedback.onOutcome(
                "deploy",
                new SkillOutcome.Failure(Instant.now(), "timeout while verifying build"),
                new SkillMetrics("deploy", 3, 0, 3, 0, 0.0, Instant.now(), 0.0),
                tempDir);

        var antiPatterns = store.query(MemoryEntry.Category.ANTI_PATTERN, List.of("deploy"), 10);
        var recipes = store.query(MemoryEntry.Category.RECOVERY_RECIPE, List.of("deploy"), 10);
        assertThat(antiPatterns).hasSize(1);
        assertThat(recipes).hasSize(1);
    }

    @Test
    void userCorrectionCreatesCorrectionAndPreference() {
        feedback.onOutcome(
                "review",
                new SkillOutcome.UserCorrected(Instant.now(), "use explicit types instead"),
                new SkillMetrics("review", 1, 1, 0, 1, 1.0, Instant.now(), 1.0),
                tempDir);

        var corrections = store.query(MemoryEntry.Category.CORRECTION, List.of("review"), 10);
        var preferences = store.query(MemoryEntry.Category.PREFERENCE, List.of("review"), 10);
        assertThat(corrections).hasSize(1);
        assertThat(preferences).hasSize(1);
    }

    @Test
    void duplicateSuccessDoesNotCreateDuplicateMemory() {
        var now = Instant.now();
        var metrics = new SkillMetrics("review", 2, 2, 0, 0, 1.0, now, 1.0);

        feedback.onOutcome("review", new SkillOutcome.Success(now, 1), metrics, tempDir);
        feedback.onOutcome("review", new SkillOutcome.Success(now.plusSeconds(5), 1), metrics, tempDir);

        var memories = store.query(MemoryEntry.Category.SUCCESSFUL_STRATEGY, List.of("review"), 10);
        assertThat(memories).hasSize(1);
    }

    @Test
    void projectScopedFeedbackDoesNotDedupAgainstGlobalMemory() {
        var now = Instant.now();
        var metrics = new SkillMetrics("review", 1, 1, 0, 0, 1.0, now, 1.0);
        var content = "Skill 'review' completed successfully and reinforced its current strategy.";
        store.add(
                MemoryEntry.Category.SUCCESSFUL_STRATEGY,
                content,
                List.of("review", "skill-feedback", "successful-strategy"),
                "skill:review",
                true,
                tempDir);

        feedback.onOutcome("review", new SkillOutcome.Success(now, 1), metrics, tempDir);

        var memories = store.query(MemoryEntry.Category.SUCCESSFUL_STRATEGY, List.of("review"), 10);
        assertThat(memories).hasSize(2);
    }
}

package dev.aceclaw.memory;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InsightTest {

    @Test
    void errorInsightTargetCategory() {
        var insight = Insight.ErrorInsight.of("bash", "command not found", "use full path", 0.9);
        assertThat(insight.targetCategory()).isEqualTo(MemoryEntry.Category.ERROR_RECOVERY);
    }

    @Test
    void errorInsightDescription() {
        var insight = Insight.ErrorInsight.of("read_file", "file not found", "check path exists", 0.8);
        assertThat(insight.description()).contains("read_file").contains("file not found").contains("check path exists");
    }

    @Test
    void errorInsightTags() {
        var insight = Insight.ErrorInsight.of("grep", "pattern error", "escape regex", 0.7);
        assertThat(insight.tags()).contains("grep", "error-recovery");
    }

    @Test
    void successInsightTargetCategory() {
        var insight = new Insight.SuccessInsight(List.of("grep", "read_file", "edit_file"), "find and fix bug", 0.85);
        assertThat(insight.targetCategory()).isEqualTo(MemoryEntry.Category.SUCCESSFUL_STRATEGY);
    }

    @Test
    void successInsightDefensiveCopy() {
        var mutable = new ArrayList<>(List.of("grep", "read_file"));
        var insight = new Insight.SuccessInsight(mutable, "search and read", 0.8);
        mutable.add("write_file");
        assertThat(insight.toolSequence()).hasSize(2);
    }

    @Test
    void successInsightTags() {
        var insight = new Insight.SuccessInsight(List.of("glob", "read_file"), "explore", 0.75);
        assertThat(insight.tags()).contains("glob", "read_file", "successful-strategy");
    }

    @Test
    void patternInsightTargetCategoryForToolSequence() {
        var insight = new Insight.PatternInsight(
                PatternType.REPEATED_TOOL_SEQUENCE, "grep->read->edit", 5, 0.9, List.of("evidence1"));
        assertThat(insight.targetCategory()).isEqualTo(MemoryEntry.Category.PATTERN);
    }

    @Test
    void patternInsightTargetCategoryForWorkflow() {
        var insight = new Insight.PatternInsight(
                PatternType.WORKFLOW, "build-test-fix", 3, 0.8, List.of("evidence1"));
        assertThat(insight.targetCategory()).isEqualTo(MemoryEntry.Category.PATTERN);
    }

    @Test
    void patternInsightTargetCategoryForErrorCorrection() {
        var insight = new Insight.PatternInsight(
                PatternType.ERROR_CORRECTION, "retry with different args", 4, 0.85, List.of("e1"));
        assertThat(insight.targetCategory()).isEqualTo(MemoryEntry.Category.ERROR_RECOVERY);
    }

    @Test
    void patternInsightTargetCategoryForUserPreference() {
        var insight = new Insight.PatternInsight(
                PatternType.USER_PREFERENCE, "prefer concise output", 2, 0.7, List.of("e1"));
        assertThat(insight.targetCategory()).isEqualTo(MemoryEntry.Category.PREFERENCE);
    }

    @Test
    void patternInsightDefensiveCopyOnEvidence() {
        var mutable = new ArrayList<>(List.of("evidence1", "evidence2"));
        var insight = new Insight.PatternInsight(
                PatternType.WORKFLOW, "multi-step", 3, 0.8, mutable);
        mutable.add("evidence3");
        assertThat(insight.evidence()).hasSize(2);
    }

    @Test
    void sealedExhaustivenessSwitch() {
        List<Insight> insights = List.of(
                Insight.ErrorInsight.of("bash", "err", "fix", 0.5),
                new Insight.SuccessInsight(List.of("grep"), "search", 0.6),
                new Insight.PatternInsight(PatternType.WORKFLOW, "flow", 3, 0.7, List.of("e")),
                new Insight.RecoveryRecipe("file not found", List.of(
                        new Insight.RecoveryStep("check path", "glob", "*.java")), "read_file", 0.8)
        );

        for (Insight insight : insights) {
            String type = switch (insight) {
                case Insight.ErrorInsight _ -> "error";
                case Insight.SuccessInsight _ -> "success";
                case Insight.PatternInsight _ -> "pattern";
                case Insight.RecoveryRecipe _ -> "recipe";
            };
            assertThat(type).isNotEmpty();
        }
    }

    @Test
    void confidenceBoundsValidation() {
        assertThatThrownBy(() -> Insight.ErrorInsight.of("bash", "err", "fix", -0.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confidence");

        assertThatThrownBy(() -> Insight.ErrorInsight.of("bash", "err", "fix", 1.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confidence");

        assertThatThrownBy(() -> new Insight.SuccessInsight(List.of("grep"), "task", -0.01))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new Insight.PatternInsight(
                PatternType.WORKFLOW, "desc", 1, 2.0, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void confidenceBoundaryValues() {
        // 0.0 and 1.0 should both be accepted
        var zero = Insight.ErrorInsight.of("bash", "err", "fix", 0.0);
        assertThat(zero.confidence()).isEqualTo(0.0);

        var one = Insight.ErrorInsight.of("bash", "err", "fix", 1.0);
        assertThat(one.confidence()).isEqualTo(1.0);
    }

    @Test
    void nullFieldsRejected() {
        assertThatThrownBy(() -> Insight.ErrorInsight.of(null, "err", "fix", 0.5))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new Insight.SuccessInsight(null, "task", 0.5))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new Insight.PatternInsight(null, "desc", 1, 0.5, List.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void patternInsightNegativeFrequencyRejected() {
        assertThatThrownBy(() -> new Insight.PatternInsight(
                PatternType.WORKFLOW, "desc", -1, 0.5, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("frequency");
    }
}

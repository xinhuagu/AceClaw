package dev.aceclaw.memory;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DetectedPatternTest {

    @Test
    void constructorValidation() {
        var pattern = new DetectedPattern(
                PatternType.REPEATED_TOOL_SEQUENCE,
                "grep->read->edit repeated",
                5,
                0.9,
                List.of("session:abc turn 3", "session:def turn 7"));

        assertThat(pattern.type()).isEqualTo(PatternType.REPEATED_TOOL_SEQUENCE);
        assertThat(pattern.description()).isEqualTo("grep->read->edit repeated");
        assertThat(pattern.frequency()).isEqualTo(5);
        assertThat(pattern.confidence()).isEqualTo(0.9);
        assertThat(pattern.evidence()).hasSize(2);
    }

    @Test
    void evidenceImmutability() {
        var mutable = new ArrayList<>(List.of("evidence1", "evidence2"));
        var pattern = new DetectedPattern(
                PatternType.WORKFLOW, "build flow", 3, 0.8, mutable);

        // Mutating the original list should not affect the record
        mutable.add("evidence3");
        assertThat(pattern.evidence()).hasSize(2);

        // The evidence list itself should be immutable
        assertThatThrownBy(() -> pattern.evidence().add("new"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void confidenceBoundsRejected() {
        assertThatThrownBy(() -> new DetectedPattern(
                PatternType.WORKFLOW, "desc", 1, -0.1, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confidence");

        assertThatThrownBy(() -> new DetectedPattern(
                PatternType.WORKFLOW, "desc", 1, 1.01, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confidence");
    }

    @Test
    void confidenceBoundaryValuesAccepted() {
        var zero = new DetectedPattern(PatternType.WORKFLOW, "desc", 1, 0.0, List.of());
        assertThat(zero.confidence()).isEqualTo(0.0);

        var one = new DetectedPattern(PatternType.WORKFLOW, "desc", 1, 1.0, List.of());
        assertThat(one.confidence()).isEqualTo(1.0);
    }

    @Test
    void negativeFrequencyRejected() {
        assertThatThrownBy(() -> new DetectedPattern(
                PatternType.WORKFLOW, "desc", -1, 0.5, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("frequency");
    }

    @Test
    void nullFieldsRejected() {
        assertThatThrownBy(() -> new DetectedPattern(null, "desc", 1, 0.5, List.of()))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new DetectedPattern(PatternType.WORKFLOW, null, 1, 0.5, List.of()))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new DetectedPattern(PatternType.WORKFLOW, "desc", 1, 0.5, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void toInsightConversion() {
        var pattern = new DetectedPattern(
                PatternType.ERROR_CORRECTION,
                "retry with full path",
                4,
                0.85,
                List.of("session:abc turn 2", "session:def turn 5"));

        var insight = pattern.toInsight();

        assertThat(insight.patternType()).isEqualTo(PatternType.ERROR_CORRECTION);
        assertThat(insight.description()).isEqualTo("retry with full path");
        assertThat(insight.frequency()).isEqualTo(4);
        assertThat(insight.confidence()).isEqualTo(0.85);
        assertThat(insight.evidence()).containsExactly("session:abc turn 2", "session:def turn 5");
        assertThat(insight.targetCategory()).isEqualTo(MemoryEntry.Category.ERROR_RECOVERY);
    }
}

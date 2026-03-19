package dev.aceclaw.core.stats;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkScorecardTest {

    @Test
    void evaluate_allMetricsPass() {
        var deltas = Map.of(
                "replay_success_rate_delta", 0.05,
                "first_try_success_rate_delta", 0.02,
                "retry_count_per_task_delta", -0.1,
                "replay_token_delta", 0.05,
                "replay_latency_delta_ms", 100.0);
        var sizes = Map.of(
                "replay_success_rate_delta", 20,
                "first_try_success_rate_delta", 20,
                "retry_count_per_task_delta", 20,
                "replay_token_delta", 20,
                "replay_latency_delta_ms", 20,
                "promotion_precision", 15,
                "false_learning_rate", 15,
                "rollback_rate", 15);
        var rates = Map.of(
                "promotion_precision", 0.90,
                "false_learning_rate", 0.05,
                "rollback_rate", 0.10);

        var scorecard = BenchmarkScorecard.evaluate(deltas, sizes, rates);
        assertThat(scorecard.pass()).isTrue();
        assertThat(scorecard.failures()).isEmpty();
        assertThat(scorecard.metrics()).hasSize(8);
    }

    @Test
    void evaluate_effectivenessRegression_fails() {
        var deltas = Map.of(
                "replay_success_rate_delta", -0.05); // regression
        var sizes = Map.of("replay_success_rate_delta", 20);

        var scorecard = BenchmarkScorecard.evaluate(deltas, sizes, Map.of());
        assertThat(scorecard.pass()).isFalse();
        assertThat(scorecard.failures()).anyMatch(f -> f.contains("replay_success_rate_delta"));
    }

    @Test
    void evaluate_safetyRegression_fails() {
        var rates = Map.of("false_learning_rate", 0.25); // exceeds 0.10 threshold
        var sizes = Map.of("false_learning_rate", 20);

        var scorecard = BenchmarkScorecard.evaluate(Map.of(), sizes, rates);
        assertThat(scorecard.pass()).isFalse();
        assertThat(scorecard.failures()).anyMatch(f -> f.contains("false_learning_rate"));
    }

    @Test
    void evaluate_insufficientSamples_notFail() {
        var deltas = Map.of("replay_success_rate_delta", -0.10); // would fail, but n<10
        var sizes = Map.of("replay_success_rate_delta", 5);

        var scorecard = BenchmarkScorecard.evaluate(deltas, sizes, Map.of());
        // Insufficient data should NOT count as failure
        assertThat(scorecard.failures()).noneMatch(f -> f.contains("replay_success_rate_delta"));
        assertThat(scorecard.metrics()).anyMatch(m ->
                m.name().equals("replay_success_rate_delta")
                        && m.status() == BenchmarkScorecard.Status.INSUFFICIENT_DATA);
    }

    @Test
    void evaluate_missingMetrics_insufficientData() {
        var scorecard = BenchmarkScorecard.evaluate(Map.of(), Map.of(), Map.of());
        assertThat(scorecard.pass()).isTrue(); // no failures, just insufficient data
        assertThat(scorecard.metrics()).allMatch(m ->
                m.status() == BenchmarkScorecard.Status.INSUFFICIENT_DATA);
    }

    @Test
    void byCategory_groupsCorrectly() {
        var deltas = Map.of("replay_success_rate_delta", 0.05);
        var rates = Map.of("promotion_precision", 0.90);
        var sizes = Map.of("replay_success_rate_delta", 20, "promotion_precision", 20);

        var scorecard = BenchmarkScorecard.evaluate(deltas, sizes, rates);
        var grouped = scorecard.byCategory();

        assertThat(grouped).containsKey(BenchmarkScorecard.Category.EFFECTIVENESS);
        assertThat(grouped).containsKey(BenchmarkScorecard.Category.SAFETY);
    }

    @Test
    void toSummary_producesReadableOutput() {
        var deltas = Map.of("replay_success_rate_delta", 0.05);
        var sizes = Map.of("replay_success_rate_delta", 20);

        var scorecard = BenchmarkScorecard.evaluate(deltas, sizes, Map.of());
        String summary = scorecard.toSummary();

        assertThat(summary).contains("Self-Learning Benchmark Scorecard");
        assertThat(summary).contains("EFFECTIVENESS");
        assertThat(summary).contains("replay_success_rate_delta");
    }

    @Test
    void lowerIsBetter_passesWhenBelowThreshold() {
        var deltas = Map.of("retry_count_per_task_delta", -0.5); // negative = improved
        var sizes = Map.of("retry_count_per_task_delta", 20);

        var scorecard = BenchmarkScorecard.evaluate(deltas, sizes, Map.of());
        assertThat(scorecard.metrics()).anyMatch(m ->
                m.name().equals("retry_count_per_task_delta")
                        && m.status() == BenchmarkScorecard.Status.PASS);
    }
}

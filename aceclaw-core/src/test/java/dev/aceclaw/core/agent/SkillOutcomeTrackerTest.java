package dev.aceclaw.core.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SkillOutcomeTrackerTest {

    private SkillOutcomeTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new SkillOutcomeTracker();
    }

    @Test
    void recordsSuccessFailureAndCorrection() {
        tracker.record("retry-safe", new SkillOutcome.Success(Instant.parse("2026-03-01T10:00:00Z"), 2));
        tracker.record("retry-safe", new SkillOutcome.Failure(Instant.parse("2026-03-02T10:00:00Z"), "timeout"));
        tracker.record("retry-safe", new SkillOutcome.UserCorrected(
                Instant.parse("2026-03-03T10:00:00Z"), "use explicit types"));

        var metrics = tracker.getMetrics("retry-safe").orElseThrow();
        assertThat(metrics.invocationCount()).isEqualTo(2);
        assertThat(metrics.successCount()).isEqualTo(1);
        assertThat(metrics.failureCount()).isEqualTo(1);
        assertThat(metrics.correctionCount()).isEqualTo(1);
        assertThat(metrics.avgTurnsUsed()).isEqualTo(2.0);
        assertThat(metrics.lastUsed()).isEqualTo(Instant.parse("2026-03-03T10:00:00Z"));
    }

    @Test
    void reportsRates() {
        tracker.record("review", new SkillOutcome.Success(Instant.now(), 1));
        tracker.record("review", new SkillOutcome.Success(Instant.now(), 1));
        tracker.record("review", new SkillOutcome.UserCorrected(Instant.now(), "not that"));
        tracker.record("review", new SkillOutcome.Failure(Instant.now(), "bad output"));

        var metrics = tracker.getMetrics("review").orElseThrow();
        assertThat(metrics.successRate()).isEqualTo(2.0 / 3.0);
        assertThat(metrics.correctionRate()).isEqualTo(1.0 / 3.0);
    }

    @Test
    void timeDecayRewardsRecentSuccess() {
        tracker.record("deploy", new SkillOutcome.Failure(Instant.now().minus(SkillOutcomeTracker.HALF_LIFE.multipliedBy(3)), "old"));
        tracker.record("deploy", new SkillOutcome.Success(Instant.now(), 1));

        var metrics = tracker.getMetrics("deploy").orElseThrow();
        assertThat(metrics.timeDecayScore()).isGreaterThan(0.8);
    }

    @Test
    void returnsAllMetrics() {
        tracker.record("review", new SkillOutcome.Success(Instant.now(), 1));
        tracker.record("deploy", new SkillOutcome.Failure(Instant.now(), "bad"));

        var all = tracker.allMetrics();
        assertThat(all).hasSize(2);
        assertThat(all).containsKeys("review", "deploy");
    }

    @Test
    void preservesOutcomeHistoryCopies() {
        var outcome = new SkillOutcome.Success(Instant.now(), 1);
        tracker.record("review", outcome);

        var outcomes = tracker.outcomes("review");
        assertThat(outcomes).containsExactly(outcome);
    }
}

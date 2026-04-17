package dev.aceclaw.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContextMonitorTest {

    @Test
    void recordsPeakTrendAndDeduplicatedHistory() {
        var monitor = new ContextMonitor(200_000);

        monitor.recordStreamingUsage(20_000);
        monitor.recordStreamingUsage(20_000);
        monitor.recordStreamingUsage(48_000);
        monitor.recordTurnComplete(1_000, 400, 0);

        assertThat(monitor.currentContextTokens()).isEqualTo(48_000);
        assertThat(monitor.peakContextTokens()).isEqualTo(48_000);
        assertThat(monitor.sampleCount()).isEqualTo(2);
        assertThat(monitor.recentTrend()).isEqualTo(ContextMonitor.Trend.RISING);
        assertThat(monitor.totalInput()).isEqualTo(1_000);
        assertThat(monitor.totalOutput()).isEqualTo(400);
    }

    @Test
    void recordLlmRequestsAccumulatesAcrossTurns() {
        var monitor = new ContextMonitor(200_000);

        monitor.recordLlmRequests(1);
        monitor.recordLlmRequests(3);
        monitor.recordLlmRequests(2);

        assertThat(monitor.totalLlmRequests()).isEqualTo(6);
    }

    @Test
    void recordLlmRequestsIgnoresZeroAndNegativeValues() {
        // A turn with no LLM call (e.g. cancelled before send) reports llmRequests=0 in the
        // usage payload. The counter must not advance on those, and must never go backward.
        var monitor = new ContextMonitor(200_000);

        monitor.recordLlmRequests(2);
        monitor.recordLlmRequests(0);
        monitor.recordLlmRequests(-1);

        assertThat(monitor.totalLlmRequests()).isEqualTo(2);
    }

    @Test
    void recentTrendCanFallAndThenStabilize() {
        var monitor = new ContextMonitor(100_000);

        monitor.recordStreamingUsage(60_000);
        monitor.recordStreamingUsage(30_000);
        assertThat(monitor.recentTrend()).isEqualTo(ContextMonitor.Trend.FALLING);

        monitor.recordStreamingUsage(31_000);
        assertThat(monitor.recentTrend()).isEqualTo(ContextMonitor.Trend.STABLE);
    }

    @Test
    void recordCompactionUpdatesCurrentContextAndSummary() {
        var monitor = new ContextMonitor(200_000);

        monitor.recordStreamingUsage(150_000);
        monitor.recordCompaction(150_000, 62_000, "SUMMARIZED");

        assertThat(monitor.currentContextTokens()).isEqualTo(62_000);
        assertThat(monitor.peakContextTokens()).isEqualTo(150_000);
        assertThat(monitor.compactionCount()).isEqualTo(1);
        assertThat(monitor.lastCompactionOriginalTokens()).isEqualTo(150_000);
        assertThat(monitor.lastCompactionCompactedTokens()).isEqualTo(62_000);
        assertThat(monitor.lastCompactionTokensSaved()).isEqualTo(88_000);
        assertThat(monitor.totalCompactionTokensSaved()).isEqualTo(88_000);
        assertThat(monitor.averageCompactionTokensSaved()).isEqualTo(88_000);
        assertThat(monitor.lastCompactionPhase()).isEqualTo("SUMMARIZED");
        assertThat(monitor.pressureLevel()).isEqualTo(ContextMonitor.PressureLevel.NORMAL);
    }

    @Test
    void recordCompactionSanitizesUnsafePhaseText() {
        var monitor = new ContextMonitor(200_000);

        monitor.recordCompaction(
                120_000,
                40_000,
                "\u001B[31mSUMMARIZED\u001B[0m\nnext\tstep\u0007 " + "x".repeat(140));

        assertThat(monitor.lastCompactionPhase()).startsWith("SUMMARIZED NEXT STEP ");
        assertThat(monitor.lastCompactionPhase()).doesNotContain("\u001B");
        assertThat(monitor.lastCompactionPhase()).doesNotContain("\n");
        assertThat(monitor.lastCompactionPhase()).doesNotContain("\t");
        assertThat(monitor.lastCompactionPhase().length()).isLessThanOrEqualTo(100);
    }

    @Test
    void recordCompactionFallsBackToUnknownWhenPhaseBecomesEmpty() {
        var monitor = new ContextMonitor(200_000);

        monitor.recordCompaction(100_000, 50_000, "\u001B[31m\u001B[0m \n\t");

        assertThat(monitor.lastCompactionPhase()).isEqualTo("UNKNOWN");
    }

    @Test
    void recordCompactionNormalizesMixedCasePhaseForCounters() {
        var monitor = new ContextMonitor(200_000);

        monitor.recordCompaction(90_000, 45_000, "summarized");
        monitor.recordCompaction(45_000, 20_000, "PrUnEd");

        assertThat(monitor.lastCompactionPhase()).isEqualTo("PRUNED");
        assertThat(monitor.summarizedCount()).isEqualTo(1);
        assertThat(monitor.prunedCount()).isEqualTo(1);
        assertThat(monitor.totalCompactionTokensSaved()).isEqualTo(70_000);
        assertThat(monitor.averageCompactionTokensSaved()).isEqualTo(35_000);
    }
}

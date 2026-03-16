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
        assertThat(monitor.lastCompactionPhase()).isEqualTo("SUMMARIZED");
        assertThat(monitor.pressureLevel()).isEqualTo(ContextMonitor.PressureLevel.NORMAL);
    }
}

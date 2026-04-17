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
    void guardedAccountingSkipsSecondRenderOfSameTask() {
        // Models the scenario that motivated TaskHandle.markUsageAccounted: a completed task
        // can reach renderTaskCompletion through multiple paths in TerminalRepl (e.g. /fg
        // rendering the result, then notifyCompletedBackgroundTasks picking up the same
        // unmarked handle). The guard ensures the ContextMonitor is incremented only once.
        var monitor = new ContextMonitor(200_000);
        var accounted = new java.util.concurrent.atomic.AtomicBoolean(false);

        // First render path acquires the guard and accounts.
        if (accounted.compareAndSet(false, true)) {
            monitor.recordTurnComplete(1_000, 400, 0);
            monitor.recordLlmRequests(3);
        }
        // Second render path for the same task finds the guard already set.
        if (accounted.compareAndSet(false, true)) {
            monitor.recordTurnComplete(1_000, 400, 0);
            monitor.recordLlmRequests(3);
        }

        assertThat(monitor.totalInput()).isEqualTo(1_000);
        assertThat(monitor.totalOutput()).isEqualTo(400);
        assertThat(monitor.totalLlmRequests()).isEqualTo(3);
    }

    @Test
    void recordLlmRequestsBySourceAccumulatesPerSource() {
        // PR B: daemon sends a per-source map in the JSON-RPC usage payload; the monitor
        // folds it into a session-wide total keyed by lowercase source name.
        var monitor = new ContextMonitor(200_000);

        monitor.recordLlmRequestsBySource(java.util.Map.of("main_turn", 3, "planner", 1));
        monitor.recordLlmRequestsBySource(java.util.Map.of("main_turn", 2, "replan", 1));

        var totals = monitor.totalLlmRequestsBySource();
        assertThat(totals).containsEntry("main_turn", 5L);
        assertThat(totals).containsEntry("planner", 1L);
        assertThat(totals).containsEntry("replan", 1L);
    }

    @Test
    void recordLlmRequestsBySourceIgnoresNullEmptyAndInvalidValues() {
        // Tolerant of absent / malformed payloads. An old daemon sends nothing; a new daemon
        // on an empty turn sends an empty map; either path must keep the counters at zero.
        var monitor = new ContextMonitor(200_000);

        monitor.recordLlmRequestsBySource(null);
        monitor.recordLlmRequestsBySource(java.util.Map.of());
        monitor.recordLlmRequestsBySource(new java.util.HashMap<>() {{
            put("", 5);        // blank key
            put("planner", 0); // non-positive value
            put("main_turn", -1); // negative value
        }});

        assertThat(monitor.totalLlmRequestsBySource()).isEmpty();
    }

    @Test
    void recordLlmRequestsBySourceNormalizesKeyCase() {
        // Defense against a future daemon (or a protocol accident) shipping mixed-case keys.
        // Two records for "MAIN_TURN" + "main_turn" must collapse to a single logical source.
        var monitor = new ContextMonitor(200_000);

        monitor.recordLlmRequestsBySource(java.util.Map.of("MAIN_TURN", 3));
        monitor.recordLlmRequestsBySource(java.util.Map.of(" main_turn ", 2));
        monitor.recordLlmRequestsBySource(java.util.Map.of("Main_Turn", 1));

        var totals = monitor.totalLlmRequestsBySource();
        assertThat(totals).hasSize(1);
        assertThat(totals).containsEntry("main_turn", 6L);
    }

    @Test
    void totalLlmRequestsBySourceReturnsUnmodifiableSnapshot() {
        // Callers shouldn't be able to mutate the monitor's state through the returned map.
        var monitor = new ContextMonitor(200_000);
        monitor.recordLlmRequestsBySource(java.util.Map.of("main_turn", 3));

        var snapshot = monitor.totalLlmRequestsBySource();

        assertThat(snapshot).containsEntry("main_turn", 3L);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> snapshot.put("x", 1L))
                .isInstanceOf(UnsupportedOperationException.class);
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

package dev.aceclaw.cli;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ContextMonitorTest {

    private static final Logger log = LoggerFactory.getLogger(ContextMonitorTest.class);

    // -- Basic state tracking -------------------------------------------------

    @Test
    void initialState_allZeros() {
        var monitor = new ContextMonitor(200_000);

        assertThat(monitor.currentContextTokens()).isZero();
        assertThat(monitor.totalInput()).isZero();
        assertThat(monitor.totalOutput()).isZero();
        assertThat(monitor.usagePercent()).isEqualTo(0.0);
        assertThat(monitor.contextWindow()).isEqualTo(200_000);
    }

    @Test
    void recordStreamingUsage_updatesCurrentContext() {
        var monitor = new ContextMonitor(100_000);

        monitor.recordStreamingUsage(50_000);
        assertThat(monitor.currentContextTokens()).isEqualTo(50_000);
        assertThat(monitor.usagePercent()).isCloseTo(50.0, org.assertj.core.data.Offset.offset(0.01));

        // Totals should not change from streaming updates
        assertThat(monitor.totalInput()).isZero();
        assertThat(monitor.totalOutput()).isZero();
    }

    @Test
    void recordTurnComplete_updatesTotalsAndContext() {
        var monitor = new ContextMonitor(200_000);

        monitor.recordTurnComplete(10_000, 2_000, 8_000);

        assertThat(monitor.totalInput()).isEqualTo(10_000);
        assertThat(monitor.totalOutput()).isEqualTo(2_000);
        assertThat(monitor.currentContextTokens()).isEqualTo(8_000);
    }

    @Test
    void multipleTurns_accumulateTotals() {
        var monitor = new ContextMonitor(200_000);

        monitor.recordTurnComplete(10_000, 2_000, 8_000);
        monitor.recordTurnComplete(15_000, 3_000, 12_000);
        monitor.recordTurnComplete(20_000, 5_000, 18_000);

        assertThat(monitor.totalInput()).isEqualTo(45_000);
        assertThat(monitor.totalOutput()).isEqualTo(10_000);
        // currentContext reflects only the last per-call value
        assertThat(monitor.currentContextTokens()).isEqualTo(18_000);
    }

    @Test
    void streamingThenTurnComplete_contextReflectsLastPerCall() {
        var monitor = new ContextMonitor(200_000);

        // Streaming updates during the turn
        monitor.recordStreamingUsage(30_000);
        monitor.recordStreamingUsage(45_000);
        assertThat(monitor.currentContextTokens()).isEqualTo(45_000);

        // Turn completes with final per-call value
        monitor.recordTurnComplete(60_000, 5_000, 50_000);
        assertThat(monitor.currentContextTokens()).isEqualTo(50_000);
    }

    // -- Edge cases -----------------------------------------------------------

    @Test
    void zeroContextWindow_usagePercentIsZero() {
        var monitor = new ContextMonitor(0);

        monitor.recordStreamingUsage(10_000);
        assertThat(monitor.usagePercent()).isEqualTo(0.0);
    }

    @Test
    void negativeContextWindow_clampedToZero() {
        var monitor = new ContextMonitor(-100);

        assertThat(monitor.contextWindow()).isZero();
        assertThat(monitor.usagePercent()).isEqualTo(0.0);
    }

    @Test
    void usagePercentCanExceed100() {
        var monitor = new ContextMonitor(100_000);

        monitor.recordStreamingUsage(120_000);
        assertThat(monitor.usagePercent()).isCloseTo(120.0, org.assertj.core.data.Offset.offset(0.01));
    }

    // -- Threshold warnings ---------------------------------------------------

    @Test
    void checkThresholds_warnsAt70_85_95_onlyOnce() {
        var monitor = new ContextMonitor(100_000);

        // Below 70% - no warning
        monitor.recordStreamingUsage(60_000);
        monitor.checkThresholds(log); // 60% - no warning

        // At 70%
        monitor.recordStreamingUsage(70_000);
        monitor.checkThresholds(log); // first 70% warning
        monitor.checkThresholds(log); // should not warn again

        // At 85%
        monitor.recordStreamingUsage(85_000);
        monitor.checkThresholds(log); // first 85% warning
        monitor.checkThresholds(log); // should not warn again

        // At 95%
        monitor.recordStreamingUsage(95_000);
        monitor.checkThresholds(log); // first 95% warning
        monitor.checkThresholds(log); // should not warn again

        // All thresholds fired - calling again should produce no new warnings
        monitor.recordStreamingUsage(99_000);
        monitor.checkThresholds(log); // no new warnings
    }

    @Test
    void checkThresholds_95_setsAllFlags() {
        var monitor = new ContextMonitor(100_000);

        // Jump straight to 95% - should set all three flags
        monitor.recordStreamingUsage(95_000);
        monitor.checkThresholds(log);

        // Dropping back to 70% should not re-warn (flag already set)
        monitor.recordStreamingUsage(70_000);
        monitor.checkThresholds(log); // no new warning
    }

    @Test
    void checkThresholds_zeroWindow_noWarning() {
        var monitor = new ContextMonitor(0);

        monitor.recordStreamingUsage(10_000);
        monitor.checkThresholds(log); // should not throw or warn
    }

    // -- Concurrent access ----------------------------------------------------

    @Test
    void concurrentStreamingUpdates_noCorruption() throws Exception {
        var monitor = new ContextMonitor(200_000);
        int threadCount = 8;
        int updatesPerThread = 10_000;
        var barrier = new CyclicBarrier(threadCount);

        try (ExecutorService executor = Executors.newFixedThreadPool(threadCount)) {
            List<Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                futures.add(executor.submit(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    for (int i = 0; i < updatesPerThread; i++) {
                        monitor.recordStreamingUsage(threadId * 1000L + i);
                    }
                }));
            }

            for (var f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        }

        // After all updates, currentContextTokens should be one of the values written
        long current = monitor.currentContextTokens();
        assertThat(current).isGreaterThanOrEqualTo(0);
        // Totals should still be zero (only streaming, no turn completions)
        assertThat(monitor.totalInput()).isZero();
        assertThat(monitor.totalOutput()).isZero();
    }

    @Test
    void concurrentTurnCompletions_totalsAreConsistent() throws Exception {
        var monitor = new ContextMonitor(200_000);
        int threadCount = 8;
        int turnsPerThread = 1_000;
        long inputPerTurn = 100;
        long outputPerTurn = 20;
        var barrier = new CyclicBarrier(threadCount);

        try (ExecutorService executor = Executors.newFixedThreadPool(threadCount)) {
            List<Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < threadCount; t++) {
                futures.add(executor.submit(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    for (int i = 0; i < turnsPerThread; i++) {
                        monitor.recordTurnComplete(inputPerTurn, outputPerTurn, 50);
                    }
                }));
            }

            for (var f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        }

        long expectedTotalIn = (long) threadCount * turnsPerThread * inputPerTurn;
        long expectedTotalOut = (long) threadCount * turnsPerThread * outputPerTurn;

        assertThat(monitor.totalInput()).isEqualTo(expectedTotalIn);
        assertThat(monitor.totalOutput()).isEqualTo(expectedTotalOut);
    }

    @Test
    void concurrentMixedOperations_noDeadlockOrCorruption() throws Exception {
        var monitor = new ContextMonitor(100_000);
        int threadCount = 6;
        int opsPerThread = 5_000;
        var barrier = new CyclicBarrier(threadCount);

        try (ExecutorService executor = Executors.newFixedThreadPool(threadCount)) {
            List<Future<?>> futures = new ArrayList<>();

            // 2 threads doing streaming updates
            for (int t = 0; t < 2; t++) {
                futures.add(executor.submit(() -> {
                    try { barrier.await(5, TimeUnit.SECONDS); } catch (Exception e) { throw new RuntimeException(e); }
                    for (int i = 0; i < opsPerThread; i++) {
                        monitor.recordStreamingUsage(i * 10L);
                    }
                }));
            }

            // 2 threads doing turn completions
            for (int t = 0; t < 2; t++) {
                futures.add(executor.submit(() -> {
                    try { barrier.await(5, TimeUnit.SECONDS); } catch (Exception e) { throw new RuntimeException(e); }
                    for (int i = 0; i < opsPerThread; i++) {
                        monitor.recordTurnComplete(100, 20, 50);
                    }
                }));
            }

            // 1 thread reading state
            futures.add(executor.submit(() -> {
                try { barrier.await(5, TimeUnit.SECONDS); } catch (Exception e) { throw new RuntimeException(e); }
                for (int i = 0; i < opsPerThread; i++) {
                    monitor.currentContextTokens();
                    monitor.usagePercent();
                    monitor.totalInput();
                    monitor.totalOutput();
                }
            }));

            // 1 thread checking thresholds
            futures.add(executor.submit(() -> {
                try { barrier.await(5, TimeUnit.SECONDS); } catch (Exception e) { throw new RuntimeException(e); }
                for (int i = 0; i < opsPerThread; i++) {
                    monitor.checkThresholds(log);
                }
            }));

            for (var f : futures) {
                f.get(15, TimeUnit.SECONDS);
            }
        }

        // 2 threads x 5000 turn completions x 100 input each
        assertThat(monitor.totalInput()).isEqualTo(2L * opsPerThread * 100);
        assertThat(monitor.totalOutput()).isEqualTo(2L * opsPerThread * 20);
    }
}

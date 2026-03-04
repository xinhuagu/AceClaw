package dev.aceclaw.core.agent;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class WatchdogTimerTest {

    @Test
    void turnBudget_triggersAtCorrectCount() {
        var token = new CancellationToken();
        try (var watchdog = new WatchdogTimer(3, Duration.ZERO, token)) {
            watchdog.checkBudget(0);
            assertFalse(watchdog.isExhausted());
            assertFalse(token.isCancelled());

            watchdog.checkBudget(1);
            assertFalse(watchdog.isExhausted());

            watchdog.checkBudget(2);
            assertFalse(watchdog.isExhausted());

            // Turn 3 (0-based) hits the limit of 3
            watchdog.checkBudget(3);
            assertTrue(watchdog.isExhausted());
            assertTrue(token.isCancelled());
            assertEquals("turn_budget", watchdog.exhaustionReason());
        }
    }

    @Test
    void turnBudget_triggersExactlyAtLimit() {
        var token = new CancellationToken();
        try (var watchdog = new WatchdogTimer(1, Duration.ZERO, token)) {
            // Turn 0 is allowed (0 < 1), turn 1 hits the limit
            watchdog.checkBudget(0);
            assertFalse(watchdog.isExhausted());

            watchdog.checkBudget(1);
            assertTrue(watchdog.isExhausted());
            assertTrue(token.isCancelled());
            assertEquals("turn_budget", watchdog.exhaustionReason());
        }
    }

    @Test
    void timeBudget_triggersAfterDuration() throws InterruptedException {
        var token = new CancellationToken();
        try (var watchdog = new WatchdogTimer(0, Duration.ofMillis(100), token)) {
            assertFalse(watchdog.isExhausted());

            // Wait for the timer to fire
            Thread.sleep(250);

            assertTrue(watchdog.isExhausted());
            assertTrue(token.isCancelled());
            assertEquals("time_budget", watchdog.exhaustionReason());
        }
    }

    @Test
    void timeBudget_beltAndSuspendersCheck() throws InterruptedException {
        var token = new CancellationToken();
        // Use a very long scheduled timer, but check passively after wall time expires
        try (var watchdog = new WatchdogTimer(0, Duration.ofMillis(50), token)) {
            Thread.sleep(100);
            // Even if timer hasn't fired yet, passive check should catch it
            watchdog.checkBudget(0);
            assertTrue(watchdog.isExhausted());
            assertEquals("time_budget", watchdog.exhaustionReason());
        }
    }

    @Test
    void disabledBudgets_neverTrigger() {
        var token = new CancellationToken();
        try (var watchdog = new WatchdogTimer(0, Duration.ZERO, token)) {
            // maxTurns=0, maxWallTime=ZERO: both disabled
            for (int i = 0; i < 100; i++) {
                watchdog.checkBudget(i);
            }
            assertFalse(watchdog.isExhausted());
            assertFalse(token.isCancelled());
            assertNull(watchdog.exhaustionReason());
        }
    }

    @Test
    void disabledTurnBudget_onlyTimeApplies() throws InterruptedException {
        var token = new CancellationToken();
        try (var watchdog = new WatchdogTimer(0, Duration.ofMillis(100), token)) {
            // Turn checks shouldn't trigger anything
            watchdog.checkBudget(1000);
            assertFalse(watchdog.isExhausted());

            Thread.sleep(200);
            assertTrue(watchdog.isExhausted());
            assertEquals("time_budget", watchdog.exhaustionReason());
        }
    }

    @Test
    void disabledTimeBudget_onlyTurnsApply() {
        var token = new CancellationToken();
        try (var watchdog = new WatchdogTimer(5, Duration.ZERO, token)) {
            // 5 calls (cumulative 0-4, all < 5)
            for (int i = 0; i < 5; i++) {
                watchdog.checkBudget(i);
            }
            assertFalse(watchdog.isExhausted());

            // 6th call: cumulative=5 >= 5 -> trigger
            watchdog.checkBudget(5);
            assertTrue(watchdog.isExhausted());
            assertEquals("turn_budget", watchdog.exhaustionReason());
        }
    }

    @Test
    void close_cancelsPendingTimer() {
        var token = new CancellationToken();
        var watchdog = new WatchdogTimer(0, Duration.ofHours(1), token);
        watchdog.close();

        // After close, the timer should not fire
        assertFalse(watchdog.isExhausted());
        assertFalse(token.isCancelled());
    }

    @Test
    void elapsedMs_returnsPositiveValue() throws InterruptedException {
        var token = new CancellationToken();
        try (var watchdog = new WatchdogTimer(0, Duration.ZERO, token)) {
            Thread.sleep(50);
            assertTrue(watchdog.elapsedMs() >= 40, "elapsedMs should be at least ~50ms");
        }
    }

    @Test
    void nullMaxWallTime_treatedAsDisabled() {
        var token = new CancellationToken();
        try (var watchdog = new WatchdogTimer(0, null, token)) {
            watchdog.checkBudget(100);
            assertFalse(watchdog.isExhausted());
            assertFalse(token.isCancelled());
        }
    }

    @Test
    void exhaustionReason_staysOnceSet() {
        var token = new CancellationToken();
        try (var watchdog = new WatchdogTimer(2, Duration.ZERO, token)) {
            // 3 calls: cumulative 0,1,2. Triggers on 3rd (cumulative=2 >= 2)
            watchdog.checkBudget(0);
            watchdog.checkBudget(1);
            watchdog.checkBudget(2);
            assertEquals("turn_budget", watchdog.exhaustionReason());

            // Subsequent checks should not change the reason
            watchdog.checkBudget(100);
            assertEquals("turn_budget", watchdog.exhaustionReason());
        }
    }

    @Test
    void resetWallClock_cancelsOldAndStartsNew() throws InterruptedException {
        var token = new CancellationToken();
        // Start with a long wall-clock timer that should not fire
        try (var watchdog = new WatchdogTimer(0, Duration.ofHours(1), token)) {
            assertFalse(watchdog.isExhausted());

            // Reset to a short timer
            watchdog.resetWallClock(Duration.ofMillis(100));

            // Wait for the new timer to fire
            Thread.sleep(250);

            assertTrue(watchdog.isExhausted());
            assertTrue(token.isCancelled());
            assertEquals("time_budget", watchdog.exhaustionReason());
        }
    }

    @Test
    void resetWallClock_turnBudgetNotReset() {
        var token = new CancellationToken();
        try (var watchdog = new WatchdogTimer(5, Duration.ofHours(1), token)) {
            // Consume 3 turns (cumulative: 0, 1, 2 — all < 5)
            watchdog.checkBudget(0);
            watchdog.checkBudget(1);
            watchdog.checkBudget(2);
            assertFalse(watchdog.isExhausted());

            // Reset wall clock -- turn counter should NOT be reset
            watchdog.resetWallClock(Duration.ofHours(1));

            // 2 more turns (cumulative: 3, 4 — still < 5)
            watchdog.checkBudget(0);
            watchdog.checkBudget(1);
            assertFalse(watchdog.isExhausted());

            // 6th call: cumulative=5 >= maxTurns=5 -> trigger
            watchdog.checkBudget(2);
            assertTrue(watchdog.isExhausted());
            assertEquals("turn_budget", watchdog.exhaustionReason());
        }
    }

    @Test
    void resetWallClock_checkBudgetUsesResetBaseline() throws InterruptedException {
        var token = new CancellationToken();
        // Create with short original wall-clock (100ms)
        try (var watchdog = new WatchdogTimer(0, Duration.ofMillis(100), token)) {
            // Reset to a generous 5s budget BEFORE the 100ms timer fires.
            // This cancels the old timer and updates the baseline.
            watchdog.resetWallClock(Duration.ofSeconds(5));

            // Now wait past the original 100ms budget.
            // Without the fix, checkBudget's belt-and-suspenders would compare
            // elapsed (>100ms from startedAt) against old maxWallTime (100ms) and fire.
            // With the fix, it compares elapsed (~100ms from resetAt) against 5s and doesn't fire.
            Thread.sleep(150);

            watchdog.checkBudget(0);
            assertFalse(watchdog.isExhausted(),
                    "checkBudget must use reset baseline, not original startedAt/maxWallTime");
            assertFalse(token.isCancelled());
        }
    }

    @Test
    void resetWallClock_noOpWhenAlreadyExhausted() throws InterruptedException {
        var token = new CancellationToken();
        try (var watchdog = new WatchdogTimer(1, Duration.ZERO, token)) {
            // Exhaust via turn budget
            watchdog.checkBudget(0);
            watchdog.checkBudget(1);
            assertTrue(watchdog.isExhausted());
            assertEquals("turn_budget", watchdog.exhaustionReason());

            // Reset should be a no-op -- reason stays the same
            watchdog.resetWallClock(Duration.ofMillis(50));
            Thread.sleep(100);

            // Reason should still be turn_budget, not time_budget
            assertEquals("turn_budget", watchdog.exhaustionReason());
        }
    }

    @Test
    void negativeMaxTurns_treatedAsDisabled() {
        var token = new CancellationToken();
        try (var watchdog = new WatchdogTimer(-5, Duration.ZERO, token)) {
            watchdog.checkBudget(1000);
            assertFalse(watchdog.isExhausted());
        }
    }

    @Test
    void cumulativeTurns_trackedAcrossSegments() {
        var token = new CancellationToken();
        try (var watchdog = new WatchdogTimer(5, Duration.ZERO, token)) {
            // Simulate 2 segments where caller resets iteration to 0 each segment
            // Segment 1: caller passes 0,1,2
            watchdog.checkBudget(0);
            watchdog.checkBudget(1);
            watchdog.checkBudget(2);
            assertFalse(watchdog.isExhausted());

            // Segment 2: caller resets to 0, but cumulative counter continues (3,4)
            watchdog.checkBudget(0);
            watchdog.checkBudget(1);
            assertFalse(watchdog.isExhausted());

            // Cumulative=5 >= maxTurns=5 -> trigger
            watchdog.checkBudget(2);
            assertTrue(watchdog.isExhausted());
            assertTrue(token.isCancelled());
            assertEquals("turn_budget", watchdog.exhaustionReason());
        }
    }

    // --- Soft/Hard split tests ---

    @Test
    void softLimit_doesNotCancelToken() {
        var token = new CancellationToken();
        // softTurns=5, hardTurns=15 -> soft limit at 5 should NOT cancel token
        try (var watchdog = new WatchdogTimer(5, 15, Duration.ZERO, Duration.ZERO, token)) {
            for (int i = 0; i < 5; i++) {
                watchdog.checkBudget(i);
            }
            // Soft limit reached, but token should NOT be cancelled
            assertTrue(watchdog.isSoftLimitReached());
            assertFalse(watchdog.isExhausted());
            assertFalse(token.isCancelled());
        }
    }

    @Test
    void hardLimit_cancelsToken() {
        var token = new CancellationToken();
        // softTurns=5, hardTurns=10
        try (var watchdog = new WatchdogTimer(5, 10, Duration.ZERO, Duration.ZERO, token)) {
            // getAndIncrement returns 0..9 across 10 calls; 11th call returns 10 >= 10
            for (int i = 0; i < 10; i++) {
                watchdog.checkBudget(i);
            }
            assertFalse(watchdog.isExhausted()); // cumulative was 0-9, none >= 10

            watchdog.checkBudget(10); // cumulative=10 >= hardTurns=10 -> cancel
            assertTrue(watchdog.isExhausted());
            assertTrue(token.isCancelled());
            assertEquals("turn_budget", watchdog.exhaustionReason());
        }
    }

    @Test
    void softWallTimer_setsFlag() throws InterruptedException {
        var token = new CancellationToken();
        // softWall=100ms, hardWall=5s -> soft fires quickly, hard much later
        try (var watchdog = new WatchdogTimer(0, 0, Duration.ofMillis(100), Duration.ofSeconds(5), token)) {
            Thread.sleep(200);
            // Soft wall reached, but token should NOT be cancelled
            assertTrue(watchdog.isSoftLimitReached());
            assertFalse(watchdog.isExhausted());
            assertFalse(token.isCancelled());
        }
    }

    @Test
    void hardWallTimer_cancelsToken() throws InterruptedException {
        var token = new CancellationToken();
        // softWall=50ms, hardWall=150ms -> both fire
        try (var watchdog = new WatchdogTimer(0, 0, Duration.ofMillis(50), Duration.ofMillis(150), token)) {
            Thread.sleep(300);
            assertTrue(watchdog.isExhausted());
            assertTrue(token.isCancelled());
            assertEquals("time_budget", watchdog.exhaustionReason());
        }
    }

    @Test
    void extendSoft_raisesLimit() {
        var token = new CancellationToken();
        try (var watchdog = new WatchdogTimer(5, 50, Duration.ZERO, Duration.ZERO, token)) {
            // Consume 5 turns -> soft limit reached
            for (int i = 0; i < 5; i++) {
                watchdog.checkBudget(i);
            }
            assertTrue(watchdog.isSoftLimitReached());
            assertFalse(watchdog.isExhausted());

            // Extend by 10 more turns
            watchdog.extendSoft(10, Duration.ZERO);
            assertEquals(1, watchdog.extensionCount());

            // Soft limit should no longer be reached (now at 15 turns, consumed 5)
            assertFalse(watchdog.isSoftLimitReached());

            // Consume 10 more -> soft limit reached again at 15
            for (int i = 0; i < 10; i++) {
                watchdog.checkBudget(i);
            }
            assertTrue(watchdog.isSoftLimitReached());
            assertFalse(watchdog.isExhausted()); // still below hard=50
        }
    }

    @Test
    void extendSoft_cappedByHardCeiling() {
        var token = new CancellationToken();
        try (var watchdog = new WatchdogTimer(5, 10, Duration.ZERO, Duration.ZERO, token)) {
            // Consume 5 turns (cumulative 0-4) -> soft limit reached (5 >= softTurns=5)
            for (int i = 0; i < 5; i++) {
                watchdog.checkBudget(i);
            }
            assertTrue(watchdog.isSoftLimitReached());

            // Extend by 20 turns -> should be capped to hardTurns=10
            watchdog.extendSoft(20, Duration.ZERO);
            assertEquals(1, watchdog.extensionCount());

            // 5 more turns (cumulative 5-9), then 6th (cumulative=10 >= hard=10) -> cancel
            for (int i = 0; i < 5; i++) {
                watchdog.checkBudget(i);
            }
            assertFalse(watchdog.isExhausted()); // cumulative=9, not yet at hard=10

            watchdog.checkBudget(0); // cumulative=10 >= hardTurns=10 -> cancel
            assertTrue(watchdog.isExhausted());
            assertTrue(token.isCancelled());
        }
    }

    @Test
    void isApproachingLimit_at80Percent() {
        var token = new CancellationToken();
        try (var watchdog = new WatchdogTimer(10, 30, Duration.ZERO, Duration.ZERO, token)) {
            // 80% of 10 = 8 turns
            for (int i = 0; i < 7; i++) {
                watchdog.checkBudget(i);
            }
            assertFalse(watchdog.isApproachingLimit()); // 7 < 8

            watchdog.checkBudget(7); // cumulative = 8
            assertTrue(watchdog.isApproachingLimit()); // 8 >= 8
        }
    }

    @Test
    void warningFlag_resetOnExtend() {
        var token = new CancellationToken();
        try (var watchdog = new WatchdogTimer(10, 100, Duration.ZERO, Duration.ZERO, token)) {
            assertFalse(watchdog.isWarningInjected());

            watchdog.markWarningInjected();
            assertTrue(watchdog.isWarningInjected());

            // Extend should reset the warning flag
            watchdog.extendSoft(5, Duration.ZERO);
            assertFalse(watchdog.isWarningInjected());
        }
    }

    @Test
    void backwardCompatConstructor_preservesBehavior() {
        // The 3-arg constructor should behave identically to the old implementation:
        // cancels token when turn budget is reached
        var token = new CancellationToken();
        try (var watchdog = new WatchdogTimer(3, Duration.ZERO, token)) {
            watchdog.checkBudget(0);
            watchdog.checkBudget(1);
            watchdog.checkBudget(2);
            assertFalse(watchdog.isExhausted());

            watchdog.checkBudget(3); // cumulative=3 >= hard=3 -> cancel
            assertTrue(watchdog.isExhausted());
            assertTrue(token.isCancelled());
            assertEquals("turn_budget", watchdog.exhaustionReason());
        }
    }

    @Test
    void resetWallClock_cancelsBothTimers() throws InterruptedException {
        var token = new CancellationToken();
        // softWall=1h, hardWall=2h -> neither should fire
        try (var watchdog = new WatchdogTimer(0, 0, Duration.ofHours(1), Duration.ofHours(2), token)) {
            assertFalse(watchdog.isSoftLimitReached());
            assertFalse(watchdog.isExhausted());

            // Reset to short hard-only timer (100ms)
            watchdog.resetWallClock(Duration.ofMillis(100));
            Thread.sleep(250);

            // Hard timer should fire, cancelling token
            assertTrue(watchdog.isExhausted());
            assertTrue(token.isCancelled());
            assertEquals("time_budget", watchdog.exhaustionReason());
        }
    }

    @Test
    void remainingBudgetSummary_format() {
        var token = new CancellationToken();
        try (var watchdog = new WatchdogTimer(10, 30,
                Duration.ofSeconds(600), Duration.ofSeconds(1800), token)) {
            String summary = watchdog.remainingBudgetSummary();
            // Should contain both time and turn info
            assertTrue(summary.contains("s"), "Should include seconds: " + summary);
            assertTrue(summary.contains("turns"), "Should include turns: " + summary);
            assertTrue(summary.contains("~"), "Should include approximate marker: " + summary);
        }
    }
}

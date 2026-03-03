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
}

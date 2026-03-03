package dev.aceclaw.core.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dual-mode budget enforcer for agent loop execution.
 *
 * <p>Enforces both a turn count budget (passive check) and a wall-clock time
 * budget (active scheduled timer). When either budget is exhausted, the
 * associated {@link CancellationToken} is cancelled, causing the agent loop
 * to exit cleanly at its next checkpoint.
 *
 * <p>Both budgets can be independently disabled: set {@code maxTurns} to 0
 * and {@code maxWallTime} to {@link Duration#ZERO} to disable each respectively.
 */
public final class WatchdogTimer implements AutoCloseable {

    /**
     * Shared daemon-thread scheduler for wall-clock timers across all instances.
     * Uses {@link ScheduledThreadPoolExecutor} with {@code removeOnCancelPolicy=true}
     * so that cancelled timer tasks are immediately removed from the work queue
     * instead of accumulating until their scheduled time.
     */
    private static final ScheduledExecutorService SCHEDULER;
    static {
        var pool = new ScheduledThreadPoolExecutor(1, r -> {
            var t = new Thread(r, "watchdog-timer");
            t.setDaemon(true);
            return t;
        });
        pool.setRemoveOnCancelPolicy(true);
        SCHEDULER = pool;
    }

    private final int maxTurns;
    private final CancellationToken token;
    private final Instant startedAt;
    private final AtomicReference<String> exhaustionReason = new AtomicReference<>(null);
    private final AtomicInteger cumulativeTurns = new AtomicInteger(0);
    private volatile ScheduledFuture<?> timerFuture;

    /** Effective wall-clock budget and reset point, updated by {@link #resetWallClock}. */
    private volatile Duration effectiveWallTime;
    private volatile Instant wallTimeResetAt;

    /**
     * Creates a new watchdog timer.
     *
     * @param maxTurns    maximum number of ReAct iterations allowed (0 = disabled)
     * @param maxWallTime maximum wall-clock duration allowed ({@code null} or {@link Duration#ZERO} = disabled)
     * @param token       the cancellation token to signal when a budget is exhausted
     */
    public WatchdogTimer(int maxTurns, Duration maxWallTime, CancellationToken token) {
        this.maxTurns = Math.max(0, maxTurns);
        this.token = Objects.requireNonNull(token, "token");
        this.startedAt = Instant.now();

        Duration initial = maxWallTime != null ? maxWallTime : Duration.ZERO;
        this.effectiveWallTime = initial;
        this.wallTimeResetAt = this.startedAt;

        if (!initial.isZero() && !initial.isNegative()) {
            this.timerFuture = SCHEDULER.schedule(() -> {
                if (this.exhaustionReason.compareAndSet(null, "time_budget")) {
                    token.cancel();
                }
            }, initial.toMillis(), TimeUnit.MILLISECONDS);
        } else {
            this.timerFuture = null;
        }
    }

    /**
     * Resets the wall-clock timer with a new duration.
     *
     * <p>Cancels the current scheduled timer (if any) and schedules a fresh one
     * with the given duration. This allows per-step wall-clock budgets during
     * multi-step plan execution.
     *
     * <p>No-op if the watchdog is already exhausted (turn or time budget spent).
     * The cumulative turn budget is intentionally NOT reset — only the wall-clock
     * timer is affected.
     *
     * @param newWallTime the new wall-clock duration for the timer
     */
    public void resetWallClock(Duration newWallTime) {
        if (exhaustionReason.get() != null) {
            return; // already exhausted, no point restarting
        }

        // Cancel existing timer
        var existing = this.timerFuture;
        if (existing != null) {
            existing.cancel(false);
        }

        // Re-check: the old timer may have fired between our initial guard and cancel
        if (exhaustionReason.get() != null) {
            this.timerFuture = null;
            return;
        }

        Duration effective = newWallTime != null ? newWallTime : Duration.ZERO;
        // Update the effective wall-clock baseline so checkBudget uses the new values
        this.effectiveWallTime = effective;
        this.wallTimeResetAt = Instant.now();

        if (!effective.isZero() && !effective.isNegative()) {
            this.timerFuture = SCHEDULER.schedule(() -> {
                if (this.exhaustionReason.compareAndSet(null, "time_budget")) {
                    token.cancel();
                }
            }, effective.toMillis(), TimeUnit.MILLISECONDS);
        } else {
            this.timerFuture = null;
        }
    }

    /**
     * Passive budget check. Call this before each LLM call in the agent loop.
     *
     * <p>Tracks cumulative turns internally so that the turn budget is enforced
     * globally across adaptive continuation segments, not per-segment.
     *
     * <p>Checks both turn count and wall-clock time (belt-and-suspenders).
     * If either budget is exhausted, sets the exhaustion reason and cancels the token.
     *
     * @param currentTurn the zero-based iteration index (used as a hint; cumulative tracking is internal)
     */
    public void checkBudget(int currentTurn) {
        if (exhaustionReason.get() != null) {
            return; // already exhausted
        }

        int cumulative = cumulativeTurns.getAndIncrement();

        // Check turn budget using cumulative count
        if (maxTurns > 0 && cumulative >= maxTurns) {
            if (exhaustionReason.compareAndSet(null, "turn_budget")) {
                token.cancel();
            }
            return;
        }

        // Belt-and-suspenders: check wall-clock even if timer hasn't fired.
        // Uses effectiveWallTime/wallTimeResetAt so this respects resetWallClock() calls.
        Duration wallTime = this.effectiveWallTime;
        if (wallTime != null && !wallTime.isZero() && !wallTime.isNegative()) {
            long elapsedMs = Duration.between(this.wallTimeResetAt, Instant.now()).toMillis();
            if (elapsedMs >= wallTime.toMillis()) {
                if (exhaustionReason.compareAndSet(null, "time_budget")) {
                    token.cancel();
                }
            }
        }
    }

    /**
     * Returns whether any budget has been exhausted.
     */
    public boolean isExhausted() {
        return exhaustionReason.get() != null;
    }

    /**
     * Returns the reason for budget exhaustion.
     *
     * @return {@code "turn_budget"}, {@code "time_budget"}, or {@code null} if not exhausted
     */
    public String exhaustionReason() {
        return exhaustionReason.get();
    }

    /**
     * Returns the elapsed time in milliseconds since this timer was created.
     */
    public long elapsedMs() {
        return Duration.between(startedAt, Instant.now()).toMillis();
    }

    /**
     * Cancels the scheduled wall-clock timer if one was started.
     */
    @Override
    public void close() {
        if (timerFuture != null) {
            timerFuture.cancel(false);
        }
    }
}

package dev.aceclaw.core.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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

    /** Shared daemon-thread scheduler for wall-clock timers across all instances. */
    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                var t = new Thread(r, "watchdog-timer");
                t.setDaemon(true);
                return t;
            });

    private final int maxTurns;
    private final Duration maxWallTime;
    private final CancellationToken token;
    private final Instant startedAt;
    private final AtomicReference<String> exhaustionReason = new AtomicReference<>(null);
    private final ScheduledFuture<?> timerFuture;

    /**
     * Creates a new watchdog timer.
     *
     * @param maxTurns    maximum number of ReAct iterations allowed (0 = disabled)
     * @param maxWallTime maximum wall-clock duration allowed ({@code null} or {@link Duration#ZERO} = disabled)
     * @param token       the cancellation token to signal when a budget is exhausted
     */
    public WatchdogTimer(int maxTurns, Duration maxWallTime, CancellationToken token) {
        this.maxTurns = Math.max(0, maxTurns);
        this.maxWallTime = maxWallTime != null ? maxWallTime : Duration.ZERO;
        this.token = Objects.requireNonNull(token, "token");
        this.startedAt = Instant.now();

        if (!this.maxWallTime.isZero() && !this.maxWallTime.isNegative()) {
            this.timerFuture = SCHEDULER.schedule(() -> {
                if (this.exhaustionReason.compareAndSet(null, "time_budget")) {
                    token.cancel();
                }
            }, this.maxWallTime.toMillis(), TimeUnit.MILLISECONDS);
        } else {
            this.timerFuture = null;
        }
    }

    /**
     * Passive budget check. Call this before each LLM call in the agent loop.
     *
     * <p>Checks both turn count and wall-clock time (belt-and-suspenders).
     * If either budget is exhausted, sets the exhaustion reason and cancels the token.
     *
     * @param currentTurn the zero-based iteration index within the current turn
     */
    public void checkBudget(int currentTurn) {
        if (exhaustionReason.get() != null) {
            return; // already exhausted
        }

        // Check turn budget
        if (maxTurns > 0 && currentTurn >= maxTurns) {
            if (exhaustionReason.compareAndSet(null, "turn_budget")) {
                token.cancel();
            }
            return;
        }

        // Belt-and-suspenders: check wall-clock even if timer hasn't fired
        if (!maxWallTime.isZero() && !maxWallTime.isNegative()) {
            long elapsedMs = Duration.between(startedAt, Instant.now()).toMillis();
            if (elapsedMs >= maxWallTime.toMillis()) {
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

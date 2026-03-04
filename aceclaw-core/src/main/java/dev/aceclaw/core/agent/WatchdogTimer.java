package dev.aceclaw.core.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dual-mode budget enforcer for agent loop execution with soft/hard limit split.
 *
 * <p>Enforces both a turn count budget and a wall-clock time budget. Each has a
 * <em>soft limit</em> (warning + progress check) and a <em>hard ceiling</em>
 * (unconditional stop).
 *
 * <p><b>Soft limit</b>: when reached, the caller checks progress and either
 * extends via {@link #extendSoft} or stops. Does NOT cancel the token.
 *
 * <p><b>Hard ceiling</b>: unconditional stop. Cancels the {@link CancellationToken}
 * regardless of progress. This is the absolute safety net.
 *
 * <p>At 80% of the soft limit, {@link #isApproachingLimit()} returns true so the
 * caller can inject a budget warning into the conversation.
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

    // Hard ceiling (immutable)
    private final int hardTurns;
    private final Duration hardWallTime;

    // Soft limit (mutable via extension)
    private volatile int currentSoftTurns;
    private volatile Duration currentSoftWallTime;

    private final CancellationToken token;
    private final Instant startedAt;
    private final AtomicReference<String> exhaustionReason = new AtomicReference<>(null);
    private final AtomicInteger cumulativeTurns = new AtomicInteger(0);

    // Soft wall flag: set when the soft wall timer fires or passive check detects elapsed time
    private final AtomicBoolean softWallReached = new AtomicBoolean(false);

    // Warning tracking
    private final AtomicBoolean warningInjectedFlag = new AtomicBoolean(false);

    // Extension tracking
    private final AtomicInteger extensionCount = new AtomicInteger(0);

    // Two wall timers
    private volatile ScheduledFuture<?> softTimerFuture;
    private volatile ScheduledFuture<?> hardTimerFuture;

    // For hard wall passive check (belt-and-suspenders)
    private volatile Duration effectiveHardWallTime;
    private volatile Instant hardWallResetAt;

    // For soft wall passive check
    private volatile Instant softWallResetAt;

    /**
     * Backward-compatible constructor. Sets soft and hard limits to the same values,
     * preserving the original behavior where {@link #checkBudget} cancels the token
     * when the turn/time budget is reached.
     *
     * @param maxTurns    maximum number of ReAct iterations allowed (0 = disabled)
     * @param maxWallTime maximum wall-clock duration allowed ({@code null} or {@link Duration#ZERO} = disabled)
     * @param token       the cancellation token to signal when a budget is exhausted
     */
    public WatchdogTimer(int maxTurns, Duration maxWallTime, CancellationToken token) {
        this(maxTurns, maxTurns, maxWallTime, maxWallTime, token);
    }

    /**
     * Full constructor with separate soft and hard limits.
     *
     * @param softTurns    soft turn limit (triggers progress check, 0 = disabled)
     * @param hardTurns    hard turn ceiling (unconditional stop, 0 = disabled)
     * @param softWallTime soft wall-clock limit (triggers progress check)
     * @param hardWallTime hard wall-clock ceiling (unconditional stop)
     * @param token        the cancellation token to signal on hard limit exhaustion
     */
    public WatchdogTimer(int softTurns, int hardTurns,
                         Duration softWallTime, Duration hardWallTime,
                         CancellationToken token) {
        this.currentSoftTurns = Math.max(0, softTurns);
        this.hardTurns = Math.max(0, hardTurns);
        this.token = Objects.requireNonNull(token, "token");
        this.startedAt = Instant.now();

        Duration softWall = softWallTime != null ? softWallTime : Duration.ZERO;
        Duration hardWall = hardWallTime != null ? hardWallTime : Duration.ZERO;
        this.currentSoftWallTime = softWall;
        this.hardWallTime = hardWall;

        this.effectiveHardWallTime = hardWall;
        this.hardWallResetAt = this.startedAt;
        this.softWallResetAt = this.startedAt;

        // Schedule soft wall timer (sets flag only, no cancellation)
        if (!softWall.isZero() && !softWall.isNegative()) {
            this.softTimerFuture = SCHEDULER.schedule(
                    () -> softWallReached.set(true),
                    softWall.toMillis(), TimeUnit.MILLISECONDS);
        }

        // Schedule hard wall timer (cancels token)
        if (!hardWall.isZero() && !hardWall.isNegative()) {
            this.hardTimerFuture = SCHEDULER.schedule(() -> {
                if (this.exhaustionReason.compareAndSet(null, "time_budget")) {
                    token.cancel();
                }
            }, hardWall.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Resets the wall-clock timer with a new duration.
     *
     * <p>Cancels BOTH soft and hard wall timers and schedules a new hard-only timer.
     * Used for per-step wall-clock budgets during multi-step plan execution where
     * auto-extension is not appropriate within a single step.
     *
     * <p>No-op if the watchdog is already exhausted. The cumulative turn budget
     * is intentionally NOT reset.
     *
     * @param newWallTime the new wall-clock duration for the hard timer
     */
    public void resetWallClock(Duration newWallTime) {
        if (exhaustionReason.get() != null) {
            return; // already exhausted, no point restarting
        }

        // Cancel soft timer
        var existingSoft = this.softTimerFuture;
        if (existingSoft != null) {
            existingSoft.cancel(false);
            this.softTimerFuture = null;
        }

        // Cancel hard timer
        var existingHard = this.hardTimerFuture;
        if (existingHard != null) {
            existingHard.cancel(false);
        }

        // Re-check: the old timer may have fired between our initial guard and cancel
        if (exhaustionReason.get() != null) {
            this.hardTimerFuture = null;
            return;
        }

        Duration effective = newWallTime != null ? newWallTime : Duration.ZERO;
        Instant now = Instant.now();

        // Update hard wall baseline for passive check
        this.effectiveHardWallTime = effective;
        this.hardWallResetAt = now;

        // Reset soft wall state (plan steps use hard limits, no auto-extension)
        this.softWallReached.set(false);
        this.softWallResetAt = now;
        this.currentSoftWallTime = effective;

        if (!effective.isZero() && !effective.isNegative()) {
            this.hardTimerFuture = SCHEDULER.schedule(() -> {
                if (this.exhaustionReason.compareAndSet(null, "time_budget")) {
                    token.cancel();
                }
            }, effective.toMillis(), TimeUnit.MILLISECONDS);
        } else {
            this.hardTimerFuture = null;
        }
    }

    /**
     * Passive budget check. Call this before each LLM call in the agent loop.
     *
     * <p>Tracks cumulative turns internally so that the turn budget is enforced
     * globally across adaptive continuation segments, not per-segment.
     *
     * <p>The hard turn ceiling and hard wall time cancel the token (unconditional stop).
     * The soft turn/wall limits only set flags checked by {@link #isSoftLimitReached()}.
     *
     * @param currentTurn the zero-based iteration index (used as a hint; cumulative tracking is internal)
     */
    public void checkBudget(int currentTurn) {
        if (exhaustionReason.get() != null) {
            return; // already exhausted
        }

        int cumulative = cumulativeTurns.getAndIncrement();

        // Hard turn ceiling: unconditional cancel
        if (hardTurns > 0 && cumulative >= hardTurns) {
            if (exhaustionReason.compareAndSet(null, "turn_budget")) {
                token.cancel();
            }
            return;
        }

        // Belt-and-suspenders: hard wall-clock passive check
        Duration hardWall = this.effectiveHardWallTime;
        if (hardWall != null && !hardWall.isZero() && !hardWall.isNegative()) {
            long elapsedMs = Duration.between(this.hardWallResetAt, Instant.now()).toMillis();
            if (elapsedMs >= hardWall.toMillis()) {
                if (exhaustionReason.compareAndSet(null, "time_budget")) {
                    token.cancel();
                }
                return;
            }
        }

        // Soft wall passive check: set flag if soft wall time elapsed
        Duration softWall = this.currentSoftWallTime;
        if (softWall != null && !softWall.isZero() && !softWall.isNegative()) {
            long softElapsedMs = Duration.between(this.softWallResetAt, Instant.now()).toMillis();
            if (softElapsedMs >= softWall.toMillis()) {
                softWallReached.set(true);
            }
        }
    }

    /**
     * Returns true when the soft limit has been reached (turns OR wall time).
     * Does NOT cancel the token. The caller should check progress and either
     * extend via {@link #extendSoft} or cancel manually.
     */
    public boolean isSoftLimitReached() {
        if (currentSoftTurns > 0 && cumulativeTurns.get() >= currentSoftTurns) {
            return true;
        }
        return softWallReached.get();
    }

    /**
     * Returns true when at or past 80% of the current soft turn or wall-time limit.
     * Used to inject a budget warning into the conversation.
     */
    public boolean isApproachingLimit() {
        if (currentSoftTurns > 0) {
            int threshold = (int) Math.ceil(currentSoftTurns * 0.8);
            if (cumulativeTurns.get() >= threshold) {
                return true;
            }
        }
        Duration softWall = this.currentSoftWallTime;
        if (softWall != null && !softWall.isZero() && !softWall.isNegative()) {
            long elapsedMs = Duration.between(this.softWallResetAt, Instant.now()).toMillis();
            if (elapsedMs >= (long) (softWall.toMillis() * 0.8)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extends the soft limit by the given amount, capped by the hard ceiling.
     * Reschedules the soft wall timer and resets the warning flag.
     * No-op if the watchdog is already exhausted.
     *
     * @param extraTurns additional turns to grant
     * @param extraTime  additional wall-clock time to grant
     */
    public void extendSoft(int extraTurns, Duration extraTime) {
        if (exhaustionReason.get() != null) {
            return;
        }

        // Extend turn limit, capped by hard ceiling
        if (extraTurns > 0 && currentSoftTurns > 0) {
            int newSoft = currentSoftTurns + extraTurns;
            if (hardTurns > 0) {
                newSoft = Math.min(newSoft, hardTurns);
            }
            this.currentSoftTurns = newSoft;
        }

        // Extend wall time
        if (extraTime != null && !extraTime.isZero() && !extraTime.isNegative()) {
            // Cancel existing soft timer
            var existing = this.softTimerFuture;
            if (existing != null) {
                existing.cancel(false);
            }

            // Reset soft wall flag
            softWallReached.set(false);
            this.softWallResetAt = Instant.now();

            // Compute new soft wall, capped by remaining hard wall time
            Duration newSoftWall = extraTime;
            if (hardWallTime != null && !hardWallTime.isZero()) {
                long hardRemainingMs = hardWallTime.toMillis()
                        - Duration.between(startedAt, Instant.now()).toMillis();
                if (hardRemainingMs > 0) {
                    newSoftWall = Duration.ofMillis(Math.min(newSoftWall.toMillis(), hardRemainingMs));
                } else {
                    newSoftWall = Duration.ZERO;
                }
            }
            this.currentSoftWallTime = newSoftWall;

            if (!newSoftWall.isZero() && !newSoftWall.isNegative()) {
                this.softTimerFuture = SCHEDULER.schedule(
                        () -> softWallReached.set(true),
                        newSoftWall.toMillis(), TimeUnit.MILLISECONDS);
            }
        }

        // Reset warning flag so it can be re-injected for the new limit
        warningInjectedFlag.set(false);
        extensionCount.incrementAndGet();
    }

    /**
     * Returns a human-readable summary of remaining budget.
     *
     * @return e.g. {@code "~120s / ~10 turns"} or {@code "unlimited"}
     */
    public String remainingBudgetSummary() {
        var sb = new StringBuilder();

        // Remaining wall time (relative to soft limit)
        Duration softWall = this.currentSoftWallTime;
        if (softWall != null && !softWall.isZero()) {
            long elapsedMs = Duration.between(this.softWallResetAt, Instant.now()).toMillis();
            long remainingMs = Math.max(0, softWall.toMillis() - elapsedMs);
            sb.append("~").append(remainingMs / 1000).append("s");
        }

        // Remaining turns (relative to soft limit)
        if (currentSoftTurns > 0) {
            int remaining = Math.max(0, currentSoftTurns - cumulativeTurns.get());
            if (!sb.isEmpty()) {
                sb.append(" / ");
            }
            sb.append("~").append(remaining).append(" turns");
        }

        return sb.isEmpty() ? "unlimited" : sb.toString();
    }

    /**
     * Returns whether the budget warning has been injected for the current soft limit.
     */
    public boolean isWarningInjected() {
        return warningInjectedFlag.get();
    }

    /**
     * Marks the budget warning as injected.
     */
    public void markWarningInjected() {
        warningInjectedFlag.set(true);
    }

    /**
     * Returns the number of soft limit extensions performed.
     */
    public int extensionCount() {
        return extensionCount.get();
    }

    /**
     * Returns whether any budget has been exhausted (hard limit reached).
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
     * Cancels all scheduled wall-clock timers.
     */
    @Override
    public void close() {
        if (softTimerFuture != null) {
            softTimerFuture.cancel(false);
        }
        if (hardTimerFuture != null) {
            hardTimerFuture.cancel(false);
        }
    }
}

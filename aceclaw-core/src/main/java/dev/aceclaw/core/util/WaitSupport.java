package dev.aceclaw.core.util;

import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.concurrent.locks.LockSupport;

/**
 * Interrupt-aware waiting helpers for polling and retry backoff loops.
 */
public final class WaitSupport {
    private static final long MIN_PARK_NANOS = 1_000_000L; // 1ms

    private WaitSupport() {}

    /**
     * Waits for the given duration, preserving interrupt semantics.
     */
    public static void sleepInterruptibly(Duration duration) throws InterruptedException {
        Objects.requireNonNull(duration, "duration");
        long remaining = Math.max(0L, duration.toNanos());
        while (remaining > 0) {
            long start = System.nanoTime();
            LockSupport.parkNanos(Math.max(MIN_PARK_NANOS, remaining));
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Interrupted while waiting");
            }
            long elapsed = System.nanoTime() - start;
            remaining = Math.max(0L, remaining - Math.max(elapsed, MIN_PARK_NANOS));
        }
    }

    /**
     * Waits until {@code condition.getAsBoolean()} becomes true.
     */
    public static void awaitCondition(BooleanSupplier condition, Duration pollInterval) throws InterruptedException {
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(pollInterval, "pollInterval");
        Duration interval = normalizeInterval(pollInterval);
        while (!condition.getAsBoolean()) {
            sleepInterruptibly(interval);
        }
    }

    /**
     * Waits until condition becomes true or timeout expires.
     *
     * @return true if condition became true before timeout, false otherwise.
     */
    public static boolean awaitCondition(BooleanSupplier condition, Duration timeout, Duration pollInterval)
            throws InterruptedException {
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(pollInterval, "pollInterval");
        if (timeout.isZero() || timeout.isNegative()) {
            return condition.getAsBoolean();
        }
        Duration interval = normalizeInterval(pollInterval);
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                break;
            }
            sleepInterruptibly(Duration.ofNanos(Math.min(interval.toNanos(), remainingNanos)));
        }
        return condition.getAsBoolean();
    }

    private static Duration normalizeInterval(Duration pollInterval) {
        if (pollInterval.isZero() || pollInterval.isNegative()) {
            return Duration.ofMillis(1);
        }
        return pollInterval;
    }
}

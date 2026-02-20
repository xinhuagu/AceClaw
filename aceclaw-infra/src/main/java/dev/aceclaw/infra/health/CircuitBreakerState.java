package dev.aceclaw.infra.health;

import java.time.Instant;

/**
 * Sealed state hierarchy for {@link CircuitBreaker}.
 *
 * <p>Use exhaustive pattern matching:
 * <pre>{@code
 * switch (state) {
 *     case Closed c   -> "closed, failures=" + c.consecutiveFailures();
 *     case Open o     -> "open since " + o.openedAt();
 *     case HalfOpen h -> "half-open, probes=" + h.successfulProbes();
 * }
 * }</pre>
 */
public sealed interface CircuitBreakerState {

    /** Circuit is closed (normal operation). Tracks consecutive failures. */
    record Closed(int consecutiveFailures) implements CircuitBreakerState {
        public Closed {
            if (consecutiveFailures < 0) {
                throw new IllegalArgumentException("consecutiveFailures must be >= 0");
            }
        }
    }

    /** Circuit is open (rejecting all calls). Records when it opened. */
    record Open(Instant openedAt) implements CircuitBreakerState {
        public Open {
            if (openedAt == null) {
                throw new IllegalArgumentException("openedAt must not be null");
            }
        }
    }

    /** Circuit is half-open (allowing limited probes). Tracks successful probes. */
    record HalfOpen(int successfulProbes) implements CircuitBreakerState {
        public HalfOpen {
            if (successfulProbes < 0) {
                throw new IllegalArgumentException("successfulProbes must be >= 0");
            }
        }
    }
}

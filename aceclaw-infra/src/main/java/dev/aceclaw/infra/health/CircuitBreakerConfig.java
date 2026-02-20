package dev.aceclaw.infra.health;

import java.time.Duration;

/**
 * Configuration for a {@link CircuitBreaker}.
 *
 * @param name              human-readable name for logging and health checks
 * @param failureThreshold  consecutive failures before opening the circuit
 * @param resetTimeout      how long to stay open before transitioning to half-open
 * @param halfOpenMaxProbes max successful probes in half-open before closing
 */
public record CircuitBreakerConfig(
        String name,
        int failureThreshold,
        Duration resetTimeout,
        int halfOpenMaxProbes
) {

    public CircuitBreakerConfig {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (failureThreshold <= 0) {
            throw new IllegalArgumentException("failureThreshold must be > 0, got: " + failureThreshold);
        }
        if (resetTimeout == null || resetTimeout.isNegative() || resetTimeout.isZero()) {
            throw new IllegalArgumentException("resetTimeout must be positive");
        }
        if (halfOpenMaxProbes <= 0) {
            throw new IllegalArgumentException("halfOpenMaxProbes must be > 0, got: " + halfOpenMaxProbes);
        }
    }

    /**
     * Sensible defaults for wrapping LLM calls:
     * 5 consecutive failures to open, 30s reset timeout, 2 probes to close.
     */
    public static CircuitBreakerConfig defaultForLlm() {
        return new CircuitBreakerConfig("llm", 5, Duration.ofSeconds(30), 2);
    }
}

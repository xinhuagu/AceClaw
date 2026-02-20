package dev.aceclaw.infra.health;

/**
 * Adapter that bridges a {@link CircuitBreaker}'s state to the {@link HealthCheck} interface.
 *
 * <p>State mapping:
 * <ul>
 *   <li>{@link CircuitBreakerState.Closed} → HEALTHY</li>
 *   <li>{@link CircuitBreakerState.HalfOpen} → DEGRADED</li>
 *   <li>{@link CircuitBreakerState.Open} → UNHEALTHY</li>
 * </ul>
 */
public final class CircuitBreakerHealthCheck implements HealthCheck {

    private final CircuitBreaker circuitBreaker;

    public CircuitBreakerHealthCheck(CircuitBreaker circuitBreaker) {
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public String name() {
        return "circuit-breaker:" + circuitBreaker.config().name();
    }

    @Override
    public HealthCheckResult check() {
        var state = circuitBreaker.currentState();
        return switch (state) {
            case CircuitBreakerState.Closed c -> {
                if (c.consecutiveFailures() == 0) {
                    yield HealthCheckResult.healthy("Circuit closed, no failures");
                }
                yield HealthCheckResult.healthy("Circuit closed, " + c.consecutiveFailures() + " recent failure(s)");
            }
            case CircuitBreakerState.HalfOpen h ->
                    HealthCheckResult.degraded("Circuit half-open, " + h.successfulProbes() + " probe(s) succeeded");
            case CircuitBreakerState.Open o ->
                    HealthCheckResult.unhealthy("Circuit open since " + o.openedAt());
        };
    }
}

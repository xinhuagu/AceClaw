package dev.aceclaw.infra.health;

/**
 * A named health check that reports the status of a component.
 *
 * <p>Implementations should be lightweight — the {@link HealthMonitor}
 * invokes checks periodically on a virtual thread.
 */
public interface HealthCheck {

    /**
     * Returns the unique name of this health check (e.g. "llm", "memory-store").
     */
    String name();

    /**
     * Performs the health check and returns the result.
     *
     * <p>Implementations should not throw — capture errors into
     * {@link HealthCheckResult#unhealthy(String)} instead.
     */
    HealthCheckResult check();
}

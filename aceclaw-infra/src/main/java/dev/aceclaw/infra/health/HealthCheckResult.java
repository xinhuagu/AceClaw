package dev.aceclaw.infra.health;

import dev.aceclaw.infra.event.HealthEvent;

import java.time.Instant;

/**
 * The result of a single {@link HealthCheck} invocation.
 *
 * @param status    the health status
 * @param detail    human-readable detail (may be empty)
 * @param timestamp when the check was performed
 */
public record HealthCheckResult(
        HealthEvent.Status status,
        String detail,
        Instant timestamp
) {

    public HealthCheckResult {
        if (status == null) throw new IllegalArgumentException("status must not be null");
        if (detail == null) detail = "";
        if (timestamp == null) timestamp = Instant.now();
    }

    /** Creates a HEALTHY result with the given detail. */
    public static HealthCheckResult healthy(String detail) {
        return new HealthCheckResult(HealthEvent.Status.HEALTHY, detail, Instant.now());
    }

    /** Creates a HEALTHY result with no detail. */
    public static HealthCheckResult healthy() {
        return healthy("");
    }

    /** Creates a DEGRADED result with the given detail. */
    public static HealthCheckResult degraded(String detail) {
        return new HealthCheckResult(HealthEvent.Status.DEGRADED, detail, Instant.now());
    }

    /** Creates an UNHEALTHY result with the given detail. */
    public static HealthCheckResult unhealthy(String detail) {
        return new HealthCheckResult(HealthEvent.Status.UNHEALTHY, detail, Instant.now());
    }
}

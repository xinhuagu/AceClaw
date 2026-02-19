package dev.aceclaw.infra.event;

import java.time.Instant;

/** Events related to component health changes. */
public sealed interface HealthEvent extends AceClawEvent {

    enum Status { HEALTHY, DEGRADED, UNHEALTHY }

    record StatusChanged(String component, Status previous, Status current, String detail, Instant timestamp) implements HealthEvent {}
}

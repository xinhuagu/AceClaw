package dev.aceclaw.infra.event;

import java.time.Instant;

/** Daemon-level system events. */
public sealed interface SystemEvent extends AceClawEvent {

    record DaemonStarted(long bootMs, Instant timestamp) implements SystemEvent {}

    record DaemonShutdownInitiated(String reason, Instant timestamp) implements SystemEvent {}

    record ConfigReloaded(Instant timestamp) implements SystemEvent {}
}

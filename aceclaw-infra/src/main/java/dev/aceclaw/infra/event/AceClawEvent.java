package dev.aceclaw.infra.event;

import java.time.Instant;

/**
 * Sealed event hierarchy for type-safe internal communication.
 *
 * <p>All events in the AceClaw system extend this sealed interface.
 * The sealed hierarchy enables exhaustive pattern matching with
 * {@code switch} expressions, ensuring compile-time completeness.
 *
 * <p>Event categories:
 * <ul>
 *   <li>{@link AgentEvent} — agent loop lifecycle (started, completed, error)</li>
 *   <li>{@link ToolEvent} — tool execution (invoked, completed, error)</li>
 *   <li>{@link SessionEvent} — session lifecycle (created, resumed, closed)</li>
 *   <li>{@link HealthEvent} — component health changes</li>
 *   <li>{@link SystemEvent} — daemon-level events (startup, shutdown, config)</li>
 *   <li>{@link SchedulerEvent} — cron scheduler events (triggered, completed, failed, skipped)</li>
 *   <li>{@link PlanEvent} — task plan lifecycle events (created, step started/completed, plan completed)</li>
 * </ul>
 */
public sealed interface AceClawEvent
        permits AgentEvent, ToolEvent, SessionEvent, HealthEvent, SystemEvent, SchedulerEvent, PlanEvent {

    /** When this event occurred. */
    Instant timestamp();
}

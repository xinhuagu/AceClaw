package dev.aceclaw.infra.event;

import java.time.Instant;

/**
 * Events emitted during task plan lifecycle.
 *
 * <p>Permits:
 * <ul>
 *   <li>{@link PlanCreated} — a plan has been generated from a complex prompt</li>
 *   <li>{@link StepStarted} — a plan step is about to execute</li>
 *   <li>{@link StepCompleted} — a plan step has finished (success or failure)</li>
 *   <li>{@link PlanCompleted} — the entire plan has finished execution</li>
 * </ul>
 */
public sealed interface PlanEvent extends AceClawEvent {

    /** The plan identifier. */
    String planId();

    record PlanCreated(String planId, int stepCount, Instant timestamp) implements PlanEvent {}

    record StepStarted(String planId, String stepId, String stepName, Instant timestamp)
            implements PlanEvent {}

    record StepCompleted(String planId, String stepId, boolean success, long durationMs, Instant timestamp)
            implements PlanEvent {}

    record PlanCompleted(String planId, boolean success, long totalDurationMs, Instant timestamp)
            implements PlanEvent {}
}

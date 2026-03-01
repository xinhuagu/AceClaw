package dev.aceclaw.infra.event;

import java.time.Instant;
import java.util.Objects;

/**
 * Events emitted during task plan lifecycle.
 *
 * <p>Permits:
 * <ul>
 *   <li>{@link PlanCreated} — a plan has been generated from a complex prompt</li>
 *   <li>{@link StepStarted} — a plan step is about to execute</li>
 *   <li>{@link StepCompleted} — a plan step has finished (success or failure)</li>
 *   <li>{@link PlanCompleted} — the entire plan has finished execution</li>
 *   <li>{@link PlanReplanned} — a plan was adaptively replanned after a step failure</li>
 *   <li>{@link PlanEscalated} — replanning determined recovery is impossible</li>
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

    record PlanReplanned(String planId, int replanAttempt, int newStepCount,
                         String rationale, Instant timestamp) implements PlanEvent {
        public PlanReplanned {
            Objects.requireNonNull(planId, "planId");
            Objects.requireNonNull(rationale, "rationale");
            Objects.requireNonNull(timestamp, "timestamp");
        }
    }

    record PlanEscalated(String planId, String reason, Instant timestamp) implements PlanEvent {
        public PlanEscalated {
            Objects.requireNonNull(planId, "planId");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(timestamp, "timestamp");
        }
    }
}

package dev.aceclaw.core.planner;

import java.time.Duration;

/**
 * The lifecycle status of a task plan.
 */
public sealed interface PlanStatus {

    record Draft() implements PlanStatus {}

    record Executing(int completedSteps, int totalSteps) implements PlanStatus {}

    record Completed(Duration totalDuration) implements PlanStatus {}

    record Failed(String reason, String failedStepId) implements PlanStatus {}
}

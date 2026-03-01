package dev.aceclaw.core.planner;

import java.util.List;
import java.util.Objects;

/**
 * Context bundle passed to {@link AdaptiveReplanner} when a plan step fails.
 *
 * @param originalGoal       the user's original prompt that triggered planning
 * @param failedStep         the step that failed after fallback exhaustion
 * @param failedStepIndex    zero-based index of the failed step
 * @param failureReason      error message from the failed step
 * @param completedSummaries summaries of all steps executed so far (including the failed one)
 * @param remainingSteps     steps that were not yet attempted
 * @param replanAttempt      1-based replan attempt number (max {@link AdaptiveReplanner#MAX_REPLAN_ATTEMPTS})
 */
public record ReplanTrigger(
        String originalGoal,
        PlannedStep failedStep,
        int failedStepIndex,
        String failureReason,
        List<CompletedStepSummary> completedSummaries,
        List<PlannedStep> remainingSteps,
        int replanAttempt
) {

    public ReplanTrigger {
        Objects.requireNonNull(originalGoal, "originalGoal");
        Objects.requireNonNull(failedStep, "failedStep");
        Objects.requireNonNull(failureReason, "failureReason");
        if (failedStepIndex < 0) {
            throw new IllegalArgumentException("failedStepIndex must be >= 0");
        }
        if (replanAttempt < 1) {
            throw new IllegalArgumentException("replanAttempt must be >= 1");
        }
        completedSummaries = completedSummaries != null ? List.copyOf(completedSummaries) : List.of();
        remainingSteps = remainingSteps != null ? List.copyOf(remainingSteps) : List.of();
    }
}

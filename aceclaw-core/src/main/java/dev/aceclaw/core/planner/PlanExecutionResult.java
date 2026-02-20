package dev.aceclaw.core.planner;

import java.util.List;

/**
 * The aggregated result of executing a complete task plan.
 *
 * @param plan            the final plan state (with updated step statuses)
 * @param stepResults     result of each step attempted (may be fewer than plan.steps() if aborted)
 * @param totalDurationMs wall-clock time for the entire plan execution
 * @param success         whether all attempted steps completed successfully
 * @param totalTokensUsed total tokens consumed across all steps
 */
public record PlanExecutionResult(
        TaskPlan plan,
        List<StepResult> stepResults,
        long totalDurationMs,
        boolean success,
        int totalTokensUsed
) {

    public PlanExecutionResult {
        stepResults = stepResults != null ? List.copyOf(stepResults) : List.of();
    }
}

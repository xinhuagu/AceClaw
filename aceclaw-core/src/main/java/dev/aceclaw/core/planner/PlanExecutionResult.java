package dev.aceclaw.core.planner;

import dev.aceclaw.core.llm.Message;

import java.util.List;
import java.util.Objects;

/**
 * The aggregated result of executing a complete task plan.
 *
 * @param plan            the final plan state (with updated step statuses)
 * @param stepResults     result of each step attempted (may be fewer than plan.steps() if aborted)
 * @param messages        all messages produced while executing the plan
 * @param totalDurationMs wall-clock time for the entire plan execution
 * @param success         whether all attempted steps completed successfully
 * @param totalTokensUsed total tokens consumed across all steps
 */
public record PlanExecutionResult(
        TaskPlan plan,
        List<StepResult> stepResults,
        List<Message> messages,
        long totalDurationMs,
        boolean success,
        int totalTokensUsed
) {

    public PlanExecutionResult {
        Objects.requireNonNull(plan, "plan");
        stepResults = stepResults != null ? List.copyOf(stepResults) : List.of();
        messages = messages != null ? List.copyOf(messages) : List.of();
    }
}

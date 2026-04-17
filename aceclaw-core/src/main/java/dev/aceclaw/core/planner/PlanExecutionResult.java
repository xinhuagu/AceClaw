package dev.aceclaw.core.planner;

import dev.aceclaw.core.llm.Message;
import dev.aceclaw.core.llm.RequestAttribution;

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
 * @param requestAttribution aggregated LLM request breakdown across all step results plus any
 *                            REPLAN requests made during execution. Does NOT include the
 *                            upfront PLANNER request; that's issued by the caller before the
 *                            executor runs and must be merged separately
 */
public record PlanExecutionResult(
        TaskPlan plan,
        List<StepResult> stepResults,
        List<Message> messages,
        long totalDurationMs,
        boolean success,
        int totalTokensUsed,
        RequestAttribution requestAttribution
) {

    public PlanExecutionResult {
        Objects.requireNonNull(plan, "plan");
        stepResults = stepResults != null ? List.copyOf(stepResults) : List.of();
        messages = messages != null ? List.copyOf(messages) : List.of();
        requestAttribution = requestAttribution != null ? requestAttribution : RequestAttribution.empty();
    }

    /** Backward-compatible constructor for callers predating request attribution. */
    public PlanExecutionResult(TaskPlan plan,
                               List<StepResult> stepResults,
                               List<Message> messages,
                               long totalDurationMs,
                               boolean success,
                               int totalTokensUsed) {
        this(plan, stepResults, messages, totalDurationMs, success, totalTokensUsed,
                RequestAttribution.empty());
    }
}

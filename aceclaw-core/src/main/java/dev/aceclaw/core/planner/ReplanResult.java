package dev.aceclaw.core.planner;

import java.util.List;
import java.util.Objects;

/**
 * Outcome of an adaptive replan attempt.
 *
 * <p>Either the LLM produced revised steps ({@link Revised}) or determined that
 * recovery is impossible ({@link Escalated}).
 */
public sealed interface ReplanResult {

    /**
     * The LLM produced a revised set of steps to replace the remaining plan.
     *
     * @param revisedSteps new steps to execute (fresh UUIDs, PENDING status)
     * @param rationale    LLM's explanation for why these steps were chosen
     */
    record Revised(List<PlannedStep> revisedSteps, String rationale) implements ReplanResult {
        public Revised {
            revisedSteps = revisedSteps != null ? List.copyOf(revisedSteps) : List.of();
            Objects.requireNonNull(rationale, "rationale");
        }
    }

    /**
     * The LLM determined that recovery is not possible.
     *
     * @param reason explanation of why the plan cannot be salvaged
     */
    record Escalated(String reason) implements ReplanResult {
        public Escalated {
            Objects.requireNonNull(reason, "reason");
        }
    }
}

package dev.aceclaw.core.planner;

import dev.aceclaw.core.llm.LlmException;
import dev.aceclaw.core.llm.RequestAttribution;
import dev.aceclaw.core.llm.RequestSource;
import dev.aceclaw.core.llm.ToolDefinition;

import java.util.List;

/**
 * Generates a task plan from a user goal and available tools.
 */
public interface TaskPlanner {

    /**
     * Generates a sequential plan for achieving the given goal.
     *
     * @param goal           the user's goal (original prompt)
     * @param availableTools tools the agent has access to
     * @return a task plan with ordered steps
     * @throws LlmException if plan generation fails
     */
    TaskPlan plan(String goal, List<ToolDefinition> availableTools) throws LlmException;

    /**
     * Attribution-aware variant. Implementations that make LLM calls during plan generation
     * should record one {@link RequestSource#PLANNER} entry per request on the supplied
     * builder (if non-null). The default implementation just records a single PLANNER entry
     * after the underlying {@link #plan} call succeeds — suitable for planners that make
     * exactly one LLM request per invocation, which covers every planner in-tree today.
     *
     * @param attribution builder to record requests into; may be {@code null} if the caller
     *                    doesn't care about attribution
     */
    default TaskPlan plan(String goal, List<ToolDefinition> availableTools,
                          RequestAttribution.Builder attribution) throws LlmException {
        var result = plan(goal, availableTools);
        if (attribution != null) {
            attribution.record(RequestSource.PLANNER);
        }
        return result;
    }
}

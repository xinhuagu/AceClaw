package dev.aceclaw.core.planner;

import dev.aceclaw.core.llm.LlmException;
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
}

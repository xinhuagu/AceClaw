package dev.aceclaw.core.planner;

import dev.aceclaw.core.agent.CancellationToken;
import dev.aceclaw.core.agent.StreamingAgentLoop;
import dev.aceclaw.core.llm.LlmException;
import dev.aceclaw.core.llm.Message;
import dev.aceclaw.core.llm.StreamEventHandler;

import java.util.List;

/**
 * Executes a task plan step by step through the agent loop.
 */
public interface PlanExecutor {

    /**
     * Executes the given plan sequentially.
     *
     * @param plan                the plan to execute
     * @param agentLoop           the agent loop for running each step
     * @param conversationHistory existing conversation history for context
     * @param handler             stream event handler for real-time output
     * @param cancellationToken   cancellation token (may be null)
     * @return the aggregated execution result
     * @throws LlmException if a step fails fatally
     */
    PlanExecutionResult execute(
            TaskPlan plan,
            StreamingAgentLoop agentLoop,
            List<Message> conversationHistory,
            StreamEventHandler handler,
            CancellationToken cancellationToken) throws LlmException;
}

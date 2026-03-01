package dev.aceclaw.core.planner;

import dev.aceclaw.core.agent.StreamingAgentLoop;

/**
 * Factory for creating new {@link StreamingAgentLoop} instances.
 *
 * <p>Used by {@link DagPlanExecutor} to create per-step loop instances for parallel execution.
 * Each parallel step needs its own loop because {@code runTurn} is stateful.
 */
@FunctionalInterface
public interface AgentLoopFactory {
    StreamingAgentLoop create();
}

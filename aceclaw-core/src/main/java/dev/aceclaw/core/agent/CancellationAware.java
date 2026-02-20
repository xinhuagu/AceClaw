package dev.aceclaw.core.agent;

/**
 * Marker interface for tools that can propagate cancellation to sub-agents.
 *
 * <p>When the agent loop detects a tool implements this interface, it injects
 * the current {@link CancellationToken} before calling {@code execute()}.
 * This allows tools like {@code TaskTool} and {@code SkillTool} to forward
 * cancellation to sub-agent loops they spawn.
 */
public interface CancellationAware {

    /**
     * Sets the cancellation token for this tool invocation.
     * Called by the agent loop before each {@code execute()} call.
     *
     * @param token the cancellation token, or null to clear
     */
    void setCancellationToken(CancellationToken token);
}

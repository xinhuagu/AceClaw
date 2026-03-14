package dev.aceclaw.core.llm;

/**
 * Callback interface for handling streaming LLM events.
 *
 * <p>Default implementations are no-ops so callers can override only
 * the events they care about.
 */
public interface StreamEventHandler {

    default void onMessageStart(StreamEvent.MessageStart event) {}

    default void onContentBlockStart(StreamEvent.ContentBlockStart event) {}

    default void onTextDelta(StreamEvent.TextDelta event) {}

    default void onThinkingDelta(StreamEvent.ThinkingDelta event) {}

    default void onToolUseDelta(StreamEvent.ToolUseDelta event) {}

    default void onContentBlockStop(StreamEvent.ContentBlockStop event) {}

    default void onMessageDelta(StreamEvent.MessageDelta event) {}

    default void onComplete(StreamEvent.StreamComplete event) {}

    default void onError(StreamEvent.StreamError event) {}

    /**
     * Called when context compaction occurs during a turn.
     *
     * @param originalTokens  estimated tokens before compaction
     * @param compactedTokens estimated tokens after compaction
     * @param phase           the compaction phase reached ("PRUNED" or "SUMMARIZED")
     */
    default void onCompaction(int originalTokens, int compactedTokens, String phase) {}

    /**
     * Called when a sub-agent (skill or task) starts execution.
     *
     * @param agentId identifier for the sub-agent (e.g. "skill:commit")
     * @param prompt  the prompt sent to the sub-agent
     */
    default void onSubAgentStart(String agentId, String prompt) {}

    /**
     * Called when a sub-agent (skill or task) finishes execution.
     *
     * @param agentId identifier for the sub-agent
     */
    default void onSubAgentEnd(String agentId) {}

    /**
     * Called after each LLM call with the per-call token usage.
     * {@code lastInputTokens} reflects the effective context window occupation
     * (input + cache creation + cache read tokens) from the most recent API call.
     * This value grows as conversation history accumulates within a turn.
     *
     * @param lastInputTokens   effective input tokens (input + cacheCreation + cacheRead)
     *                           from the most recent LLM call
     * @param totalInputTokens  cumulative input tokens across all calls in this turn
     * @param totalOutputTokens cumulative output tokens across all calls in this turn
     */
    default void onUsageUpdate(long lastInputTokens, long totalInputTokens, long totalOutputTokens) {}

    /**
     * Called periodically to signal the agent is still active during long
     * operations (e.g. tool execution, LLM streaming).
     */
    default void onHeartbeat(StreamEvent.Heartbeat event) {}

    /**
     * Called when a tool execution finishes.
     *
     * @param toolUseId  tool-use block id from the model response
     * @param toolName   tool name
     * @param durationMs execution duration in milliseconds
     * @param isError    whether the tool result is an error
     * @param error      optional error preview (null for success)
     */
    default void onToolCompleted(String toolUseId, String toolName,
                                 long durationMs, boolean isError, String error) {}
}

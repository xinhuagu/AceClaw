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
}

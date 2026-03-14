package dev.aceclaw.cli;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Abstraction for rendering streaming agent events.
 *
 * <p>Implementations include {@link ForegroundOutputSink} (renders directly to terminal)
 * and {@link BackgroundOutputBuffer} (buffers events for later replay).
 */
public interface OutputSink {

    /** Receives a thinking delta (extended thinking text). */
    void onThinkingDelta(String delta);

    /** Receives a text delta (assistant response text). */
    void onTextDelta(String delta);

    /** Notifies that a tool is being invoked. */
    void onToolUse(String toolName);

    /**
     * Notifies that a tool is being invoked.
     *
     * @param toolId   tool-use id from the model response
     * @param toolName tool name
     * @param summary  optional short input summary
     */
    default void onToolUse(String toolId, String toolName, String summary) {
        onToolUse(toolName);
    }

    /**
     * Notifies that a tool invocation has completed.
     *
     * @param toolId     tool-use id from the model response
     * @param toolName   tool name
     * @param durationMs execution duration in milliseconds
     * @param isError    whether the tool failed
     * @param error      optional brief error text
     */
    default void onToolCompleted(String toolId, String toolName,
                                 long durationMs, boolean isError, String error) {}

    /** Receives a stream error. */
    void onStreamError(String error);

    /** Receives a stream cancellation acknowledgment. */
    void onStreamCancelled();

    /** Called when the entire turn is complete (final JSON-RPC response). */
    void onTurnComplete(JsonNode result, boolean hasError);

    /** Called when the connection is closed unexpectedly. */
    void onConnectionClosed();

    /** Called when a plan is created. */
    default void onPlanCreated(JsonNode params) {}

    /** Called when a plan step starts. */
    default void onPlanStepStarted(JsonNode params) {}

    /** Called when a plan step completes. */
    default void onPlanStepCompleted(JsonNode params) {}

    /** Called when a plan completes. */
    default void onPlanCompleted(JsonNode params) {}

    /** Called when a sub-agent starts. */
    default void onSubAgentStart(JsonNode params) {}

    /** Called when a sub-agent completes. */
    default void onSubAgentEnd(JsonNode params) {}

    /** Called when context compaction occurs. */
    default void onCompaction(JsonNode params) {}

    /** Called when a watchdog budget limit is reached. */
    default void onBudgetExhausted(JsonNode params) {}

    /**
     * Called when a usage update is received during streaming.
     *
     * @param inputTokens   effective input tokens (input + cache creation + cache read)
     *                       representing actual context window occupation for this LLM call
     * @param contextWindow configured context window size in tokens
     */
    default void onUsageUpdate(long inputTokens, long contextWindow) {}
}

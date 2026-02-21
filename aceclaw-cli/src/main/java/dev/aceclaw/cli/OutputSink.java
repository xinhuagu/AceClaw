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

    /** Called when context compaction occurs. */
    default void onCompaction(JsonNode params) {}
}

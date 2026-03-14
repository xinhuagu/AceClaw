package dev.aceclaw.core.llm;

import java.util.Objects;

/**
 * Events emitted during a streaming LLM response.
 *
 * <p>Implementations process these via {@link StreamEventHandler}.
 */
public sealed interface StreamEvent {

    /**
     * The stream has started and the message metadata is available.
     *
     * @param id    message identifier
     * @param model model name
     * @param usage initial usage stats (contains {@code input_tokens} from Anthropic {@code message_start});
     *              may be {@code null} for providers that don't report usage at stream start
     */
    record MessageStart(String id, String model, Usage usage) implements StreamEvent {
        /** Convenience constructor for providers/tests that don't supply start-of-stream usage. */
        public MessageStart(String id, String model) {
            this(id, model, null);
        }
    }

    /**
     * A new content block has started at the given index.
     */
    record ContentBlockStart(int index, ContentBlock block) implements StreamEvent {}

    /**
     * An incremental text delta for a text content block.
     */
    record TextDelta(String text) implements StreamEvent {}

    /**
     * An incremental thinking delta for an extended thinking content block.
     */
    record ThinkingDelta(String text) implements StreamEvent {}

    /**
     * An incremental JSON delta for a tool-use input accumulation.
     *
     * @param index       content block index
     * @param name        tool name (present at start, may be null on subsequent deltas)
     * @param partialJson partial JSON fragment to accumulate
     */
    record ToolUseDelta(int index, String name, String partialJson) implements StreamEvent {}

    /**
     * A content block at the given index has finished.
     */
    record ContentBlockStop(int index) implements StreamEvent {}

    /**
     * Final message-level metadata (stop reason and final usage).
     */
    record MessageDelta(StopReason stopReason, Usage usage) implements StreamEvent {}

    /**
     * The stream completed successfully.
     */
    record StreamComplete() implements StreamEvent {}

    /**
     * The stream encountered an error.
     */
    record StreamError(LlmException error) implements StreamEvent {}

    /**
     * A heartbeat signal indicating the agent is still active (e.g. during
     * long-running tool execution or LLM calls).
     *
     * @param phase describes what the agent is doing (e.g. "tool_execution")
     */
    record Heartbeat(String phase) implements StreamEvent {
        public Heartbeat {
            phase = Objects.requireNonNull(phase, "phase");
        }
    }
}

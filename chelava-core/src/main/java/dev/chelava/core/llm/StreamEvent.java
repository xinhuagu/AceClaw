package dev.chelava.core.llm;

/**
 * Events emitted during a streaming LLM response.
 *
 * <p>Implementations process these via {@link StreamEventHandler}.
 */
public sealed interface StreamEvent {

    /**
     * The stream has started and the message metadata is available.
     */
    record MessageStart(String id, String model) implements StreamEvent {}

    /**
     * A new content block has started at the given index.
     */
    record ContentBlockStart(int index, ContentBlock block) implements StreamEvent {}

    /**
     * An incremental text delta for a text content block.
     */
    record TextDelta(String text) implements StreamEvent {}

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
}

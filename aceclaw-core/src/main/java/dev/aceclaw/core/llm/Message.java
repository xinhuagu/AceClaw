package dev.aceclaw.core.llm;

import java.util.List;

/**
 * A message in the conversation history sent to the LLM.
 */
public sealed interface Message {

    /**
     * A message from the user (human or tool results).
     */
    record UserMessage(List<ContentBlock> content) implements Message {

        public UserMessage {
            content = List.copyOf(content);
        }
    }

    /**
     * A message from the assistant (model output).
     */
    record AssistantMessage(List<ContentBlock> content) implements Message {

        public AssistantMessage {
            content = List.copyOf(content);
        }
    }

    // -- Factory methods --

    /**
     * Creates a user message containing a single text block.
     */
    static UserMessage user(String text) {
        return new UserMessage(List.of(new ContentBlock.Text(text)));
    }

    /**
     * Creates an assistant message containing a single text block.
     */
    static AssistantMessage assistant(String text) {
        return new AssistantMessage(List.of(new ContentBlock.Text(text)));
    }

    /**
     * Creates a user message carrying one or more tool results.
     */
    static UserMessage toolResults(List<ContentBlock.ToolResult> results) {
        return new UserMessage(results.stream().map(r -> (ContentBlock) r).toList());
    }

    /**
     * Creates a user message carrying a single tool result.
     */
    static UserMessage toolResult(String toolUseId, String content, boolean isError) {
        return new UserMessage(List.of(new ContentBlock.ToolResult(toolUseId, content, isError)));
    }
}

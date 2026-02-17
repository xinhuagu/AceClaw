package dev.chelava.core.llm;

import java.util.List;

/**
 * An immutable response from an LLM provider.
 *
 * @param id         provider-assigned response identifier
 * @param model      model that generated this response
 * @param content    content blocks produced by the model
 * @param stopReason reason the model stopped generating
 * @param usage      token usage statistics
 */
public record LlmResponse(
        String id,
        String model,
        List<ContentBlock> content,
        StopReason stopReason,
        Usage usage
) {

    public LlmResponse {
        content = List.copyOf(content);
    }

    /**
     * Convenience: returns the concatenated text from all {@link ContentBlock.Text} blocks.
     */
    public String text() {
        return content.stream()
                .filter(b -> b instanceof ContentBlock.Text)
                .map(b -> ((ContentBlock.Text) b).text())
                .reduce("", (a, b) -> a + b);
    }

    /**
     * Convenience: returns all tool-use blocks in this response.
     */
    public List<ContentBlock.ToolUse> toolUseBlocks() {
        return content.stream()
                .filter(b -> b instanceof ContentBlock.ToolUse)
                .map(b -> (ContentBlock.ToolUse) b)
                .toList();
    }

    /**
     * Whether the model wants to invoke at least one tool.
     */
    public boolean hasToolUse() {
        return stopReason == StopReason.TOOL_USE;
    }
}

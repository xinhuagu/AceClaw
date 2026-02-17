package dev.chelava.core.llm;

/**
 * A single block of content in an LLM message.
 *
 * <p>Content is polymorphic: plain text, a tool invocation request,
 * or a tool result returned to the model.
 */
public sealed interface ContentBlock {

    /**
     * Plain text output from the model.
     */
    record Text(String text) implements ContentBlock {}

    /**
     * A tool invocation requested by the model.
     *
     * @param id        unique identifier for this tool-use block (used to match results)
     * @param name      tool name
     * @param inputJson raw JSON string of the tool input arguments
     */
    record ToolUse(String id, String name, String inputJson) implements ContentBlock {}

    /**
     * Result of a tool execution, sent back to the model.
     *
     * @param toolUseId the id of the ToolUse block this result answers
     * @param content   textual result content
     * @param isError   whether the tool execution resulted in an error
     */
    record ToolResult(String toolUseId, String content, boolean isError) implements ContentBlock {}
}

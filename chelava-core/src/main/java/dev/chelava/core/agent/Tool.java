package dev.chelava.core.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.chelava.core.llm.ToolDefinition;

/**
 * A tool that the agent can invoke during its ReAct loop.
 *
 * <p>Each tool has a name, description, and JSON Schema for its inputs.
 * The agent loop looks up tools by name and executes them with the
 * JSON input provided by the LLM.
 */
public interface Tool {

    /**
     * Unique tool name (e.g. "read_file", "bash").
     */
    String name();

    /**
     * Human-readable description for the LLM to understand when to use this tool.
     */
    String description();

    /**
     * JSON Schema describing the tool's input parameters.
     */
    JsonNode inputSchema();

    /**
     * Executes the tool with the given JSON input string.
     *
     * @param inputJson raw JSON string of the tool input arguments
     * @return the result of the tool execution
     * @throws Exception if execution fails
     */
    ToolResult execute(String inputJson) throws Exception;

    /**
     * Result of a tool execution.
     *
     * @param output  textual output of the tool
     * @param isError whether the execution resulted in an error
     */
    record ToolResult(String output, boolean isError) {}

    /**
     * Converts this tool to a {@link ToolDefinition} for inclusion in LLM requests.
     */
    default ToolDefinition toDefinition() {
        return new ToolDefinition(name(), description(), inputSchema());
    }
}

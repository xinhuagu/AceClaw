package dev.chelava.core.llm;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Definition of a tool that the model may invoke.
 *
 * @param name        unique tool name (e.g. "read_file")
 * @param description human-readable description for the model
 * @param inputSchema JSON Schema describing the tool's input parameters
 */
public record ToolDefinition(
        String name,
        String description,
        JsonNode inputSchema
) {}

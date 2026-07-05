package dev.aceclaw.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.agent.Tool;
import dev.aceclaw.security.Capability;
import dev.aceclaw.security.CapabilityAware;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Adapts an MCP tool to the AceClaw {@link Tool} interface.
 *
 * <p>Each MCP tool is exposed with the naming convention {@code mcp__<serverName>__<toolName>},
 * matching the Claude Code convention. The double underscore separates namespace, server, and tool.
 *
 * <p>Tool execution delegates to the {@link McpSyncClient#callTool} method and converts the
 * {@link McpSchema.CallToolResult} back to a {@link Tool.ToolResult}.
 */
public final class McpToolBridge implements Tool, CapabilityAware {

    private static final Logger log = LoggerFactory.getLogger(McpToolBridge.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String qualifiedName;
    private final String serverName;
    private final String mcpToolName;
    private final String description;
    private final JsonNode inputSchema;
    private final McpSyncClient client;

    private McpToolBridge(String qualifiedName, String serverName, String mcpToolName, String description,
                          JsonNode inputSchema, McpSyncClient client) {
        this.qualifiedName = qualifiedName;
        this.serverName = serverName;
        this.mcpToolName = mcpToolName;
        this.description = description;
        this.inputSchema = inputSchema;
        this.client = client;
    }

    /**
     * Creates a bridged tool from an MCP tool definition.
     *
     * @param serverName the MCP server name (from config)
     * @param mcpTool    the MCP tool definition
     * @param client     the MCP client for tool execution
     * @return a new tool bridge instance
     */
    public static McpToolBridge create(String serverName, McpSchema.Tool mcpTool, McpSyncClient client) {
        Objects.requireNonNull(serverName, "serverName");
        Objects.requireNonNull(mcpTool, "mcpTool");
        Objects.requireNonNull(client, "client");
        var qualifiedName = "mcp__" + serverName + "__" + mcpTool.name();

        // Convert MCP input schema to Jackson JsonNode
        JsonNode inputSchema;
        try {
            var schemaJson = MAPPER.writeValueAsString(mcpTool.inputSchema());
            inputSchema = MAPPER.readTree(schemaJson);
        } catch (Exception e) {
            log.warn("Failed to convert input schema for MCP tool '{}': {}", qualifiedName, e.getMessage());
            inputSchema = MAPPER.createObjectNode()
                    .put("type", "object");
        }

        return new McpToolBridge(qualifiedName, serverName, mcpTool.name(),
                mcpTool.description(), inputSchema, client);
    }

    @Override
    public String name() {
        return qualifiedName;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public JsonNode inputSchema() {
        return inputSchema;
    }

    @Override
    public ToolResult execute(String inputJson) throws Exception {
        log.debug("Executing MCP tool '{}' ({})", qualifiedName, mcpToolName);

        // Parse input JSON to a Map for the CallToolRequest
        Map<String, Object> args;
        if (inputJson == null || inputJson.isBlank() || inputJson.equals("{}")) {
            args = Map.of();
        } else {
            @SuppressWarnings("unchecked")
            var parsed = MAPPER.readValue(inputJson, LinkedHashMap.class);
            args = parsed;
        }

        // Call the MCP tool
        var request = new McpSchema.CallToolRequest(mcpToolName, args);
        var result = client.callTool(request);

        // Extract text content from the result
        var output = extractContent(result);
        var isError = result.isError() != null && result.isError();

        log.debug("MCP tool '{}' completed: isError={}, outputLength={}",
                qualifiedName, isError, output.length());

        return new ToolResult(output, isError);
    }

    /**
     * Produces the structured {@link Capability} for the governance pipeline.
     * Delegates to {@link McpCapabilityInference} for the best-effort
     * inference; returns the opaque {@link Capability.McpInvoke} when no
     * pattern matches.
     *
     * <p>The args payload is intentionally not retained on the emitted
     * variant — args can be huge and may carry secrets; policies decide on
     * (server, method) or the resolved path alone.
     */
    @Override
    public Capability toCapability(JsonNode args) {
        var inferred = McpCapabilityInference.infer(mcpToolName, args);
        return inferred != null ? inferred : new Capability.McpInvoke(serverName, mcpToolName);
    }

    /**
     * Extracts text content from an MCP {@link McpSchema.CallToolResult}.
     * Concatenates all text content blocks, separated by newlines.
     */
    private static String extractContent(McpSchema.CallToolResult result) {
        if (result.content() == null || result.content().isEmpty()) {
            return "";
        }

        var sb = new StringBuilder();
        for (var content : result.content()) {
            if (content instanceof McpSchema.TextContent text) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(text.text());
            } else {
                // For non-text content (images, etc.), include a description
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append("[Non-text content: ").append(content.type()).append(']');
            }
        }
        return sb.toString();
    }
}

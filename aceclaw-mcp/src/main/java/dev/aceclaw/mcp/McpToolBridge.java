package dev.aceclaw.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.agent.Tool;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Adapts an MCP tool to the AceClaw {@link Tool} interface.
 *
 * <p>Each MCP tool is exposed with the naming convention {@code mcp__<serverName>__<toolName>},
 * matching the Claude Code convention. The double underscore separates namespace, server, and tool.
 *
 * <p>Tool execution delegates to the {@link McpSyncClient#callTool} method and converts the
 * {@link McpSchema.CallToolResult} back to a {@link Tool.ToolResult}.
 */
public final class McpToolBridge implements Tool {

    private static final Logger log = LoggerFactory.getLogger(McpToolBridge.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Dedicated executor for blocking MCP RPC calls (virtual threads are interruptible). */
    private static final ExecutorService MCP_TOOL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final String qualifiedName;
    private final String mcpToolName;
    private final String description;
    private final JsonNode inputSchema;
    private final McpSyncClient client;
    private final Duration timeout;

    private McpToolBridge(String qualifiedName, String mcpToolName, String description,
                          JsonNode inputSchema, McpSyncClient client, Duration timeout) {
        this.qualifiedName = qualifiedName;
        this.mcpToolName = mcpToolName;
        this.description = description;
        this.inputSchema = inputSchema;
        this.client = client;
        this.timeout = timeout;
    }

    /**
     * Creates a bridged tool from an MCP tool definition.
     *
     * @param serverName the MCP server name (from config)
     * @param mcpTool    the MCP tool definition
     * @param client     the MCP client for tool execution
     * @param timeout    per-server request timeout (from config); if null, the SDK's own timeout applies
     * @return a new tool bridge instance
     */
    public static McpToolBridge create(String serverName, McpSchema.Tool mcpTool,
                                       McpSyncClient client, Duration timeout) {
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

        return new McpToolBridge(qualifiedName, mcpTool.name(),
                mcpTool.description(), inputSchema, client, timeout);
    }

    /**
     * Creates a bridged tool with the default SDK timeout (no AceClaw-side timeout wrapper).
     */
    public static McpToolBridge create(String serverName, McpSchema.Tool mcpTool, McpSyncClient client) {
        return create(serverName, mcpTool, client, null);
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

        // Call the MCP tool on a virtual thread so cancel(true) actually interrupts it.
        // Uses the per-server timeout from config, or falls back to the SDK's own timeout.
        var request = new McpSchema.CallToolRequest(mcpToolName, args);
        McpSchema.CallToolResult result;
        var future = MCP_TOOL_EXECUTOR.submit(() -> client.callTool(request));
        try {
            if (timeout != null) {
                result = future.get(timeout.toSeconds(), TimeUnit.SECONDS);
            } else {
                result = future.get();
            }
        } catch (TimeoutException e) {
            future.cancel(true);
            long timeoutSecs = timeout.toSeconds();
            log.warn("MCP tool '{}' timed out after {}s", qualifiedName, timeoutSecs);
            return new ToolResult(
                    "MCP tool '" + qualifiedName + "' timed out after " + timeoutSecs
                            + " seconds. The MCP server may be unresponsive.",
                    true);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception cause ? cause
                    : new RuntimeException("MCP tool execution failed", e.getCause());
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return new ToolResult("MCP tool '" + qualifiedName + "' was interrupted", true);
        }

        // Extract text content from the result
        var output = extractContent(result);
        var isError = result.isError() != null && result.isError();

        log.debug("MCP tool '{}' completed: isError={}, outputLength={}",
                qualifiedName, isError, output.length());

        return new ToolResult(output, isError);
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

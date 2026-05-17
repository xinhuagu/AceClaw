package dev.aceclaw.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.agent.Tool;
import dev.aceclaw.security.Capability;
import dev.aceclaw.security.CapabilityAware;
import dev.aceclaw.security.WriteMode;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

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
     * Produces the structured {@link Capability} for the governance pipeline
     * (#480 / runtime-governance).
     *
     * <p>Best-effort inference: when the MCP method name has clear file-write
     * or file-delete intent <em>and</em> the args contain a path-shaped field,
     * emit {@link Capability.FileWrite} / {@link Capability.FileDelete} so the
     * structural hard-denial layer can refuse writes/deletes to {@code .env*},
     * {@code .ssh/*}, {@code /etc/*}, etc. — exactly as it does for built-in
     * file tools (Codex P1 follow-up on #495). Without this, an MCP filesystem
     * server's {@code write_file(path=".env")} would land as opaque
     * {@code McpInvoke} and the structural rules would never see the path.
     *
     * <p>Conservative on both sides: ambiguous method names (no obvious verb,
     * no path arg) fall through to {@link Capability.McpInvoke} which still
     * gets the standard policy + audit treatment. False positives just mean
     * the user sees a slightly more aggressive prompt — they can still
     * approve. False negatives mean the structural denial is missed, which is
     * the pre-fix behaviour.
     *
     * <p>The args payload itself is intentionally not retained on either
     * variant — args can be huge and may carry secrets; policies decide on
     * (server, method) / path alone.
     */
    @Override
    public Capability toCapability(JsonNode args) {
        var inferred = inferFileCapability(args);
        return inferred != null ? inferred : new Capability.McpInvoke(serverName, mcpToolName);
    }

    /**
     * Common keys MCP filesystem-style servers use for the target path of
     * a single-arg write/delete. Order matters only for readability — only
     * the first non-null textual match is extracted.
     */
    private static final List<String> PATH_FIELDS = List.of(
            "path", "file_path", "filepath", "filename", "file");

    /**
     * Common keys for the destination of a two-arg op (move/rename/copy).
     * The destination receives a new file, so it's effectively a write.
     */
    private static final List<String> DESTINATION_FIELDS = List.of(
            "destination", "dest", "target", "to", "new_path", "output_path", "output");

    /**
     * Common keys for the source of a two-arg op. For moves (not copies)
     * the source disappears, so it's effectively a delete.
     */
    private static final List<String> SOURCE_FIELDS = List.of(
            "source", "src", "from", "old_path", "input_path", "input");

    /** Method names with clear file-write intent (matched against lowercased mcpToolName). */
    private static final Pattern WRITE_VERB = Pattern.compile(
            "^(write|create|edit|append|put|save)(_.*)?$|.*_(write|create|edit|append|save)$");

    /** Method names with clear file-delete intent (matched against lowercased mcpToolName). */
    private static final Pattern DELETE_VERB = Pattern.compile(
            "^(delete|remove|unlink|rm)(_.*)?$|.*_(delete|remove|unlink)$");

    /**
     * Method names for two-arg destination-receiving ops: move/rename/copy.
     * Includes the short {@code mv} and {@code cp} forms as standalone names
     * (so a method literally named {@code mv} matches, but a method named
     * {@code recipe} does not — anchored full-word match).
     */
    private static final Pattern MOVE_DEST_VERB = Pattern.compile(
            "^(move|mv|rename|copy|cp)(_.*)?$|.*_(move|rename|copy)$");

    /**
     * Subset of {@link #MOVE_DEST_VERB} that does NOT remove the source —
     * copy/cp. Used to decide whether a sensitive source path should be
     * treated as a delete: copy doesn't delete the source, move does.
     */
    private static final Pattern COPY_VERB = Pattern.compile(
            "^(copy|cp)(_.*)?$|.*_copy$");

    private Capability inferFileCapability(JsonNode args) {
        if (args == null || args.isNull() || !args.isObject()) return null;
        String name = mcpToolName.toLowerCase(Locale.ROOT);

        if (WRITE_VERB.matcher(name).matches()) {
            Path p = safePath(extractField(args, PATH_FIELDS));
            return p == null ? null : new Capability.FileWrite(p, WriteMode.OVERWRITE);
        }
        if (DELETE_VERB.matcher(name).matches()) {
            Path p = safePath(extractField(args, PATH_FIELDS));
            return p == null ? null : new Capability.FileDelete(p);
        }
        if (MOVE_DEST_VERB.matcher(name).matches()) {
            // Two-arg op: destination is the "write" target; source is the
            // "delete" target (for moves only — copies leave the source).
            // The structural denial layer only sees one Capability, so:
            //   - If destination resolves, emit FileWrite(dest). This covers
            //     "move/copy to .env" — the most common attack vector
            //     (Codex P1 follow-up on #495).
            //   - Else if the op is a move (not a copy) and source resolves,
            //     emit FileDelete(source). Catches "move .env elsewhere"
            //     which effectively deletes .env.
            Path dst = safePath(extractField(args, DESTINATION_FIELDS));
            if (dst != null) return new Capability.FileWrite(dst, WriteMode.OVERWRITE);
            if (!COPY_VERB.matcher(name).matches()) {
                Path src = safePath(extractField(args, SOURCE_FIELDS));
                if (src != null) return new Capability.FileDelete(src);
            }
        }
        return null;
    }

    private static String extractField(JsonNode args, List<String> keys) {
        for (String key : keys) {
            var node = args.get(key);
            if (node != null && node.isTextual()) {
                return node.asText();
            }
        }
        return null;
    }

    /**
     * Parses {@code raw} into a {@link Path} or returns {@code null} when the
     * input is blank or malformed. Malformed paths (e.g. invalid characters
     * for the host filesystem) fall back to {@code McpInvoke} rather than
     * crash the dispatcher.
     */
    private static Path safePath(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Path.of(raw);
        } catch (InvalidPathException malformed) {
            return null;
        }
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

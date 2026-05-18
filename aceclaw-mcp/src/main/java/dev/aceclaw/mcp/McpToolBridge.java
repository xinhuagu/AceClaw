package dev.aceclaw.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.agent.Tool;
import dev.aceclaw.security.Capability;
import dev.aceclaw.security.CapabilityAware;
import dev.aceclaw.security.DefaultPermissionPolicy;
import dev.aceclaw.security.WriteMode;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
     * Produces the structured {@link Capability} for the governance pipeline
     * (#480 / runtime-governance).
     *
     * <p>Best-effort inference at this boundary so the policy's structural
     * hard-denial layer can refuse writes/deletes to {@code .env*},
     * {@code .ssh/*}, {@code /etc/*}, etc. — exactly as it does for built-in
     * file tools. Without this, an MCP filesystem server's
     * {@code write_file(path=".env")} would land as opaque {@code McpInvoke}
     * and the structural rules would never see the path.
     *
     * <h3>What gets classified as what</h3>
     *
     * <ul>
     *   <li>Method name matches {@code write|create|edit|append|put|save}
     *       (as prefix or suffix) with a {@code path}/{@code file_path}/etc.
     *       arg → {@link Capability.FileWrite}.</li>
     *   <li>Method name matches {@code delete|remove|unlink|rm} with a
     *       similar arg → {@link Capability.FileDelete}.</li>
     *   <li>Method name matches {@code move|rename|copy|mv|cp} with a
     *       destination-style arg ({@code destination}, {@code dest},
     *       {@code target}, {@code to}, {@code new_path}, …) →
     *       {@link Capability.FileWrite} of the destination. For moves
     *       (not copies) with only a source-style arg ({@code source},
     *       {@code src}, {@code from}, {@code old_path}, …) →
     *       {@link Capability.FileDelete} of the source.</li>
     *   <li>Everything else → {@link Capability.McpInvoke} (server, method)
     *       and gets the standard policy + audit treatment via the MCP
     *       method's allowlist key.</li>
     * </ul>
     *
     * <p>Conservative on both sides. False positives — a non-filesystem
     * method named {@code write_log(path=...)} would be classified as
     * {@code FileWrite} — only result in a more aggressive prompt; the user
     * can still approve. False negatives — an obscurely named file op that
     * misses the patterns — fall back to the standard MCP prompt, which is
     * the pre-fix behaviour.
     *
     * <p>Note that the audit log will record {@code @type=FileWrite} for
     * benign moves, which can surprise operators searching by capability
     * type. The {@code toolName} field on the audit entry still carries
     * {@code mcp__<server>__<method>}, so the original MCP method remains
     * traceable.
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

    /**
     * Method names with clear file-write intent (matched against lowercased
     * mcpToolName). Prefix and suffix alternations include the short
     * {@code put} form on both sides so {@code foo_put} matches just like
     * {@code put_foo} does.
     */
    private static final Pattern WRITE_VERB = Pattern.compile(
            "^(write|create|edit|append|put|save)(_.*)?$|.*_(write|create|edit|append|save|put)$");

    /**
     * Method names with clear file-delete intent. Short {@code rm} included
     * on both sides for the same reason as {@link #WRITE_VERB}.
     */
    private static final Pattern DELETE_VERB = Pattern.compile(
            "^(delete|remove|unlink|rm)(_.*)?$|.*_(delete|remove|unlink|rm)$");

    /**
     * Method names for two-arg destination-receiving ops: move/rename/copy.
     * Short {@code mv}/{@code cp} forms included on both sides.
     */
    private static final Pattern MOVE_DEST_VERB = Pattern.compile(
            "^(move|mv|rename|copy|cp)(_.*)?$|.*_(move|rename|copy|mv|cp)$");

    /**
     * Subset of {@link #MOVE_DEST_VERB} that does NOT remove the source —
     * copy/cp. Used to decide whether a sensitive source path should be
     * treated as a delete: copy doesn't delete the source, move does.
     */
    private static final Pattern COPY_VERB = Pattern.compile(
            "^(copy|cp)(_.*)?$|.*_(copy|cp)$");

    private Capability inferFileCapability(JsonNode args) {
        if (args == null || args.isNull() || !args.isObject()) return null;
        String name = mcpToolName.toLowerCase(Locale.ROOT);

        if (WRITE_VERB.matcher(name).matches()) {
            // Most single-write methods use a `path`-style field. Some
            // weirder names (`write_to_destination(...)`) use a destination-
            // style field; cascade so they're caught too.
            Path p = safePath(extractField(args, PATH_FIELDS));
            if (p == null) p = safePath(extractField(args, DESTINATION_FIELDS));
            return p == null ? null : new Capability.FileWrite(p, WriteMode.OVERWRITE);
        }
        if (DELETE_VERB.matcher(name).matches()) {
            Path p = safePath(extractField(args, PATH_FIELDS));
            if (p == null) p = safePath(extractField(args, SOURCE_FIELDS));
            return p == null ? null : new Capability.FileDelete(p);
        }
        if (MOVE_DEST_VERB.matcher(name).matches()) {
            // Two-arg op: destination is the "write" target; source is the
            // "delete" target (for moves only — copies leave the source).
            // Disambiguation order:
            //   1. dst is sensitive → FileWrite(dst). Catches "move/copy to
            //      .env" — the destination-write attack.
            //   2. src resolves AND (op is a move OR src is sensitive) →
            //      FileDelete(src). Two distinct purposes folded together:
            //        a. For MOVES (regardless of src sensitivity): the
            //           source really is removed, and FileDelete's DANGEROUS
            //           risk prompts in accept-edits where FileWrite would
            //           auto-approve. Pre-#495 MCP moves prompted via
            //           EXECUTE risk; this preserves that floor (Codex P2
            //           on #495).
            //        b. For COPIES from a sensitive source: copies don't
            //           actually delete the source, but duplicating a
            //           credentials file to a non-sensitive location IS
            //           exfiltration. Pre-#495 the same call prompted via
            //           the EXECUTE fallback; emitting FileDelete here
            //           triggers the structural denial via the sensitive
            //           source path (Codex P1 on #495 — "preserve prompts
            //           for copies from sensitive sources"). Audit log
            //           shows @type=FileDelete for a copy — slightly
            //           misleading but safer than missing the denial; the
            //           toolName field still carries
            //           mcp__<server>__copy_file so operators can correlate.
            //   3. Pure copy with non-sensitive src (dst is safe per step 1)
            //      → FileWrite(dst). Genuine WRITE risk; auto-approval in
            //      accept-edits is fine.
            Path dst = safePath(extractField(args, DESTINATION_FIELDS));
            Path src = safePath(extractField(args, SOURCE_FIELDS));
            boolean isMove = !COPY_VERB.matcher(name).matches();

            if (dst != null && DefaultPermissionPolicy.isSensitivePath(dst)) {
                return new Capability.FileWrite(dst, WriteMode.OVERWRITE);
            }
            if (src != null && (isMove || DefaultPermissionPolicy.isSensitivePath(src))) {
                return new Capability.FileDelete(src);
            }
            if (dst != null) return new Capability.FileWrite(dst, WriteMode.OVERWRITE);
        }
        return null;
    }

    /**
     * Looks up a field in {@code args} matching any of {@code keys}, tolerant
     * of casing and underscore differences. So {@code path}, {@code Path},
     * {@code file_path}, {@code filePath}, {@code FILEPATH} all match the
     * canonical entry {@code path} or {@code file_path} in the keys list.
     * Necessary because MCP servers in the wild use mixed conventions
     * (snake_case in the official filesystem server, camelCase in many
     * third-party servers — Codex P2 on #495).
     */
    private static String extractField(JsonNode args, List<String> keys) {
        // Build a normalized → original lookup over args once. Cheap (args
        // objects are small) and avoids O(keys × argFields) repeat scans.
        Map<String, String> argKeysByNormalized = new HashMap<>();
        var iter = args.fieldNames();
        while (iter.hasNext()) {
            String k = iter.next();
            argKeysByNormalized.putIfAbsent(normalizeFieldName(k), k);
        }
        for (String key : keys) {
            String original = argKeysByNormalized.get(normalizeFieldName(key));
            if (original != null) {
                var node = args.get(original);
                if (node != null && node.isTextual()) {
                    return node.asText();
                }
            }
        }
        return null;
    }

    /**
     * Folds casing + strips underscores so {@code filePath}, {@code file_path},
     * {@code FilePath}, {@code FILEPATH} all normalize to {@code filepath}.
     * Locale.ROOT for stable case-folding regardless of host locale.
     */
    private static String normalizeFieldName(String s) {
        return s.toLowerCase(Locale.ROOT).replace("_", "");
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

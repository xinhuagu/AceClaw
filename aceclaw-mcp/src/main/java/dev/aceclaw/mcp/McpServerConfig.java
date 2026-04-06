package dev.aceclaw.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses MCP server configuration from Claude Code-compatible {@code .mcp.json} files
 * and AceClaw-specific {@code mcp-servers.json} files.
 *
 * <p>Config loading order (later overrides earlier):
 * <ol>
 *   <li>{@code ~/.aceclaw/config.json} (global AceClaw config, {@code mcpServers} key)</li>
 *   <li>{@code ~/.aceclaw/mcp-servers.json} (global dedicated MCP config)</li>
 *   <li>{@code {project}/.mcp.json} (project, Claude Code compatible)</li>
 *   <li>{@code {project}/.aceclaw/config.json} (project AceClaw config, {@code mcpServers} key)</li>
 *   <li>{@code {project}/.aceclaw/mcp-servers.json} (project AceClaw-specific dedicated MCP config)</li>
 * </ol>
 *
 * <p>Supports stdio, SSE, and streamable HTTP transports. Each server can optionally
 * specify a {@code timeout} in seconds for MCP requests (default: 30s):
 * <pre>{@code
 * {
 *   "mcpServers": {
 *     "local-server": {
 *       "command": "npx",
 *       "args": ["-y", "@package/mcp-server"],
 *       "env": { "KEY": "value" },
 *       "timeout": 120
 *     },
 *     "remote-sse": {
 *       "url": "https://example.com/mcp",
 *       "headers": { "Authorization": "Bearer xxx" }
 *     },
 *     "remote-streamable": {
 *       "url": "https://example.com/mcp",
 *       "transport": "streamable-http",
 *       "headers": { "Authorization": "Bearer xxx" },
 *       "timeout": 300
 *     }
 *   }
 * }
 * }</pre>
 */
public final class McpServerConfig {

    private static final Logger log = LoggerFactory.getLogger(McpServerConfig.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Transport type for an MCP server connection.
     */
    public enum TransportType {
        STDIO, SSE, STREAMABLE_HTTP
    }

    /**
     * A single MCP server entry from configuration.
     *
     * @param command   the executable command (stdio only, e.g. "npx", "node", "python")
     * @param args      command-line arguments (stdio only)
     * @param env       environment variables for the server process (stdio only)
     * @param url       the server URL (SSE/HTTP transports)
     * @param headers   HTTP headers (SSE/HTTP transports)
     * @param transport the transport type
     * @param timeout   request timeout in seconds (null = use default 30s)
     */
    public record ServerEntry(
            String command,
            List<String> args,
            Map<String, String> env,
            String url,
            Map<String, String> headers,
            TransportType transport,
            Integer timeout
    ) {
        public ServerEntry {
            args = args != null ? List.copyOf(args) : List.of();
            env = env != null ? Map.copyOf(env) : Map.of();
            headers = headers != null ? Map.copyOf(headers) : Map.of();
            transport = transport != null ? transport : TransportType.STDIO;
        }

        /** Creates a stdio server entry. */
        public static ServerEntry stdio(String command, List<String> args, Map<String, String> env) {
            return new ServerEntry(command, args, env, null, Map.of(), TransportType.STDIO, null);
        }

        /** Creates an SSE server entry. */
        public static ServerEntry sse(String url, Map<String, String> headers) {
            return new ServerEntry(null, List.of(), Map.of(), url, headers, TransportType.SSE, null);
        }

        /** Creates a streamable HTTP server entry. */
        public static ServerEntry streamableHttp(String url, Map<String, String> headers) {
            return new ServerEntry(null, List.of(), Map.of(), url, headers, TransportType.STREAMABLE_HTTP, null);
        }
    }

    private McpServerConfig() {}

    /**
     * Loads MCP server configuration by merging all config sources.
     *
     * @param workingDir the project working directory
     * @return merged server configurations, keyed by server name
     */
    public static Map<String, ServerEntry> load(Path workingDir) {
        var result = new LinkedHashMap<String, ServerEntry>();
        var aceclawHome = Path.of(System.getProperty("user.home"), ".aceclaw");

        // 1. Global: ~/.aceclaw/config.json
        mergeFrom(result, aceclawHome.resolve("config.json"));

        // 2. Global: ~/.aceclaw/mcp-servers.json
        mergeFrom(result, aceclawHome.resolve("mcp-servers.json"));

        // 3. Project: {workingDir}/.mcp.json (Claude Code compatible)
        mergeFrom(result, workingDir.resolve(".mcp.json"));

        // 4. Project: {workingDir}/.aceclaw/config.json
        var projectAceClawDir = workingDir.resolve(".aceclaw");
        mergeFrom(result, projectAceClawDir.resolve("config.json"));

        // 5. Project: {workingDir}/.aceclaw/mcp-servers.json
        mergeFrom(result, projectAceClawDir.resolve("mcp-servers.json"));

        if (!result.isEmpty()) {
            log.info("Loaded {} MCP server(s): {}", result.size(), result.keySet());
        }

        return Collections.unmodifiableMap(result);
    }

    static void mergeFrom(Map<String, ServerEntry> target, Path configFile) {
        if (!Files.isRegularFile(configFile)) {
            return;
        }

        try {
            var root = MAPPER.readTree(Files.readString(configFile));
            var servers = root.get("mcpServers");
            if (servers == null || !servers.isObject()) {
                log.debug("No mcpServers key in {}", configFile);
                return;
            }

            var it = servers.fields();
            while (it.hasNext()) {
                var entry = it.next();
                var name = entry.getKey();
                var value = entry.getValue();

                var commandText = value.has("command") ? value.get("command").asText().trim() : "";
                var urlText = value.has("url") ? value.get("url").asText().trim() : "";
                var hasCommand = !commandText.isBlank();
                var hasUrl = !urlText.isBlank();

                if (!hasCommand && !hasUrl) {
                    log.warn("MCP server '{}' in {} has no command or url; skipping", name, configFile);
                    continue;
                }

                if (hasCommand && hasUrl) {
                    log.warn("MCP server '{}' in {} has both command and url; preferring url-based transport",
                            name, configFile);
                }

                // Parse optional timeout (seconds)
                Integer timeout = null;
                if (value.has("timeout") && value.get("timeout").isNumber()) {
                    timeout = value.get("timeout").asInt();
                }

                if (hasUrl) {
                    // Remote transport: SSE or streamable HTTP
                    var url = urlText;
                    var headers = parseStringMap(value, "headers");
                    var transportStr = value.has("transport") ? value.get("transport").asText().trim() : "";

                    if ("streamable-http".equalsIgnoreCase(transportStr)) {
                        target.put(name, new ServerEntry(null, List.of(), Map.of(), url, headers,
                                TransportType.STREAMABLE_HTTP, timeout));
                    } else {
                        target.put(name, new ServerEntry(null, List.of(), Map.of(), url, headers,
                                TransportType.SSE, timeout));
                    }
                    log.debug("Loaded MCP server '{}' from {}: {} ({})", name, configFile, url,
                            target.get(name).transport());
                } else {
                    // Stdio transport
                    var command = commandText;
                    var args = new java.util.ArrayList<String>();
                    if (value.has("args") && value.get("args").isArray()) {
                        for (var arg : value.get("args")) {
                            args.add(arg.asText());
                        }
                    }
                    var env = parseStringMap(value, "env");
                    target.put(name, new ServerEntry(command, args, env, null, Map.of(),
                            TransportType.STDIO, timeout));
                    log.debug("Loaded MCP server '{}' from {}: {} {}", name, configFile, command, args);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to parse MCP config {}: {}", configFile, e.getMessage());
        }
    }

    private static Map<String, String> parseStringMap(com.fasterxml.jackson.databind.JsonNode parent, String fieldName) {
        var result = new LinkedHashMap<String, String>();
        if (parent.has(fieldName) && parent.get(fieldName).isObject()) {
            var it = parent.get(fieldName).fields();
            while (it.hasNext()) {
                var e = it.next();
                result.put(e.getKey(), e.getValue().asText());
            }
        }
        return result;
    }
}

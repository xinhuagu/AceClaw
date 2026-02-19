package dev.aceclaw.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.agent.Tool;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages the lifecycle of MCP server connections (stdio, SSE, and streamable HTTP).
 *
 * <p>For each configured server, creates the appropriate transport, initializes the MCP
 * protocol handshake, discovers available tools, and creates {@link McpToolBridge} adapters
 * for registration in AceClaw's {@link dev.aceclaw.core.agent.ToolRegistry}.
 *
 * <p>Implements {@link AutoCloseable} for graceful shutdown of all connections.
 */
public final class McpClientManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpClientManager.class);

    private static final int MAX_START_ATTEMPTS = 3;
    private static final Duration BACKOFF_BASE = Duration.ofSeconds(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Health status of an individual MCP server.
     */
    public enum ServerStatus {
        STARTING, CONNECTED, FAILED, SHUTDOWN
    }

    private final Map<String, McpServerConfig.ServerEntry> serverConfigs;
    private final Map<String, McpSyncClient> clients = new LinkedHashMap<>();
    private final Map<String, ServerStatus> statuses = new LinkedHashMap<>();
    private final List<Tool> bridgedTools = new ArrayList<>();

    /**
     * Creates a manager for the given server configurations.
     *
     * @param serverConfigs map of server name to configuration entry
     */
    public McpClientManager(Map<String, McpServerConfig.ServerEntry> serverConfigs) {
        this.serverConfigs = serverConfigs;
    }

    /**
     * Starts all configured MCP servers, initializes connections, and discovers tools.
     *
     * <p>Each server gets up to {@value MAX_START_ATTEMPTS} start attempts with exponential backoff.
     * Failed servers are logged and skipped; the daemon still starts normally.
     */
    public void start() {
        for (var entry : serverConfigs.entrySet()) {
            var serverName = entry.getKey();
            var config = entry.getValue();
            statuses.put(serverName, ServerStatus.STARTING);

            McpSyncClient client = null;
            Exception lastError = null;

            for (int attempt = 1; attempt <= MAX_START_ATTEMPTS; attempt++) {
                try {
                    client = createAndInitialize(serverName, config);
                    break;
                } catch (Exception e) {
                    lastError = e;
                    log.warn("MCP server '{}' start attempt {}/{} failed: {}",
                            serverName, attempt, MAX_START_ATTEMPTS, e.getMessage());
                    if (attempt < MAX_START_ATTEMPTS) {
                        try {
                            Thread.sleep(BACKOFF_BASE.toMillis() * attempt);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }

            if (client != null) {
                clients.put(serverName, client);
                statuses.put(serverName, ServerStatus.CONNECTED);
                discoverAndBridgeTools(serverName, client);
            } else {
                statuses.put(serverName, ServerStatus.FAILED);
                log.error("MCP server '{}' failed to start after {} attempts: {}",
                        serverName, MAX_START_ATTEMPTS,
                        lastError != null ? lastError.getMessage() : "unknown error");
            }
        }

        log.info("MCP client manager started: {}/{} servers connected, {} tools discovered",
                clients.size(), serverConfigs.size(), bridgedTools.size());
    }

    /**
     * Returns all bridged MCP tools adapted to the AceClaw {@link Tool} interface.
     */
    public List<Tool> bridgedTools() {
        return Collections.unmodifiableList(bridgedTools);
    }

    /**
     * Returns the health status of each configured server, refreshed via ping.
     */
    public Map<String, ServerStatus> serverStatus() {
        // Refresh status of all clients with an entry (including FAILED — they may have recovered)
        for (var entry : clients.entrySet()) {
            var serverName = entry.getKey();
            try {
                entry.getValue().ping();
                statuses.put(serverName, ServerStatus.CONNECTED);
            } catch (Exception e) {
                log.warn("MCP server '{}' ping failed: {}", serverName, e.getMessage());
                statuses.put(serverName, ServerStatus.FAILED);
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(statuses));
    }

    /**
     * Pings all connected servers and returns a map of server name to success.
     *
     * @return map of server name to ping success (true = healthy)
     */
    public Map<String, Boolean> ping() {
        var results = new LinkedHashMap<String, Boolean>();
        for (var entry : clients.entrySet()) {
            var serverName = entry.getKey();
            try {
                entry.getValue().ping();
                results.put(serverName, true);
                statuses.put(serverName, ServerStatus.CONNECTED);
            } catch (Exception e) {
                log.warn("MCP server '{}' ping failed: {}", serverName, e.getMessage());
                results.put(serverName, false);
                statuses.put(serverName, ServerStatus.FAILED);
            }
        }
        return results;
    }

    /**
     * Reconnects to a specific MCP server by name.
     * Closes any existing connection, re-creates the client, and re-discovers tools.
     *
     * @param serverName the name of the server to reconnect
     * @return true if reconnection succeeded
     */
    public boolean reconnect(String serverName) {
        var config = serverConfigs.get(serverName);
        if (config == null) {
            log.warn("Cannot reconnect unknown MCP server '{}'", serverName);
            return false;
        }

        // Close existing client
        var existing = clients.remove(serverName);
        if (existing != null) {
            try {
                existing.closeGracefully();
            } catch (Exception e) {
                log.debug("Error closing existing connection for '{}': {}", serverName, e.getMessage());
            }
        }

        // Remove old bridged tools for this server
        bridgedTools.removeIf(t -> t.name().startsWith("mcp__" + serverName + "__"));

        // Reconnect
        try {
            var client = createAndInitialize(serverName, config);
            clients.put(serverName, client);
            statuses.put(serverName, ServerStatus.CONNECTED);
            discoverAndBridgeTools(serverName, client);
            log.info("MCP server '{}' reconnected successfully", serverName);
            return true;
        } catch (Exception e) {
            statuses.put(serverName, ServerStatus.FAILED);
            log.error("MCP server '{}' reconnection failed: {}", serverName, e.getMessage());
            return false;
        }
    }

    /**
     * Returns the MCP client for the given server name, or null if not connected.
     */
    public McpSyncClient client(String serverName) {
        return clients.get(serverName);
    }

    @Override
    public void close() {
        for (var entry : clients.entrySet()) {
            var serverName = entry.getKey();
            var client = entry.getValue();
            try {
                client.closeGracefully();
                statuses.put(serverName, ServerStatus.SHUTDOWN);
                log.info("MCP server '{}' shut down gracefully", serverName);
            } catch (Exception e) {
                log.warn("Error shutting down MCP server '{}': {}", serverName, e.getMessage());
            }
        }
        clients.clear();
    }

    private McpSyncClient createAndInitialize(String serverName, McpServerConfig.ServerEntry config) {
        log.info("Starting MCP server '{}' via {} transport", serverName, config.transport());

        var jsonMapper = new JacksonMcpJsonMapper(new ObjectMapper());
        McpClientTransport transport = switch (config.transport()) {
            case STDIO -> createStdioTransport(config, jsonMapper);
            case SSE -> createSseTransport(config, jsonMapper);
            case STREAMABLE_HTTP -> createStreamableHttpTransport(config, jsonMapper);
        };

        var client = McpClient.sync(transport)
                .requestTimeout(REQUEST_TIMEOUT)
                .build();

        try {
            client.initialize();
        } catch (Exception e) {
            // Clean up transport resources on initialization failure
            try {
                client.closeGracefully();
            } catch (Exception closeEx) {
                log.debug("Cleanup after failed init for '{}': {}", serverName, closeEx.getMessage());
            }
            throw e;
        }
        log.info("MCP server '{}' initialized successfully", serverName);

        return client;
    }

    private McpClientTransport createStdioTransport(McpServerConfig.ServerEntry config,
                                                     JacksonMcpJsonMapper jsonMapper) {
        var paramsBuilder = ServerParameters.builder(config.command());
        if (!config.args().isEmpty()) {
            paramsBuilder.args(config.args().toArray(new String[0]));
        }
        if (!config.env().isEmpty()) {
            paramsBuilder.env(config.env());
        }
        return new StdioClientTransport(paramsBuilder.build(), jsonMapper);
    }

    private McpClientTransport createSseTransport(McpServerConfig.ServerEntry config,
                                                   JacksonMcpJsonMapper jsonMapper) {
        @SuppressWarnings("removal")
        var builder = new HttpClientSseClientTransport.Builder(config.url())
                .jsonMapper(jsonMapper);

        if (!config.headers().isEmpty()) {
            builder.customizeRequest(reqBuilder -> applyHeaders(reqBuilder, config.headers()));
        }

        return builder.build();
    }

    private McpClientTransport createStreamableHttpTransport(McpServerConfig.ServerEntry config,
                                                              JacksonMcpJsonMapper jsonMapper) {
        var builder = HttpClientStreamableHttpTransport.builder(config.url())
                .jsonMapper(jsonMapper);

        if (!config.headers().isEmpty()) {
            builder.customizeRequest(reqBuilder -> applyHeaders(reqBuilder, config.headers()));
        }

        return builder.build();
    }

    private static void applyHeaders(HttpRequest.Builder reqBuilder, Map<String, String> headers) {
        headers.forEach(reqBuilder::setHeader);
    }

    private void discoverAndBridgeTools(String serverName, McpSyncClient client) {
        try {
            var toolsResult = client.listTools();
            var tools = toolsResult.tools();

            if (tools.isEmpty()) {
                log.info("MCP server '{}' has no tools", serverName);
                return;
            }

            for (var mcpTool : tools) {
                var bridged = McpToolBridge.create(serverName, mcpTool, client);
                bridgedTools.add(bridged);
                log.debug("Bridged MCP tool: {}", bridged.name());
            }

            log.info("MCP server '{}' provides {} tool(s): {}", serverName, tools.size(),
                    tools.stream().map(McpSchema.Tool::name).toList());
        } catch (Exception e) {
            log.warn("Failed to discover tools from MCP server '{}': {}", serverName, e.getMessage());
        }
    }
}

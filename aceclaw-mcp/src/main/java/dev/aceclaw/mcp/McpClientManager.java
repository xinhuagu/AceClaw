package dev.aceclaw.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.agent.Tool;
import dev.aceclaw.core.util.WaitSupport;
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
import java.util.Objects;
import java.util.function.Consumer;

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
    private static final Duration RECONNECT_COOLDOWN = Duration.ofSeconds(30);

    /**
     * Health status of an individual MCP server.
     */
    public enum ServerStatus {
        STARTING, CONNECTED, FAILED, SHUTDOWN
    }

    /**
     * Health snapshot for a configured server.
     *
     * @param status the current server lifecycle status
     * @param toolCount number of currently bridged tools from this server
     * @param lastError most recent connection or ping error, if any
     */
    public record ServerHealth(ServerStatus status, int toolCount, String lastError) {}

    private final Map<String, McpServerConfig.ServerEntry> serverConfigs;
    private final Map<String, McpSyncClient> clients = new LinkedHashMap<>();
    private final Map<String, ServerStatus> statuses = new LinkedHashMap<>();
    private final List<Tool> bridgedTools = new ArrayList<>();
    private final Map<String, String> lastErrors = new LinkedHashMap<>();
    private final Map<String, Long> lastReconnectAttemptMillis = new LinkedHashMap<>();
    private Consumer<List<Tool>> onServerTools;

    /**
     * Creates a manager for the given server configurations.
     *
     * @param serverConfigs map of server name to configuration entry
     */
    public McpClientManager(Map<String, McpServerConfig.ServerEntry> serverConfigs) {
        this.serverConfigs = Objects.requireNonNull(serverConfigs, "serverConfigs");
    }

    /**
     * Starts all configured MCP servers, initializes connections, and discovers tools.
     *
     * <p>Each server gets up to {@value MAX_START_ATTEMPTS} start attempts with exponential backoff.
     * Failed servers are logged and skipped; the daemon still starts normally.
     */
    public void start() {
        start(null);
    }

    /**
     * Starts all configured MCP servers with an optional per-server callback.
     *
     * <p>When a server connects and its tools are discovered, the callback is invoked immediately
     * with the newly bridged tools. This allows callers to register tools incrementally rather
     * than waiting for all servers to finish — so one slow or failing server does not block
     * tools from already-succeeded servers.
     *
     * @param onServerTools callback invoked per server with its bridged tools, or null to skip
     */
    public synchronized void start(Consumer<List<Tool>> onServerTools) {
        this.onServerTools = onServerTools;
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
                            WaitSupport.sleepInterruptibly(BACKOFF_BASE.multipliedBy(attempt));
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
                lastErrors.remove(serverName);
                bridgeServerTools(serverName, client, onServerTools);
            } else {
                statuses.put(serverName, ServerStatus.FAILED);
                if (lastError != null) {
                    lastErrors.put(serverName, lastError.getMessage());
                }
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
    public synchronized List<Tool> bridgedTools() {
        return Collections.unmodifiableList(bridgedTools);
    }

    /**
     * Returns the health status of each configured server, refreshed via ping.
     */
    public synchronized Map<String, ServerStatus> serverStatus() {
        refreshServerHealth(true);
        return Collections.unmodifiableMap(new LinkedHashMap<>(statuses));
    }

    /**
     * Returns detailed health state for every configured server.
     *
     * <p>This refreshes current connections and attempts auto-repair for failed servers with
     * reconnect cooldown applied.
     */
    public synchronized Map<String, ServerHealth> serverHealth() {
        refreshServerHealth(true);
        var result = new LinkedHashMap<String, ServerHealth>();
        for (var serverName : serverConfigs.keySet()) {
            result.put(serverName, new ServerHealth(
                    statuses.getOrDefault(serverName, ServerStatus.FAILED),
                    toolCountFor(serverName),
                    lastErrors.get(serverName)));
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Pings all connected servers and returns a map of server name to success.
     *
     * @return map of server name to ping success (true = healthy)
     */
    public synchronized Map<String, Boolean> ping() {
        var results = new LinkedHashMap<String, Boolean>();
        for (var entry : clients.entrySet()) {
            var serverName = entry.getKey();
            try {
                entry.getValue().ping();
                results.put(serverName, true);
                statuses.put(serverName, ServerStatus.CONNECTED);
                lastErrors.remove(serverName);
            } catch (Exception e) {
                log.warn("MCP server '{}' ping failed: {}", serverName, e.getMessage());
                results.put(serverName, false);
                statuses.put(serverName, ServerStatus.FAILED);
                lastErrors.put(serverName, e.getMessage());
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
    public synchronized boolean reconnect(String serverName) {
        var config = serverConfigs.get(serverName);
        if (config == null) {
            log.warn("Cannot reconnect unknown MCP server '{}'", serverName);
            return false;
        }

        lastReconnectAttemptMillis.put(serverName, System.currentTimeMillis());
        return reconnectInternal(serverName, config, onServerTools);
    }

    /**
     * Attempts reconnection only when the cooldown window has elapsed.
     *
     * @param serverName configured server name
     * @return true if reconnect ran and succeeded
     */
    public synchronized boolean reconnectIfDue(String serverName) {
        var config = serverConfigs.get(serverName);
        if (config == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        long lastAttempt = lastReconnectAttemptMillis.getOrDefault(serverName, 0L);
        if (now - lastAttempt < RECONNECT_COOLDOWN.toMillis()) {
            return false;
        }
        lastReconnectAttemptMillis.put(serverName, now);
        return reconnectInternal(serverName, config, onServerTools);
    }

    private boolean reconnectInternal(String serverName,
                                      McpServerConfig.ServerEntry config,
                                      Consumer<List<Tool>> onServerTools) {
        statuses.put(serverName, ServerStatus.STARTING);

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
            lastErrors.remove(serverName);
            bridgeServerTools(serverName, client, onServerTools);
            log.info("MCP server '{}' reconnected successfully", serverName);
            return true;
        } catch (Exception e) {
            statuses.put(serverName, ServerStatus.FAILED);
            lastErrors.put(serverName, e.getMessage());
            log.error("MCP server '{}' reconnection failed: {}", serverName, e.getMessage());
            return false;
        }
    }

    /**
     * Returns the MCP client for the given server name, or null if not connected.
     */
    public synchronized McpSyncClient client(String serverName) {
        return clients.get(serverName);
    }

    @Override
    public synchronized void close() {
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

    private void bridgeServerTools(String serverName,
                                   McpSyncClient client,
                                   Consumer<List<Tool>> onServerTools) {
        var newlyBridged = discoverAndBridgeTools(serverName, client);
        if (onServerTools != null && !newlyBridged.isEmpty()) {
            onServerTools.accept(Collections.unmodifiableList(newlyBridged));
        }
    }

    private List<Tool> discoverAndBridgeTools(String serverName, McpSyncClient client) {
        try {
            var toolsResult = client.listTools();
            var tools = toolsResult.tools();

            if (tools.isEmpty()) {
                log.info("MCP server '{}' has no tools", serverName);
                return List.of();
            }

            var bridgedForServer = new ArrayList<Tool>();
            for (var mcpTool : tools) {
                var bridged = McpToolBridge.create(serverName, mcpTool, client);
                bridgedTools.add(bridged);
                bridgedForServer.add(bridged);
                log.debug("Bridged MCP tool: {}", bridged.name());
            }

            log.info("MCP server '{}' provides {} tool(s): {}", serverName, tools.size(),
                    tools.stream().map(McpSchema.Tool::name).toList());
            return List.copyOf(bridgedForServer);
        } catch (Exception e) {
            log.warn("Failed to discover tools from MCP server '{}': {}", serverName, e.getMessage());
            lastErrors.put(serverName, e.getMessage());
            return List.of();
        }
    }

    private void refreshServerHealth(boolean autoReconnect) {
        for (var serverName : serverConfigs.keySet()) {
            var client = clients.get(serverName);
            if (client == null) {
                statuses.putIfAbsent(serverName, ServerStatus.FAILED);
                if (autoReconnect && statuses.get(serverName) == ServerStatus.FAILED) {
                    reconnectIfDue(serverName);
                }
                continue;
            }

            try {
                client.ping();
                statuses.put(serverName, ServerStatus.CONNECTED);
                lastErrors.remove(serverName);
            } catch (Exception e) {
                log.warn("MCP server '{}' ping failed: {}", serverName, e.getMessage());
                statuses.put(serverName, ServerStatus.FAILED);
                lastErrors.put(serverName, e.getMessage());
                if (autoReconnect) {
                    reconnectIfDue(serverName);
                }
            }
        }
    }

    private int toolCountFor(String serverName) {
        String prefix = "mcp__" + serverName + "__";
        int count = 0;
        for (var tool : bridgedTools) {
            if (tool.name().startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }
}

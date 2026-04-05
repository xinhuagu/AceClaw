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
    private static final Duration BACKGROUND_REFRESH_INTERVAL = Duration.ofSeconds(15);

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

    /**
     * Factory that creates and initializes an MCP client for a given server.
     * Package-private for testability.
     */
    @FunctionalInterface
    interface ClientFactory {
        McpSyncClient create(String serverName, McpServerConfig.ServerEntry config);
    }

    private final Map<String, McpServerConfig.ServerEntry> serverConfigs;
    private final ClientFactory clientFactory;
    private final Map<String, McpSyncClient> clients = new LinkedHashMap<>();
    private final Map<String, ServerStatus> statuses = new LinkedHashMap<>();
    private final List<Tool> bridgedTools = new ArrayList<>();
    private final Map<String, String> lastErrors = new LinkedHashMap<>();
    private final Map<String, Long> lastReconnectAttemptMillis = new LinkedHashMap<>();
    private Consumer<List<Tool>> onServerTools;
    private Consumer<String> onToolRemoved;
    private volatile boolean closed;
    private volatile Thread refreshThread;

    /**
     * Creates a manager for the given server configurations.
     *
     * @param serverConfigs map of server name to configuration entry
     */
    public McpClientManager(Map<String, McpServerConfig.ServerEntry> serverConfigs) {
        this(serverConfigs, null);
    }

    /**
     * Package-private constructor for testing with a custom client factory.
     */
    McpClientManager(Map<String, McpServerConfig.ServerEntry> serverConfigs,
                     ClientFactory clientFactory) {
        this.serverConfigs = Objects.requireNonNull(serverConfigs, "serverConfigs");
        this.clientFactory = clientFactory;
    }

    /**
     * Sets an optional callback invoked when a tool is removed during reconnect.
     *
     * @param onToolRemoved callback receiving the removed tool name, or null to skip
     */
    public void setOnToolRemoved(Consumer<String> onToolRemoved) {
        this.onToolRemoved = onToolRemoved;
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
                lastReconnectAttemptMillis.put(serverName, System.currentTimeMillis());
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

        // Start background health refresh thread for non-blocking status queries.
        // I/O (ping, reconnect) runs outside the monitor so serverHealth() never blocks.
        refreshThread = Thread.ofVirtual().name("mcp-health-refresh").start(() -> {
            while (!closed) {
                try {
                    Thread.sleep(BACKGROUND_REFRESH_INTERVAL.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (closed) break;
                try {
                    refreshServerHealthInBackground();
                } catch (Exception e) {
                    log.debug("Background MCP health refresh failed: {}", e.getMessage());
                }
            }
        });
    }

    /**
     * Returns all bridged MCP tools adapted to the AceClaw {@link Tool} interface.
     */
    public synchronized List<Tool> bridgedTools() {
        return List.copyOf(bridgedTools);
    }

    /**
     * Returns the cached health status of each configured server.
     *
     * <p>Statuses are refreshed periodically by a background thread started in {@link #start}.
     */
    public synchronized Map<String, ServerStatus> serverStatus() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(statuses));
    }

    /**
     * Returns cached detailed health state for every configured server.
     *
     * <p>A background thread periodically pings servers and attempts auto-repair for failed
     * servers with reconnect cooldown. This method returns the most recent cached snapshot
     * without performing any blocking I/O.
     */
    public synchronized Map<String, ServerHealth> serverHealth() {
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

        // Remove old bridged tools for this server and notify callback
        String prefix = "mcp__" + serverName + "__";
        var removed = bridgedTools.stream()
                .filter(t -> t.name().startsWith(prefix))
                .map(Tool::name)
                .toList();
        bridgedTools.removeIf(t -> t.name().startsWith(prefix));
        var removalCallback = this.onToolRemoved;
        if (removalCallback != null) {
            for (var name : removed) {
                removalCallback.accept(name);
            }
        }

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
        closed = true;
        var rt = refreshThread;
        if (rt != null) {
            rt.interrupt();
        }
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

        // Unregister all bridged tools so the daemon ToolRegistry stays in sync
        var removalCallback = this.onToolRemoved;
        if (removalCallback != null) {
            for (var tool : bridgedTools) {
                removalCallback.accept(tool.name());
            }
        }
        bridgedTools.clear();
    }

    private McpSyncClient createAndInitialize(String serverName, McpServerConfig.ServerEntry config) {
        if (clientFactory != null) {
            return clientFactory.create(serverName, config);
        }
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

    /**
     * Bridges tools from a connected server. On discovery failure, marks the server FAILED
     * so it is not reported as healthy with zero tools.
     *
     * @return true if tool discovery succeeded (even if zero tools), false on failure
     */
    private boolean bridgeServerTools(String serverName,
                                      McpSyncClient client,
                                      Consumer<List<Tool>> onServerTools) {
        try {
            var newlyBridged = discoverAndBridgeTools(serverName, client);
            if (onServerTools != null && !newlyBridged.isEmpty()) {
                onServerTools.accept(Collections.unmodifiableList(newlyBridged));
            }
            return true;
        } catch (Exception e) {
            log.warn("Failed to discover tools from MCP server '{}': {}", serverName, e.getMessage());
            statuses.put(serverName, ServerStatus.FAILED);
            lastErrors.put(serverName, e.getMessage());
            return false;
        }
    }

    /**
     * Discovers and bridges tools from a connected MCP server.
     *
     * @throws RuntimeException if tool discovery fails — caller must handle failure
     */
    private List<Tool> discoverAndBridgeTools(String serverName, McpSyncClient client) {
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
    }

    /**
     * Background-safe health refresh: takes a snapshot under lock, does I/O outside the
     * monitor, then publishes results in a short synchronized section. This ensures
     * serverHealth()/serverStatus() never block on slow pings or reconnects.
     */
    private void refreshServerHealthInBackground() {
        // 1. Snapshot under lock
        Map<String, McpSyncClient> clientSnapshot;
        Map<String, ServerStatus> statusSnapshot;
        synchronized (this) {
            clientSnapshot = new LinkedHashMap<>(clients);
            statusSnapshot = new LinkedHashMap<>(statuses);
        }

        // 2. Ping outside the monitor — no lock held during I/O
        var pingResults = new LinkedHashMap<String, Boolean>();
        var pingErrors = new LinkedHashMap<String, String>();
        for (var serverName : serverConfigs.keySet()) {
            var client = clientSnapshot.get(serverName);
            if (client == null) {
                pingResults.put(serverName, false);
                continue;
            }
            try {
                client.ping();
                pingResults.put(serverName, true);
            } catch (Exception e) {
                log.warn("MCP server '{}' ping failed: {}", serverName, e.getMessage());
                pingResults.put(serverName, false);
                pingErrors.put(serverName, e.getMessage());
            }
        }

        // 3. Publish results + trigger reconnects under lock
        synchronized (this) {
            for (var serverName : serverConfigs.keySet()) {
                Boolean ok = pingResults.get(serverName);
                if (ok != null && ok) {
                    statuses.put(serverName, ServerStatus.CONNECTED);
                    lastErrors.remove(serverName);
                } else if (clientSnapshot.containsKey(serverName)) {
                    // Had a client but ping failed
                    statuses.put(serverName, ServerStatus.FAILED);
                    var err = pingErrors.get(serverName);
                    if (err != null) {
                        lastErrors.put(serverName, err);
                    }
                    reconnectIfDue(serverName);
                } else {
                    // No client — was already failed
                    statuses.putIfAbsent(serverName, ServerStatus.FAILED);
                    if (statuses.get(serverName) == ServerStatus.FAILED) {
                        reconnectIfDue(serverName);
                    }
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

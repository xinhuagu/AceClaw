package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.aceclaw.infra.health.HealthMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Routes JSON-RPC 2.0 requests to the appropriate handler methods.
 *
 * <p>MVP methods: session.create, session.destroy, session.list,
 * agent.prompt, health.status, admin.shutdown.
 */
public final class RequestRouter {

    private static final Logger log = LoggerFactory.getLogger(RequestRouter.class);

    /**
     * A handler for a JSON-RPC method.
     */
    @FunctionalInterface
    public interface MethodHandler {
        Object handle(JsonNode params) throws Exception;
    }

    /**
     * A handler for a streaming JSON-RPC method that needs bidirectional I/O.
     *
     * <p>Streaming handlers receive a {@link StreamContext} so they can send
     * intermediate notifications (e.g. text deltas, permission requests) and
     * read client responses (e.g. permission decisions) during processing.
     */
    @FunctionalInterface
    public interface StreamingMethodHandler {
        Object handle(JsonNode params, StreamContext context) throws Exception;
    }

    /** Application-level error code: workspace already has an active TUI attachment. */
    public static final int WORKSPACE_CONFLICT = -32001;

    private final Map<String, MethodHandler> handlers = new ConcurrentHashMap<>();
    private final Map<String, StreamingMethodHandler> streamingHandlers = new ConcurrentHashMap<>();
    private final SessionManager sessionManager;
    private final WorkspaceAttachmentRegistry attachmentRegistry;
    private final ObjectMapper objectMapper;
    private volatile Runnable shutdownCallback;

    private volatile String modelName;
    private volatile String providerName;
    private volatile String activeProfile;
    private volatile int contextWindowTokens;
    private volatile HealthMonitor healthMonitor;
    private volatile Supplier<JsonNode> mcpStatusSupplier;
    private volatile DashboardInfo dashboardInfo;

    /**
     * Snapshot of where the bundled browser dashboard (issue #446) is reachable.
     * Reported via {@code health.status} so the {@code aceclaw dashboard} CLI
     * subcommand can ask the daemon "where do I open the browser?" instead of
     * hard-coding 3141 — covers the case where the user overrode the port in
     * {@code config.json} or the bridge fell back to an ephemeral one.
     *
     * @param enabled whether the WebSocket bridge / static-files handler is up
     * @param url     fully-formed URL the user can open (e.g.
     *                {@code http://localhost:3141}); empty when disabled
     * @param bundled whether {@code index.html} was found on the daemon's
     *                classpath. False in {@code -Pno-dashboard} builds — the
     *                CLI should refuse to open the browser with a message
     *                explaining how to rebuild.
     */
    public record DashboardInfo(boolean enabled, String url, boolean bundled) {
        public DashboardInfo {
            url = url != null ? url : "";
        }
    }

    public RequestRouter(SessionManager sessionManager, ObjectMapper objectMapper) {
        this(sessionManager, new WorkspaceAttachmentRegistry(), objectMapper);
    }

    public RequestRouter(SessionManager sessionManager,
                         WorkspaceAttachmentRegistry attachmentRegistry,
                         ObjectMapper objectMapper) {
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager");
        this.attachmentRegistry = Objects.requireNonNull(attachmentRegistry, "attachmentRegistry");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        registerBuiltinHandlers();
    }

    /**
     * Returns the workspace attachment registry for external access (e.g., daemon shutdown).
     */
    public WorkspaceAttachmentRegistry attachmentRegistry() {
        return attachmentRegistry;
    }

    /**
     * Sets the model name reported by the health status endpoint.
     *
     * @param modelName the LLM model identifier (e.g., "claude-sonnet-4-5-20250514")
     */
    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    /**
     * Sets additional metadata reported by the health status endpoint.
     */
    public void setProviderInfo(String provider, int contextWindowTokens) {
        this.providerName = provider;
        this.contextWindowTokens = contextWindowTokens;
    }

    /**
     * Sets the active config profile name reported by the health status endpoint.
     * May be null when no profile is applied.
     */
    public void setActiveProfile(String activeProfile) {
        this.activeProfile = activeProfile;
    }

    /**
     * Sets the health monitor for per-component health reporting.
     */
    public void setHealthMonitor(HealthMonitor healthMonitor) {
        this.healthMonitor = healthMonitor;
    }

    /**
     * Sets an optional MCP status supplier to enrich the health status endpoint.
     */
    public void setMcpStatusSupplier(Supplier<JsonNode> mcpStatusSupplier) {
        this.mcpStatusSupplier = mcpStatusSupplier;
    }

    /**
     * Sets the dashboard / WebSocket bridge info reported by {@code health.status}.
     * Pass {@code null} to clear (the default is null = field omitted from response).
     */
    public void setDashboardInfo(DashboardInfo info) {
        this.dashboardInfo = info;
    }

    /**
     * Registers a custom method handler.
     */
    public void register(String method, MethodHandler handler) {
        handlers.put(method, handler);
    }

    /**
     * Registers a streaming method handler that requires bidirectional I/O.
     *
     * @param method  the JSON-RPC method name
     * @param handler the streaming handler
     */
    public void registerStreaming(String method, StreamingMethodHandler handler) {
        streamingHandlers.put(method, handler);
    }

    /**
     * Returns whether the given method is registered as a streaming handler.
     *
     * @param method the JSON-RPC method name
     * @return true if a streaming handler is registered for this method
     */
    public boolean isStreamingMethod(String method) {
        return streamingHandlers.containsKey(method);
    }

    /**
     * Sets the callback to invoke when admin.shutdown is called.
     */
    public void setShutdownCallback(Runnable shutdownCallback) {
        this.shutdownCallback = shutdownCallback;
    }

    /**
     * Routes a JSON-RPC request and returns the response object.
     *
     * <p>For non-streaming handlers only. Streaming methods should be
     * dispatched via {@link #routeStreaming(JsonRpc.Request, StreamContext)}.
     *
     * @param request the parsed JSON-RPC request
     * @return a {@link JsonRpc.Response} or {@link JsonRpc.ErrorResponse}
     */
    public Object route(JsonRpc.Request request) {
        if (request.method() == null || request.method().isBlank()) {
            return JsonRpc.ErrorResponse.of(
                    request.id(), JsonRpc.INVALID_REQUEST, "Missing method");
        }

        var handler = handlers.get(request.method());
        if (handler == null) {
            // Check streaming handlers too so we get a meaningful error
            if (!streamingHandlers.containsKey(request.method())) {
                log.warn("Unknown method: {}", request.method());
                return JsonRpc.ErrorResponse.of(
                        request.id(), JsonRpc.METHOD_NOT_FOUND,
                        "Method not found: " + request.method());
            }
            // Streaming method called without context; this is a programming error on the bridge side
            log.error("Streaming method {} called without StreamContext", request.method());
            return JsonRpc.ErrorResponse.of(
                    request.id(), JsonRpc.INTERNAL_ERROR,
                    "Method requires streaming context: " + request.method());
        }

        try {
            var result = handler.handle(request.params());
            if (request.isNotification()) {
                return null; // No response for notifications
            }
            return JsonRpc.Response.success(request.id(), result);
        } catch (WorkspaceConflictException e) {
            return JsonRpc.ErrorResponse.of(
                    request.id(), WORKSPACE_CONFLICT, e.getMessage(), e.errorData());
        } catch (IllegalArgumentException e) {
            return JsonRpc.ErrorResponse.of(
                    request.id(), JsonRpc.INVALID_PARAMS, e.getMessage());
        } catch (Exception e) {
            log.error("Error handling method {}: {}", request.method(), e.getMessage(), e);
            return JsonRpc.ErrorResponse.of(
                    request.id(), JsonRpc.INTERNAL_ERROR, e.getMessage());
        }
    }

    /**
     * Routes a JSON-RPC request to a streaming handler with bidirectional I/O.
     *
     * <p>Called by {@link ConnectionBridge} when a request targets a streaming method.
     * The handler can send intermediate notifications and read client messages via
     * the provided {@link StreamContext}.
     *
     * @param request the parsed JSON-RPC request
     * @param context the streaming I/O context
     * @return a {@link JsonRpc.Response} or {@link JsonRpc.ErrorResponse}
     */
    public Object routeStreaming(JsonRpc.Request request, StreamContext context) {
        if (request.method() == null || request.method().isBlank()) {
            return JsonRpc.ErrorResponse.of(
                    request.id(), JsonRpc.INVALID_REQUEST, "Missing method");
        }

        var handler = streamingHandlers.get(request.method());
        if (handler == null) {
            log.warn("No streaming handler for method: {}", request.method());
            return JsonRpc.ErrorResponse.of(
                    request.id(), JsonRpc.METHOD_NOT_FOUND,
                    "No streaming handler for: " + request.method());
        }

        try {
            var result = handler.handle(request.params(), context);
            if (request.isNotification()) {
                return null;
            }
            return JsonRpc.Response.success(request.id(), result);
        } catch (IllegalArgumentException e) {
            return JsonRpc.ErrorResponse.of(
                    request.id(), JsonRpc.INVALID_PARAMS, e.getMessage());
        } catch (Exception e) {
            log.error("Error in streaming handler {}: {}", request.method(), e.getMessage(), e);
            return JsonRpc.ErrorResponse.of(
                    request.id(), JsonRpc.INTERNAL_ERROR, e.getMessage());
        }
    }

    private void registerBuiltinHandlers() {
        handlers.put("session.create", this::handleSessionCreate);
        handlers.put("session.destroy", this::handleSessionDestroy);
        handlers.put("session.list", this::handleSessionList);
        handlers.put("workspace.heartbeat", this::handleWorkspaceHeartbeat);
        handlers.put("workspace.release", this::handleWorkspaceRelease);
        handlers.put("health.status", this::handleHealthStatus);
        handlers.put("admin.shutdown", this::handleAdminShutdown);
    }

    private Object handleSessionCreate(JsonNode params) {
        var projectPath = requireString(params, "project");
        if (projectPath.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing required parameter: project");
        }
        var clientInstanceId = extractString(params, "clientInstanceId", "cli-default");
        boolean interactive = params != null
                && params.has("interactive")
                && params.get("interactive").asBoolean(true);

        var canonicalProjectPath = canonicalizeProjectPath(projectPath);
        var session = sessionManager.createSession(canonicalProjectPath);

        // If this is an interactive TUI session, acquire workspace attachment
        if (interactive) {
            var acquireResult = attachmentRegistry.acquire(
                    canonicalProjectPath, session.id(), clientInstanceId);
            if (acquireResult instanceof WorkspaceAttachmentRegistry.AcquireResult.Conflict conflict) {
                // Roll back the session we just created
                sessionManager.destroySession(session.id());

                var errorData = objectMapper.createObjectNode();
                errorData.put("existingSessionId", conflict.existing().sessionId());
                errorData.put("existingClientInstanceId", conflict.existing().clientInstanceId());
                errorData.put("workspace", conflict.workspace().toString());
                errorData.put("attachedAt", conflict.existing().attachedAt().toString());
                throw new WorkspaceConflictException(
                        "Workspace already has an active TUI session: "
                                + conflict.existing().sessionId(),
                        errorData);
            }
        }

        var result = objectMapper.createObjectNode();
        result.put("sessionId", session.id());
        result.put("project", session.projectPath().toString());
        result.put("createdAt", session.createdAt().toString());
        return result;
    }

    /**
     * Exception indicating a workspace attachment conflict.
     * Carries structured error data for the client.
     */
    static final class WorkspaceConflictException extends RuntimeException {
        private final ObjectNode errorData;

        WorkspaceConflictException(String message, ObjectNode errorData) {
            super(message);
            this.errorData = errorData;
        }

        ObjectNode errorData() {
            return errorData;
        }
    }

    private static Path canonicalizeProjectPath(String projectPath) {
        try {
            var candidate = Paths.get(projectPath).toAbsolutePath().normalize();
            if (java.nio.file.Files.exists(candidate)) {
                return candidate.toRealPath();
            }
            return candidate;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid project path: " + projectPath);
        }
    }

    private Object handleSessionDestroy(JsonNode params) {
        var sessionId = requireString(params, "sessionId");

        // Release workspace attachment if this session holds one
        var session = sessionManager.getSession(sessionId);
        if (session != null) {
            attachmentRegistry.release(session.projectPath(), sessionId);
        }

        boolean destroyed = sessionManager.destroySession(sessionId);

        var result = objectMapper.createObjectNode();
        result.put("destroyed", destroyed);
        return result;
    }

    private Object handleWorkspaceHeartbeat(JsonNode params) {
        var sessionId = requireString(params, "sessionId");
        var projectPath = requireString(params, "workspace");
        var canonicalPath = canonicalizeProjectPath(projectPath);

        boolean accepted = attachmentRegistry.heartbeat(canonicalPath, sessionId);
        var result = objectMapper.createObjectNode();
        result.put("accepted", accepted);
        return result;
    }

    private Object handleWorkspaceRelease(JsonNode params) {
        var sessionId = requireString(params, "sessionId");
        var projectPath = requireString(params, "workspace");
        var canonicalPath = canonicalizeProjectPath(projectPath);

        boolean released = attachmentRegistry.release(canonicalPath, sessionId);
        var result = objectMapper.createObjectNode();
        result.put("released", released);
        return result;
    }

    private Object handleSessionList(JsonNode params) {
        var sessions = sessionManager.activeSessions();
        var array = objectMapper.createArrayNode();
        for (var session : sessions) {
            var node = objectMapper.createObjectNode();
            node.put("sessionId", session.id());
            node.put("project", session.projectPath().toString());
            node.put("createdAt", session.createdAt().toString());
            node.put("active", session.isActive());
            node.put("messageCount", session.messages().size());
            array.add(node);
        }
        return array;
    }

    private Object handleHealthStatus(JsonNode params) {
        var result = objectMapper.createObjectNode();

        var monitor = this.healthMonitor;
        if (monitor != null) {
            var snapshot = monitor.snapshot();
            result.put("status", snapshot.status().name().toLowerCase());

            // Per-component health breakdown
            if (!snapshot.components().isEmpty()) {
                var components = objectMapper.createObjectNode();
                for (var entry : snapshot.components().entrySet()) {
                    var comp = objectMapper.createObjectNode();
                    comp.put("status", entry.getValue().status().name().toLowerCase());
                    if (!entry.getValue().detail().isEmpty()) {
                        comp.put("detail", entry.getValue().detail());
                    }
                    comp.put("timestamp", entry.getValue().timestamp().toString());
                    components.set(entry.getKey(), comp);
                }
                result.set("components", components);
            }
        } else {
            result.put("status", "healthy");
        }

        result.put("activeSessions", sessionManager.sessionCount());
        result.put("activeAttachments", attachmentRegistry.activeCount());
        result.put("timestamp", Instant.now().toString());
        result.put("version", dev.aceclaw.core.BuildVersion.version());
        var m = modelName;
        if (m != null) {
            result.put("model", m);
        }
        var p = providerName;
        if (p != null) {
            result.put("provider", p);
        }
        var profile = activeProfile;
        if (profile != null && !profile.isBlank()) {
            result.put("profile", profile);
        }
        if (contextWindowTokens > 0) {
            result.put("contextWindowTokens", contextWindowTokens);
        }
        var mcpSupplier = this.mcpStatusSupplier;
        if (mcpSupplier != null) {
            try {
                var mcp = mcpSupplier.get();
                if (mcp != null) {
                    result.set("mcp", mcp);
                }
            } catch (Exception e) {
                log.debug("Failed to resolve MCP status: {}", e.getMessage());
            }
        }
        var dash = this.dashboardInfo;
        if (dash != null) {
            var dashNode = objectMapper.createObjectNode();
            dashNode.put("enabled", dash.enabled());
            dashNode.put("bundled", dash.bundled());
            if (!dash.url().isEmpty()) {
                dashNode.put("url", dash.url());
            }
            result.set("dashboard", dashNode);
        }
        return result;
    }

    private Object handleAdminShutdown(JsonNode params) {
        log.info("Shutdown requested via JSON-RPC");
        var callback = this.shutdownCallback;
        if (callback != null) {
            Thread.ofVirtual().name("aceclaw-shutdown-trigger").start(callback);
        }
        var result = objectMapper.createObjectNode();
        result.put("acknowledged", true);
        return result;
    }

    // --- Parameter extraction helpers ---

    private static String requireString(JsonNode params, String field) {
        if (params == null || !params.has(field) || params.get(field).isNull()) {
            throw new IllegalArgumentException("Missing required parameter: " + field);
        }
        return params.get(field).asText();
    }

    private static String extractString(JsonNode params, String field, String defaultValue) {
        if (params == null || !params.has(field) || params.get(field).isNull()) {
            return defaultValue;
        }
        return params.get(field).asText();
    }
}

package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    private final Map<String, MethodHandler> handlers = new ConcurrentHashMap<>();
    private final Map<String, StreamingMethodHandler> streamingHandlers = new ConcurrentHashMap<>();
    private final SessionManager sessionManager;
    private final ObjectMapper objectMapper;
    private volatile Runnable shutdownCallback;

    public RequestRouter(SessionManager sessionManager, ObjectMapper objectMapper) {
        this.sessionManager = sessionManager;
        this.objectMapper = objectMapper;
        registerBuiltinHandlers();
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
        handlers.put("health.status", this::handleHealthStatus);
        handlers.put("admin.shutdown", this::handleAdminShutdown);
    }

    private Object handleSessionCreate(JsonNode params) {
        var projectPath = extractString(params, "project", System.getProperty("user.dir"));
        var session = sessionManager.createSession(Path.of(projectPath));

        var result = objectMapper.createObjectNode();
        result.put("sessionId", session.id());
        result.put("project", session.projectPath().toString());
        result.put("createdAt", session.createdAt().toString());
        return result;
    }

    private Object handleSessionDestroy(JsonNode params) {
        var sessionId = requireString(params, "sessionId");
        boolean destroyed = sessionManager.destroySession(sessionId);

        var result = objectMapper.createObjectNode();
        result.put("destroyed", destroyed);
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
        result.put("status", "healthy");
        result.put("activeSessions", sessionManager.sessionCount());
        result.put("timestamp", Instant.now().toString());
        result.put("version", "0.1.0-SNAPSHOT");
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

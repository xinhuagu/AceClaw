package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket bridge that fans out daemon JSON-RPC notifications to browser
 * clients (issue #431, epic #430).
 *
 * <p>Lifecycle:
 * <pre>
 *   bridge = new WebSocketBridge(host, port, mapper);
 *   bridge.start();        // opens ws://{host}:{port}/ws
 *   ...
 *   bridge.broadcast("stream.text", paramsJson);   // → all connected clients
 *   ...
 *   bridge.stop();
 * </pre>
 *
 * <p>The bridge is intentionally one-way fan-out for Tier 1: every
 * {@link #broadcast(String, Object)} call serialises a JSON-RPC notification
 * and sends it to every connected {@link WsContext}. Inbound messages from
 * browsers (e.g. {@code permission.response}) are accepted and parked on
 * {@link #setInboundHandler(InboundHandler)} so issue #433 can wire them
 * into the permission flow without touching this class again.
 *
 * <p>Security: always binds to {@code localhost} by default. Acceptance
 * criterion from #431.
 */
public final class WebSocketBridge {

    private static final Logger log = LoggerFactory.getLogger(WebSocketBridge.class);

    private final String host;
    private final int port;
    private final ObjectMapper objectMapper;
    private final Set<WsContext> clients = ConcurrentHashMap.newKeySet();

    private volatile Javalin app;
    private volatile InboundHandler inboundHandler = (ctx, message) -> { /* default: drop */ };

    public WebSocketBridge(String host, int port, ObjectMapper objectMapper) {
        this.host = Objects.requireNonNull(host, "host");
        this.port = port;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Starts the embedded Javalin server. Idempotent.
     */
    public synchronized void start() {
        if (app != null) {
            return;
        }
        var instance = Javalin.create(cfg -> {
            cfg.showJavalinBanner = false;
            // Bind only to the configured host (localhost by default).
            cfg.jetty.defaultHost = host;
        }).ws("/ws", ws -> {
            ws.onConnect(ctx -> {
                clients.add(ctx);
                log.info("WS client connected: {} (total={})", ctx.sessionId(), clients.size());
            });
            ws.onClose(ctx -> {
                clients.remove(ctx);
                log.info("WS client closed: {} (total={})", ctx.sessionId(), clients.size());
            });
            ws.onError(ctx -> {
                clients.remove(ctx);
                Throwable err = ctx.error();
                log.warn("WS client error: {} ({})", ctx.sessionId(),
                        err != null ? err.getMessage() : "unknown");
            });
            ws.onMessage(ctx -> {
                try {
                    var node = objectMapper.readTree(ctx.message());
                    inboundHandler.handle(ctx, node);
                } catch (Exception e) {
                    log.warn("WS inbound parse failed: {}", e.getMessage());
                }
            });
        });
        instance.start(port);
        this.app = instance;
        log.info("WebSocket bridge listening on ws://{}:{}/ws", host, port);
    }

    /**
     * Stops the embedded server. Idempotent.
     */
    public synchronized void stop() {
        var instance = app;
        if (instance == null) {
            return;
        }
        try {
            instance.stop();
        } catch (Exception e) {
            log.warn("WebSocket bridge stop failed: {}", e.getMessage());
        } finally {
            clients.clear();
            app = null;
            log.info("WebSocket bridge stopped");
        }
    }

    /**
     * Broadcasts a JSON-RPC 2.0 notification to all connected clients.
     *
     * <p>Failed sends to a single client are logged and that client is dropped;
     * a slow/dead browser tab cannot block notifications to others.
     *
     * @param method JSON-RPC method (e.g. {@code "stream.text"})
     * @param params notification parameters; serialised via Jackson
     */
    public void broadcast(String method, Object params) {
        if (clients.isEmpty() || app == null) {
            return;
        }
        String message;
        try {
            var envelope = objectMapper.createObjectNode();
            envelope.put("jsonrpc", "2.0");
            envelope.put("method", method);
            envelope.set("params", objectMapper.valueToTree(params));
            message = objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            log.warn("Failed to serialise WS broadcast for {}: {}", method, e.getMessage());
            return;
        }
        for (var client : clients) {
            try {
                client.send(message);
            } catch (Exception e) {
                log.warn("WS send failed for {}: {}", client.sessionId(), e.getMessage());
                clients.remove(client);
            }
        }
    }

    /**
     * Installs a handler for inbound client messages (e.g. permission.response).
     * Default handler drops messages so issue #431 stays one-way; #433 will
     * register a real handler that routes into the permission flow.
     */
    public void setInboundHandler(InboundHandler handler) {
        this.inboundHandler = handler != null ? handler : (ctx, message) -> { };
    }

    /** Returns the number of currently connected browser clients. */
    public int clientCount() {
        return clients.size();
    }

    /** Returns true once {@link #start()} has bound the listener. */
    public boolean isRunning() {
        return app != null;
    }

    /** Returns the port the bridge was configured to bind to. */
    public int port() {
        return port;
    }

    /** Returns the host the bridge was configured to bind to. */
    public String host() {
        return host;
    }

    /**
     * Inbound message callback. Receives the raw {@link WsContext} so that
     * #433 can correlate per-client state if needed.
     */
    @FunctionalInterface
    public interface InboundHandler {
        void handle(WsContext ctx, com.fasterxml.jackson.databind.JsonNode message) throws Exception;
    }
}

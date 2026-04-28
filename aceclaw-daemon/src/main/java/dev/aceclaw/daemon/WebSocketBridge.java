package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

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
    /**
     * Connection listeners. Snapshot pushers (#432) and per-client routers (#433)
     * subscribe here; tests use them to wait on a {@link java.util.concurrent.CountDownLatch}
     * instead of polling {@link #clientCount()}.
     */
    private final List<Consumer<WsContext>> connectListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<WsContext>> disconnectListeners = new CopyOnWriteArrayList<>();

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
                fire(connectListeners, ctx);
            });
            ws.onClose(ctx -> {
                clients.remove(ctx);
                log.info("WS client closed: {} (total={})", ctx.sessionId(), clients.size());
                fire(disconnectListeners, ctx);
            });
            ws.onError(ctx -> {
                boolean removed = clients.remove(ctx);
                Throwable err = ctx.error();
                log.warn("WS client error: {} ({})", ctx.sessionId(),
                        err != null ? err.getMessage() : "unknown");
                if (removed) {
                    fire(disconnectListeners, ctx);
                }
            });
            ws.onMessage(ctx -> {
                try {
                    var node = objectMapper.readTree(ctx.message());
                    inboundHandler.handle(ctx, node);
                } catch (Exception e) {
                    // Log full stack — debugging #433's permission-routing handler
                    // is painful without it.
                    log.warn("WS inbound parse failed", e);
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
     * <p>{@link WsContext#send(String)} is non-blocking — Jetty queues the frame
     * and reports send failures asynchronously via {@code onError}, which is
     * already wired to remove the client. The synchronous {@code try/catch}
     * here is a fallback for the rare case where {@code send} throws inline
     * (e.g. session already closed); it does not catch async failures.
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
                // Synchronous failure (e.g. session closed mid-iteration). Async
                // failures land on onError above, which also removes the client.
                log.warn("WS send failed for {}: {}", client.sessionId(), e.getMessage());
                clients.remove(client);
            }
        }
    }

    /**
     * Installs a handler for inbound client messages (e.g. permission.response).
     * The default handler drops messages so issue #431 stays one-way; #433 will
     * register a real handler that routes into the permission flow.
     */
    public void setInboundHandler(InboundHandler handler) {
        this.inboundHandler = Objects.requireNonNull(handler, "handler");
    }

    /**
     * Registers a listener invoked synchronously on Jetty's WS thread when a new
     * client establishes a connection. Useful for #432 (push the latest snapshot
     * to a freshly-connected tab) and for tests waiting on a {@link
     * java.util.concurrent.CountDownLatch} rather than polling {@link #clientCount()}.
     */
    public void addConnectionListener(Consumer<WsContext> listener) {
        connectListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Registers a listener invoked when a client disconnects (clean close OR
     * error after the client was added to the active set). Mirrors
     * {@link #addConnectionListener} for cleanup symmetry.
     */
    public void addDisconnectionListener(Consumer<WsContext> listener) {
        disconnectListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    private static void fire(List<Consumer<WsContext>> listeners, WsContext ctx) {
        for (var l : listeners) {
            try {
                l.accept(ctx);
            } catch (Exception e) {
                log.warn("WS listener threw: {}", e.toString(), e);
            }
        }
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

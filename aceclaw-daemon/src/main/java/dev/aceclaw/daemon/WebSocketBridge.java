package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
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
 * {@link #broadcast(String, String, Object)} call wraps the daemon event in the
 * {@code DaemonEventEnvelope} contract frozen by issue #439 and sends it to
 * every connected {@link WsContext}. Inbound messages from browsers (e.g.
 * {@code permission.response}) are accepted and parked on
 * {@link #setInboundHandler(InboundHandler)} so issue #433 can wire them
 * into the permission flow without touching this class again.
 *
 * <p>Wire format (matches {@code aceclaw-dashboard/src/types/events.ts}):
 * <pre>{@code
 * {
 *   "eventId":    <monotonic long>,        // bridge-assigned, used by #432 snapshot dedup
 *   "sessionId":  <string>,                // session this event belongs to
 *   "receivedAt": <ISO-8601>,              // bridge-assigned timestamp
 *   "event":      { method, params }       // mirrors DaemonEvent union
 * }
 * }</pre>
 * The {@code eventId} counter is monotonic per bridge instance and resets on
 * bridge restart — re-connecting clients fetch a fresh snapshot watermark.
 *
 * <p>Security: always binds to {@code localhost} by default. Acceptance
 * criterion from #431.
 */
public final class WebSocketBridge {

    private static final Logger log = LoggerFactory.getLogger(WebSocketBridge.class);

    private final String host;
    /**
     * Configured port. {@code 0} means "let Jetty pick an ephemeral port"; in
     * that case {@link #start()} replaces this with the actually-bound port so
     * {@link #port()} is always correct after the bridge is running.
     */
    private volatile int port;
    private final ObjectMapper objectMapper;
    /**
     * Browser {@code Origin} headers allowed to open a WS handshake. Empty =
     * reject any browser connection; tools that send no {@code Origin} (curl,
     * Java {@code HttpClient} default) are always accepted because cross-site
     * browser attacks cannot suppress the header.
     */
    private final List<String> allowedOrigins;
    private final Set<WsContext> clients = ConcurrentHashMap.newKeySet();
    /**
     * Connection listeners. Snapshot pushers (#432) and per-client routers (#433)
     * subscribe here; tests use them to wait on a {@link java.util.concurrent.CountDownLatch}
     * instead of polling {@link #clientCount()}.
     */
    private final List<Consumer<WsContext>> connectListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<WsContext>> disconnectListeners = new CopyOnWriteArrayList<>();
    /**
     * Monotonic envelope id (issue #439). Pre-incremented on each broadcast so
     * the first event gets {@code eventId = 1}; {@link #currentEventId()}
     * returns the last id assigned, which #432's snapshot endpoint will use as
     * the {@code lastEventId} watermark.
     */
    private final AtomicLong eventIdCounter = new AtomicLong();

    private volatile Javalin app;
    private volatile InboundHandler inboundHandler = (ctx, message) -> { /* default: drop */ };

    public WebSocketBridge(String host, int port, ObjectMapper objectMapper) {
        this(host, port, objectMapper, List.of());
    }

    public WebSocketBridge(String host, int port, ObjectMapper objectMapper,
                           List<String> allowedOrigins) {
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException(
                    "port must be in [0, 65535] (0 = ephemeral); got " + port);
        }
        this.host = Objects.requireNonNull(host, "host");
        this.port = port;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.allowedOrigins = List.copyOf(Objects.requireNonNull(allowedOrigins, "allowedOrigins"));
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
                if (!isOriginAllowed(ctx)) {
                    log.warn("WS: rejecting connection from disallowed origin '{}' (sessionId={})",
                            ctx.header("Origin"), ctx.sessionId());
                    // 1008 = Policy Violation. Closing here aborts the WS upgrade
                    // before the client is added to {@link #clients}, so it never
                    // receives any broadcast.
                    ctx.closeSession(1008, "origin not allowed");
                    return;
                }
                clients.add(ctx);
                log.info("WS client connected: {} (total={})", ctx.sessionId(), clients.size());
                fire(connectListeners, ctx);
            });
            ws.onClose(ctx -> {
                // dropClient gates listener notification on whether the client
                // was an active member. Disallowed-origin handshakes are closed
                // in onConnect BEFORE clients.add; onError or broadcast() may
                // have already removed-and-notified the same socket; or send()
                // may have failed synchronously inside broadcast. In every
                // case the helper makes "remove + notify" exactly-once.
                int beforeSize = clients.size();
                boolean wasActive = dropClient(ctx);
                log.info("WS client closed: {} (size {} -> {}, wasActive={})",
                        ctx.sessionId(), beforeSize, clients.size(), wasActive);
            });
            ws.onError(ctx -> {
                Throwable err = ctx.error();
                log.warn("WS client error: {} ({})", ctx.sessionId(),
                        err != null ? err.getMessage() : "unknown");
                dropClient(ctx);
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
        // Replace a 0 (ephemeral) port with the port Jetty actually bound. Tests
        // rely on this to avoid the TOCTOU race that {@code ServerSocket(0)} +
        // close-and-rebind would otherwise expose: another process can grab the
        // port between probe and bridge bind.
        if (this.port == 0) {
            this.port = instance.port();
        }
        this.app = instance;
        log.info("WebSocket bridge listening on ws://{}:{}/ws", host, this.port);
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
     * Broadcasts a daemon event to all connected clients, wrapped in the
     * {@code DaemonEventEnvelope} shape frozen by issue #439.
     *
     * <p>{@link WsContext#send(String)} is non-blocking — Jetty queues the frame
     * and reports send failures asynchronously via {@code onError}, which is
     * already wired to remove the client. The synchronous {@code try/catch}
     * here is a fallback for the rare case where {@code send} throws inline
     * (e.g. session already closed); it does not catch async failures.
     *
     * <p>{@code sessionId} is required so the reducer can recover session
     * identity for events whose {@code params} do not carry it (e.g.
     * {@code stream.text}, {@code stream.tool_use}, {@code stream.tool_completed}).
     *
     * @param sessionId session this event belongs to (must not be null)
     * @param method    daemon event method (e.g. {@code "stream.text"})
     * @param params    event parameters; serialised via Jackson
     */
    public void broadcast(String sessionId, String method, Object params) {
        Objects.requireNonNull(sessionId, "sessionId");
        if (clients.isEmpty() || app == null) {
            return;
        }
        // Always assign an envelope id even with no live clients so the counter
        // remains a faithful monotonic record. The early-return above is purely
        // an optimisation; if it is removed in future work the eventId stream
        // still has no gaps.
        long eventId = eventIdCounter.incrementAndGet();
        String message;
        try {
            var envelope = objectMapper.createObjectNode();
            envelope.put("eventId", eventId);
            envelope.put("sessionId", sessionId);
            envelope.put("receivedAt", Instant.now().toString());
            var event = objectMapper.createObjectNode();
            event.put("method", method);
            event.set("params", objectMapper.valueToTree(params));
            envelope.set("event", event);
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
                // failures land on onError above. Either path goes through
                // dropClient so disconnectListeners fire exactly once for the
                // first observer that wins the remove — listeners that track
                // connect/disconnect parity never lose a cleanup callback even
                // if Jetty's onClose runs late or never.
                log.warn("WS send failed for {}: {}", client.sessionId(), e.getMessage());
                dropClient(client);
            }
        }
    }

    /**
     * Removes {@code ctx} from the active set and fires disconnect listeners
     * iff this call won the remove. Idempotent: a second invocation for the
     * same context returns {@code false} and notifies nobody. The unified path
     * for every "this client is gone" observer (onClose, onError, broadcast
     * synchronous send failure) — listeners that track connect/disconnect
     * parity see exactly one disconnect per active client.
     */
    private boolean dropClient(WsContext ctx) {
        if (clients.remove(ctx)) {
            fire(disconnectListeners, ctx);
            return true;
        }
        return false;
    }

    /**
     * Returns the last envelope id assigned by {@link #broadcast}; zero if no
     * event has ever been broadcast on this bridge instance. Used by #432's
     * snapshot endpoint as the {@code lastEventId} watermark a freshly-loaded
     * browser sends back to deduplicate the live stream against the snapshot.
     */
    public long currentEventId() {
        return eventIdCounter.get();
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

    /**
     * Origin policy:
     * <ul>
     *   <li>No {@code Origin} header (curl, Java {@code HttpClient} default,
     *       native Jetty client): always allowed. Browsers cannot suppress the
     *       header, so absence means a non-browser caller and there is no
     *       cross-site exposure.</li>
     *   <li>{@code Origin} present and listed in {@link #allowedOrigins}: allowed.</li>
     *   <li>{@code Origin} present but not listed (or list is empty): rejected.
     *       This closes the cross-site exfiltration vector that any malicious
     *       webpage could otherwise exploit by opening
     *       {@code new WebSocket('ws://localhost:...')}.</li>
     * </ul>
     */
    private boolean isOriginAllowed(WsContext ctx) {
        String origin = ctx.header("Origin");
        if (origin == null) {
            return true;
        }
        return allowedOrigins.contains(origin);
    }

    /** Returns the number of currently connected browser clients. */
    public int clientCount() {
        return clients.size();
    }

    /** Returns true once {@link #start()} has bound the listener. */
    public boolean isRunning() {
        return app != null;
    }

    /**
     * Returns the bridge's port. Before {@link #start()} this is the configured
     * port (which may be {@code 0} when the caller asked for an ephemeral
     * port); after {@code start()} it is always the actually-bound port.
     */
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

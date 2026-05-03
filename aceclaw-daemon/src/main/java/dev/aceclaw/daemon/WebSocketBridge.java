package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
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

    /**
     * Sentinel sessionId stamped on globally-scoped envelopes (e.g. cron
     * scheduler events from #459). Per-session reducers filter envelopes by
     * matching the connected tab's sessionId, so this sentinel is invisible
     * to {@code useExecutionTree} and only the global hooks
     * ({@code useCronJobs}, …) opt in by checking for it. Late-joiners
     * backfill via dedicated polling endpoints (e.g. {@code scheduler.events.poll})
     * — global events deliberately do NOT go through the per-session
     * snapshot buffer, so this sentinel never accumulates entries there.
     */
    public static final String GLOBAL_SESSION_ID = "__global__";

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
    /**
     * Per-session ring buffer of envelopes, populated on every {@link #broadcast}
     * regardless of whether any client is currently connected. The snapshot
     * endpoint (#432) reads this so a late-joining tab can reconstruct the
     * tree without replaying real history.
     */
    private final SessionEventBuffer eventBuffer = new SessionEventBuffer();

    private volatile Javalin app;
    private volatile InboundHandler inboundHandler = (ctx, message) -> { /* default: drop */ };

    /**
     * Same-origin allowlist computed at {@link #start()} once the bound port is
     * known. Browser code loaded from {@code http://localhost:{port}} (the bundled
     * dashboard, issue #446) sends its page origin as the {@code Origin} header;
     * we accept it without requiring any user-side {@code allowedOrigins}
     * configuration. This is safe because a same-origin handshake by definition
     * cannot be cross-site — a page can only mint its own origin if it loaded
     * from that origin in the first place, which means it already passed the
     * static-files / page-load gate served by this same daemon.
     *
     * <p>Populated with both {@code http://localhost:{port}} and
     * {@code http://127.0.0.1:{port}} regardless of the configured bind host
     * (they are the two stable browser origins for a localhost-bound server).
     * Empty until {@link #start()} runs.
     */
    private volatile Set<String> sameOriginAllowlist = Set.of();

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
     * Classpath path of the bundled dashboard's entry point. Present when the
     * daemon JAR was built without {@code -Pno-dashboard}; absent in
     * backend-only builds. Detected once at {@link #start()} via
     * {@link Class#getResource(String)}.
     */
    static final String DASHBOARD_INDEX_RESOURCE = "/META-INF/dashboard/index.html";
    static final String DASHBOARD_DIRECTORY_RESOURCE = "/META-INF/dashboard";

    /**
     * Starts the embedded Javalin server. Idempotent.
     */
    public synchronized void start() {
        if (app != null) {
            return;
        }
        boolean dashboardBundled = WebSocketBridge.class.getResource(DASHBOARD_INDEX_RESOURCE) != null;
        var instance = Javalin.create(cfg -> {
            cfg.showJavalinBanner = false;
            // Bind only to the configured host (localhost by default).
            cfg.jetty.defaultHost = host;
            // Bundled dashboard (issue #446): serve static assets from the
            // daemon JAR at /, with SPA fallback so any non-asset path returns
            // index.html (lets the dashboard's React Router work, if added).
            // Skipped in -Pno-dashboard builds — index.html simply isn't on the
            // classpath, and a request to / falls through to the friendly 404
            // handler registered below.
            if (dashboardBundled) {
                cfg.staticFiles.add(staticFiles -> {
                    staticFiles.hostedPath = "/";
                    staticFiles.directory = DASHBOARD_DIRECTORY_RESOURCE;
                    staticFiles.location = Location.CLASSPATH;
                });
                cfg.spaRoot.addFile("/", DASHBOARD_INDEX_RESOURCE, Location.CLASSPATH);
            }
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
        // Friendly fallback for backend-only (-Pno-dashboard) builds: any GET
        // not handled by /ws or staticFiles falls through to here. Returning a
        // plain-text explanation beats Javalin's default 404 because it tells
        // the user precisely why the dashboard didn't load instead of looking
        // like a daemon bug.
        if (!dashboardBundled) {
            instance.get("/", ctx -> {
                ctx.status(404);
                ctx.contentType("text/plain; charset=utf-8");
                ctx.result("AceClaw dashboard is not bundled in this build.\n"
                        + "Rebuild without -Pno-dashboard, or run the dev server: "
                        + "cd aceclaw-dashboard && npm run dev\n");
            });
        }

        // Populate the same-origin allowlist BEFORE Jetty opens the port, so a
        // browser racing against startup cannot land on an empty allowlist and
        // get rejected as "origin not allowed". Only possible when the port is
        // configured (non-zero) — for the ephemeral-port test path the bound
        // port is unknown until {@code instance.start} returns, and we accept
        // the (microsecond-wide) race because no production caller uses port 0.
        if (this.port != 0) {
            this.sameOriginAllowlist = Set.of(
                    "http://localhost:" + this.port,
                    "http://127.0.0.1:" + this.port);
        }
        instance.start(port);
        // Replace a 0 (ephemeral) port with the port Jetty actually bound. Tests
        // rely on this to avoid the TOCTOU race that {@code ServerSocket(0)} +
        // close-and-rebind would otherwise expose: another process can grab the
        // port between probe and bridge bind.
        if (this.port == 0) {
            this.port = instance.port();
            this.sameOriginAllowlist = Set.of(
                    "http://localhost:" + this.port,
                    "http://127.0.0.1:" + this.port);
        }
        this.app = instance;
        if (dashboardBundled) {
            log.info("WebSocket bridge listening on ws://{}:{}/ws (dashboard at http://{}:{}/)",
                    host, this.port, host, this.port);
        } else {
            log.info("WebSocket bridge listening on ws://{}:{}/ws (dashboard not bundled)",
                    host, this.port);
        }
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
            // Drain via dropClient so disconnectListeners observe shutdown
            // drops, not just live-traffic drops. List.copyOf gives a stable
            // snapshot that's safe to iterate even if Jetty's onClose runs
            // concurrently from another thread; dropClient is idempotent so
            // a late onClose for the same socket simply no-ops.
            for (var client : List.copyOf(clients)) {
                dropClient(client);
            }
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
        // Guard against routing a globally-scoped event through the
        // per-session path by mistake — the sentinel must only ever be
        // emitted by broadcastGlobal, which deliberately skips the
        // SessionEventBuffer. A typo'd call here would silently pollute
        // the buffer with entries no snapshot.request would ever read.
        if (GLOBAL_SESSION_ID.equals(sessionId)) {
            throw new IllegalArgumentException(
                    "Use broadcastGlobal(method, params) for globally-scoped events; "
                            + "the GLOBAL_SESSION_ID sentinel must not appear in broadcast(sessionId, ...)");
        }
        if (app == null) {
            return;
        }
        var built = buildEnvelope(sessionId, method, params);
        if (built == null) {
            return;
        }
        // Append to the per-session ring buffer unconditionally — even with
        // zero connected clients, #432 needs the buffer populated so a tab
        // that opens AFTER the event was emitted can replay it via
        // snapshot.request.
        eventBuffer.append(sessionId, built.envelope());
        sendToAllClients(built.message());
    }

    /**
     * Broadcasts a globally-scoped event (no owning session) to every
     * connected client. Used by daemon-wide subsystems such as the cron
     * scheduler (#459) whose events are not tied to any one session.
     *
     * <p>The envelope still carries a {@code sessionId} field — set to
     * {@link #GLOBAL_SESSION_ID} — so the dashboard's per-session
     * {@code useExecutionTree} naturally filters these out, and global
     * hooks like {@code useCronJobs} opt in by matching that sentinel.
     *
     * <p>Globally-scoped events deliberately do NOT enter the per-session
     * snapshot ring buffer: late-joining tabs backfill via dedicated
     * polling endpoints (e.g. {@code scheduler.events.poll}), which is
     * cheaper than an ever-growing global bucket inside
     * {@link SessionEventBuffer} that no per-session snapshot.request
     * would ever read.
     */
    public void broadcastGlobal(String method, Object params) {
        if (app == null) {
            return;
        }
        var built = buildEnvelope(GLOBAL_SESSION_ID, method, params);
        if (built == null) {
            return;
        }
        sendToAllClients(built.message());
    }

    /** Carrier for buildEnvelope's two outputs (object form for buffer, string form for the wire). */
    private record Built(ObjectNode envelope, String message) {}

    private Built buildEnvelope(String sessionId, String method, Object params) {
        // Increment BEFORE any no-op early-return so eventId stays monotonic
        // and gap-free. #432's snapshot endpoint exposes currentEventId() as
        // the reconnect watermark; the browser uses it to deduplicate the
        // live stream against the snapshot, and that contract requires an
        // unbroken sequence regardless of whether any tab was connected
        // when each event was produced.
        long eventId = eventIdCounter.incrementAndGet();
        try {
            var envelope = objectMapper.createObjectNode();
            envelope.put("eventId", eventId);
            envelope.put("sessionId", sessionId);
            envelope.put("receivedAt", Instant.now().toString());
            var event = objectMapper.createObjectNode();
            event.put("method", method);
            event.set("params", objectMapper.valueToTree(params));
            envelope.set("event", event);
            return new Built(envelope, objectMapper.writeValueAsString(envelope));
        } catch (Exception e) {
            log.warn("Failed to serialise WS broadcast for {}: {}", method, e.getMessage());
            return null;
        }
    }

    private void sendToAllClients(String message) {
        if (clients.isEmpty()) {
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
     * Returns the per-session envelope buffer that backs {@code snapshot.request}
     * (issue #432). Daemon code reads from it via the inbound handler; tests
     * use it to assert that broadcasts populate the buffer correctly.
     */
    public SessionEventBuffer eventBuffer() {
        return eventBuffer;
    }

    /**
     * Drops every envelope held for {@code sessionId}. The daemon calls this
     * from {@link SessionManager}'s end callback after the
     * {@code stream.session_ended} broadcast has been buffered, so a snapshot
     * fetched in the brief window between session-end-broadcast and
     * session-removal still includes the {@code session_ended} event but
     * memory is reclaimed once the session is gone.
     */
    public void clearSession(String sessionId) {
        eventBuffer.clear(sessionId);
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
     *   <li>{@code Origin} matches the daemon's own bound port via
     *       {@link #sameOriginAllowlist} (same-origin from the bundled dashboard,
     *       #446): allowed without requiring user config. The page that opens
     *       this WS can only have minted that Origin if it loaded from this
     *       same daemon, so there is nothing cross-site to defend against.</li>
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
        if (sameOriginAllowlist.contains(origin)) {
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
     * Returns whether the bundled dashboard (issue #446) was on the daemon's
     * classpath at startup. False in {@code -Pno-dashboard} builds.
     */
    public static boolean dashboardBundled() {
        return WebSocketBridge.class.getResource(DASHBOARD_INDEX_RESOURCE) != null;
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

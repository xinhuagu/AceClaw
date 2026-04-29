package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests for {@link WebSocketBridge} — issue #431 acceptance criteria
 * (port binds, multiple clients, broadcast fan-out, clean disconnect).
 *
 * <p>All synchronisation is done with proper wait/notify primitives —
 * {@link BlockingQueue#poll(long, TimeUnit)} for messages and
 * {@link CountDownLatch#await(long, TimeUnit)} for connect / disconnect events
 * (via {@link WebSocketBridge#addConnectionListener}). No spin-polling, no
 * {@code Thread.sleep}: every wait blocks until either the event fires or the
 * 5-second timeout expires.
 */
final class WebSocketBridgeTest {

    private static final long AWAIT_TIMEOUT_MS = 5_000;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private WebSocketBridge bridge;

    @AfterEach
    void tearDown() {
        if (bridge != null) {
            bridge.stop();
        }
    }

    @Test
    void broadcastsDaemonEventEnvelopeToAllConnectedClients() throws Exception {
        bridge = startOnRandomPort();

        var connected = new CountDownLatch(2);
        bridge.addConnectionListener(_ -> connected.countDown());

        var queue1 = new LinkedBlockingQueue<String>();
        var queue2 = new LinkedBlockingQueue<String>();
        var ws1 = connect(queue1::add);
        var ws2 = connect(queue2::add);

        // Block until both server-side onConnect callbacks have fired.
        assertThat(connected.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .as("both clients must connect within %dms", AWAIT_TIMEOUT_MS)
                .isTrue();
        assertThat(bridge.clientCount()).isEqualTo(2);
        assertThat(bridge.currentEventId()).isZero();

        bridge.broadcast("sess-1", "stream.text", Map.of("delta", "hello"));

        var msg1 = queue1.poll(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        var msg2 = queue2.poll(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertThat(msg1).isNotNull();
        assertThat(msg2).isNotNull();

        // Wire format must match aceclaw-dashboard/src/types/events.ts
        // DaemonEventEnvelope { eventId, sessionId, receivedAt, event:{method, params} }.
        var node = objectMapper.readTree(msg1);
        assertThat(node.get("eventId").asLong()).isEqualTo(1L);
        assertThat(node.get("sessionId").asText()).isEqualTo("sess-1");
        assertThat(node.get("receivedAt").asText()).isNotBlank();
        assertThat(node.has("jsonrpc")).as("envelope must NOT be JSON-RPC framed").isFalse();
        var event = node.get("event");
        assertThat(event.get("method").asText()).isEqualTo("stream.text");
        assertThat(event.get("params").get("delta").asText()).isEqualTo("hello");
        assertThat(msg2).isEqualTo(msg1);
        assertThat(bridge.currentEventId()).isEqualTo(1L);

        ws1.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        ws2.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    @Test
    void eventIdIsMonotonicAcrossSuccessiveBroadcasts() throws Exception {
        bridge = startOnRandomPort();
        var connected = new CountDownLatch(1);
        bridge.addConnectionListener(_ -> connected.countDown());
        var queue = new LinkedBlockingQueue<String>();
        var ws = connect(queue::add);
        assertThat(connected.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();

        bridge.broadcast("sess-A", "stream.text", Map.of("delta", "1"));
        bridge.broadcast("sess-A", "stream.text", Map.of("delta", "2"));
        bridge.broadcast("sess-B", "stream.text", Map.of("delta", "3"));

        long id1 = objectMapper.readTree(queue.poll(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .get("eventId").asLong();
        long id2 = objectMapper.readTree(queue.poll(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .get("eventId").asLong();
        long id3 = objectMapper.readTree(queue.poll(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .get("eventId").asLong();
        assertThat(id1).isEqualTo(1L);
        assertThat(id2).isEqualTo(2L);
        assertThat(id3).isEqualTo(3L);
        assertThat(bridge.currentEventId()).isEqualTo(3L);

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    @Test
    void broadcastBeforeAnyClientIsANoOp() throws Exception {
        bridge = startOnRandomPort();
        bridge.broadcast("sess-1", "stream.heartbeat", Map.of("phase", "warmup"));
        assertThat(bridge.clientCount()).isZero();
    }

    @Test
    void eventIdAdvancesAcrossZeroClientGaps() throws Exception {
        // The currentEventId watermark feeds #432's snapshot/reconnect dedup,
        // so the counter must advance even when no tab is connected — gaps in
        // the id stream would let the live stream alias against the snapshot
        // and resurrect events the browser already saw.
        bridge = startOnRandomPort();
        assertThat(bridge.currentEventId()).isZero();

        bridge.broadcast("sess-1", "stream.text", Map.of("delta", "a"));
        bridge.broadcast("sess-1", "stream.text", Map.of("delta", "b"));
        bridge.broadcast("sess-1", "stream.text", Map.of("delta", "c"));

        assertThat(bridge.currentEventId())
                .as("eventId must increment for every broadcast, even with no clients")
                .isEqualTo(3L);
    }

    @Test
    void stopFiresDisconnectListenersForActiveClients() throws Exception {
        bridge = startOnRandomPort();
        var connected = new CountDownLatch(1);
        var disconnected = new CountDownLatch(1);
        var disconnectFires = new java.util.concurrent.atomic.AtomicInteger();
        bridge.addConnectionListener(_ -> connected.countDown());
        bridge.addDisconnectionListener(_ -> {
            disconnectFires.incrementAndGet();
            disconnected.countDown();
        });

        var queue = new LinkedBlockingQueue<String>();
        var ws = connect(queue::add);
        assertThat(connected.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(bridge.clientCount()).isEqualTo(1);

        bridge.stop();

        // dropClient drained the active set during stop; the listener observes
        // the cleanup. Idempotent: a later async onClose for the same socket
        // simply no-ops, so the count stays at exactly 1.
        assertThat(disconnected.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(disconnectFires.get())
                .as("stop() must fire disconnect listener exactly once per active client")
                .isEqualTo(1);
        assertThat(bridge.isRunning()).isFalse();

        ws.abort();
    }

    @Test
    void clientDisconnectDoesNotBlockSubsequentBroadcasts() throws Exception {
        bridge = startOnRandomPort();
        var connected = new CountDownLatch(1);
        var disconnected = new CountDownLatch(1);
        // Disconnect parity contract: the listener must fire EXACTLY ONCE per
        // client lifecycle, regardless of which observer path (onClose,
        // onError, broadcast synchronous send-failure) drops the client first.
        var disconnectFires = new java.util.concurrent.atomic.AtomicInteger();
        bridge.addConnectionListener(_ -> connected.countDown());
        bridge.addDisconnectionListener(_ -> {
            disconnectFires.incrementAndGet();
            disconnected.countDown();
        });

        var queue = new LinkedBlockingQueue<String>();
        var ws = connect(queue::add);
        assertThat(connected.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertThat(disconnected.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(bridge.clientCount()).isZero();

        // The daemon must not crash or hang when broadcasting after a disconnect.
        // Multiple post-close broadcasts must NOT re-fire disconnect — the
        // helper that drops clients is idempotent (CHM.remove + conditional
        // fire), so the listener observes one disconnect per client.
        bridge.broadcast("sess-1", "stream.text", Map.of("delta", "after-close-1"));
        bridge.broadcast("sess-1", "stream.text", Map.of("delta", "after-close-2"));
        assertThat(bridge.isRunning()).isTrue();
        assertThat(disconnectFires.get())
                .as("disconnect listener must fire exactly once per client lifecycle")
                .isEqualTo(1);
    }

    @Test
    void rejectsHandshakeWithDisallowedOrigin() throws Exception {
        // Empty allowedOrigins (the secure default) ⇒ any browser Origin is rejected.
        bridge = startOnRandomPort(List.of());

        // Wire a disconnect listener; it MUST NOT fire for connections that
        // were never added to the active set — otherwise consumers tracking
        // connect/disconnect parity would underflow on rejected handshakes.
        var disconnectFires = new java.util.concurrent.atomic.AtomicInteger();
        bridge.addDisconnectionListener(_ -> disconnectFires.incrementAndGet());

        var queue = new LinkedBlockingQueue<String>();
        var listener = new BufferingListener(queue::add);
        // Java HttpClient lets us forge an Origin header — same surface a malicious
        // page would see when calling new WebSocket('ws://localhost:3141/ws').
        HttpClient.newHttpClient().newWebSocketBuilder()
                .header("Origin", "https://evil.example")
                .buildAsync(URI.create("ws://127.0.0.1:" + bridge.port() + "/ws"), listener)
                .get(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        // Server closes immediately with 1008 (policy violation). The client-side
        // onClose latch is the proper signal; no polling.
        assertThat(listener.closed.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .as("server should close handshake from disallowed origin")
                .isTrue();
        assertThat(listener.closeStatusCode).isEqualTo(1008);
        assertThat(bridge.clientCount()).as("rejected client must NOT enter the active set").isZero();

        // Subsequent broadcast must not be visible to the rejected client.
        bridge.broadcast("sess-1", "stream.text", Map.of("delta", "should-not-arrive"));
        // poll(short) returns null because the client never received anything.
        assertThat(queue.poll(200, TimeUnit.MILLISECONDS)).isNull();
        assertThat(disconnectFires.get())
                .as("disconnect listener must NOT fire for handshakes that never joined")
                .isZero();
    }

    @Test
    void acceptsHandshakeWithAllowedOrigin() throws Exception {
        bridge = startOnRandomPort(List.of("https://dashboard.local"));

        var connected = new CountDownLatch(1);
        bridge.addConnectionListener(_ -> connected.countDown());

        var queue = new LinkedBlockingQueue<String>();
        var listener = new BufferingListener(queue::add);
        var ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .header("Origin", "https://dashboard.local")
                .buildAsync(URI.create("ws://127.0.0.1:" + bridge.port() + "/ws"), listener)
                .get(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        assertThat(connected.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();
        bridge.broadcast("sess-1", "stream.text", Map.of("delta", "hello"));
        assertThat(queue.poll(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isNotNull();

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    @Test
    void inboundHandlerReceivesParsedClientMessages() throws Exception {
        bridge = startOnRandomPort();
        var inbound = new CompletableFuture<String>();
        bridge.setInboundHandler((ctx, message) -> inbound.complete(message.toString()));

        var connected = new CountDownLatch(1);
        bridge.addConnectionListener(_ -> connected.countDown());

        var queue = new LinkedBlockingQueue<String>();
        var ws = connect(queue::add);
        assertThat(connected.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();

        ws.sendText("{\"method\":\"permission.response\",\"params\":{\"requestId\":\"r1\",\"approved\":true}}", true)
                .get(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        var payload = inbound.get(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertThat(payload).contains("permission.response").contains("\"r1\"");

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    @Test
    void inboundHandlerCanReplyPointToPoint() throws Exception {
        // Pins the contract that #445's sessions.list relies on: an inbound
        // handler can call ctx.send to reply to the requester only, without
        // routing through bridge.broadcast (which would fan the response out
        // to every other connected client too). Without this, the sessions
        // list would leak into every browser tab — wrong shape for a
        // request-response.
        bridge = startOnRandomPort();
        bridge.setInboundHandler((ctx, message) -> {
            // Echo a control-style reply, unwrapped (no DaemonEventEnvelope).
            // sessions.list does the same thing in production.
            if (message.has("method") && "ping".equals(message.get("method").asText())) {
                ctx.send("{\"method\":\"pong\",\"correlationId\":\""
                        + message.get("id").asText() + "\"}");
            }
        });

        // Two clients connected: a single-client test would still pass even
        // if the bridge fanned the reply out via broadcast (the requester
        // would receive its own reply either way). The second client lets
        // us prove the reply is requester-only.
        var connected = new CountDownLatch(2);
        bridge.addConnectionListener(_ -> connected.countDown());

        var requesterQueue = new LinkedBlockingQueue<String>();
        var otherQueue = new LinkedBlockingQueue<String>();
        var ws = connect(requesterQueue::add);
        var wsOther = connect(otherQueue::add);
        assertThat(connected.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();

        ws.sendText("{\"method\":\"ping\",\"id\":\"r-42\"}", true)
                .get(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        // Reply lands on the requesting client's queue. Crucially, this is
        // NOT envelope-wrapped — no eventId / sessionId / receivedAt.
        var reply = requesterQueue.poll(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertThat(reply).isNotNull();
        var node = objectMapper.readTree(reply);
        assertThat(node.get("method").asText()).isEqualTo("pong");
        assertThat(node.get("correlationId").asText()).isEqualTo("r-42");
        assertThat(node.has("eventId")).as("control replies must NOT carry an envelope").isFalse();

        // The non-requesting client must not see the reply: this is what
        // makes ctx.send semantically distinct from bridge.broadcast.
        // Use a short bounded poll — the message either arrived by now
        // (Jetty would have delivered it on the same network turn as the
        // requester's frame) or it never will. 200ms is plenty in-process.
        assertThat(otherQueue.poll(200, TimeUnit.MILLISECONDS))
                .as("non-requesting clients must not receive point-to-point replies")
                .isNull();

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        wsOther.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private WebSocketBridge startOnRandomPort() throws Exception {
        return startOnRandomPort(List.of());
    }

    /**
     * Asks Jetty to bind an ephemeral port (port=0) and lets the bridge read
     * back the actually-bound port via {@link WebSocketBridge#port()}. This
     * avoids the TOCTOU race that {@code ServerSocket(0).close()} +
     * later-rebind would expose: another process can grab the port in the
     * window between probe and bind.
     */
    private WebSocketBridge startOnRandomPort(List<String> allowedOrigins) throws Exception {
        var b = new WebSocketBridge("127.0.0.1", 0, objectMapper, allowedOrigins);
        b.start();
        return b;
    }

    private WebSocket connect(java.util.function.Consumer<String> onText) throws Exception {
        return HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + bridge.port() + "/ws"),
                        new BufferingListener(onText))
                .get(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private static final class BufferingListener implements WebSocket.Listener {
        private final java.util.function.Consumer<String> onText;
        private final StringBuilder partial = new StringBuilder();
        /** Counts down on onClose, letting tests await close without polling. */
        final CountDownLatch closed = new CountDownLatch(1);
        volatile int closeStatusCode = -1;

        BufferingListener(java.util.function.Consumer<String> onText) {
            this.onText = onText;
        }

        @Override
        public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                onText.accept(partial.toString());
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletableFuture<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeStatusCode = statusCode;
            closed.countDown();
            return null;
        }
    }
}

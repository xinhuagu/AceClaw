package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
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
    void broadcastsJsonRpcNotificationToAllConnectedClients() throws Exception {
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

        bridge.broadcast("stream.text", Map.of("delta", "hello"));

        var msg1 = queue1.poll(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        var msg2 = queue2.poll(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertThat(msg1).isNotNull();
        assertThat(msg2).isNotNull();

        var node = objectMapper.readTree(msg1);
        assertThat(node.get("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(node.get("method").asText()).isEqualTo("stream.text");
        assertThat(node.get("params").get("delta").asText()).isEqualTo("hello");
        assertThat(msg2).isEqualTo(msg1);

        ws1.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        ws2.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    @Test
    void broadcastBeforeAnyClientIsANoOp() throws Exception {
        bridge = startOnRandomPort();
        bridge.broadcast("stream.heartbeat", Map.of("phase", "warmup"));
        assertThat(bridge.clientCount()).isZero();
    }

    @Test
    void clientDisconnectDoesNotBlockSubsequentBroadcasts() throws Exception {
        bridge = startOnRandomPort();
        var connected = new CountDownLatch(1);
        var disconnected = new CountDownLatch(1);
        bridge.addConnectionListener(_ -> connected.countDown());
        bridge.addDisconnectionListener(_ -> disconnected.countDown());

        var queue = new LinkedBlockingQueue<String>();
        var ws = connect(queue::add);
        assertThat(connected.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertThat(disconnected.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(bridge.clientCount()).isZero();

        // The daemon must not crash or hang when broadcasting after a disconnect.
        bridge.broadcast("stream.text", Map.of("delta", "after-close"));
        assertThat(bridge.isRunning()).isTrue();
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

    private WebSocketBridge startOnRandomPort() throws Exception {
        var b = new WebSocketBridge("127.0.0.1", freePort(), objectMapper);
        b.start();
        return b;
    }

    private WebSocket connect(java.util.function.Consumer<String> onText) throws Exception {
        return HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + bridge.port() + "/ws"),
                        new BufferingListener(onText))
                .get(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private static int freePort() throws Exception {
        try (var s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static final class BufferingListener implements WebSocket.Listener {
        private final java.util.function.Consumer<String> onText;
        private final StringBuilder partial = new StringBuilder();

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
    }
}

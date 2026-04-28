package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests for {@link WebSocketBridge} — issue #431 acceptance criteria
 * (port binds, multiple clients, broadcast fan-out, clean disconnect).
 */
final class WebSocketBridgeTest {

    private static final long POLL_TIMEOUT_MS = 5_000;

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

        var received1 = new ArrayList<String>();
        var received2 = new ArrayList<String>();
        var ws1 = connect(received1::add);
        var ws2 = connect(received2::add);

        pollUntil(() -> bridge.clientCount() == 2);

        bridge.broadcast("stream.text", Map.of("delta", "hello"));

        pollUntil(() -> received1.size() == 1 && received2.size() == 1);

        var node = objectMapper.readTree(received1.get(0));
        assertThat(node.get("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(node.get("method").asText()).isEqualTo("stream.text");
        assertThat(node.get("params").get("delta").asText()).isEqualTo("hello");
        assertThat(received2.get(0)).isEqualTo(received1.get(0));

        ws1.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        ws2.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
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
        var ws = connect(_ -> { });
        pollUntil(() -> bridge.clientCount() == 1);

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        pollUntil(() -> bridge.clientCount() == 0);

        // The daemon must not crash or hang when broadcasting after a disconnect.
        bridge.broadcast("stream.text", Map.of("delta", "after-close"));
        assertThat(bridge.isRunning()).isTrue();
    }

    @Test
    void inboundHandlerReceivesParsedClientMessages() throws Exception {
        bridge = startOnRandomPort();
        var inbound = new CompletableFuture<String>();
        bridge.setInboundHandler((ctx, message) -> inbound.complete(message.toString()));

        var ws = connect(_ -> { });
        pollUntil(() -> bridge.clientCount() == 1);

        ws.sendText("{\"method\":\"permission.response\",\"params\":{\"requestId\":\"r1\",\"approved\":true}}", true)
                .get(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        var payload = inbound.get(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertThat(payload).contains("permission.response").contains("\"r1\"");

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
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
                .get(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private static int freePort() throws Exception {
        try (var s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static void pollUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out waiting for condition");
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

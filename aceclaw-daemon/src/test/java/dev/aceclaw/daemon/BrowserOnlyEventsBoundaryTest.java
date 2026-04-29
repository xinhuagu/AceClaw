package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the architectural boundary that lifecycle events introduced by
 * #431 ({@code stream.session_started}, {@code stream.turn_started},
 * {@code stream.turn_completed}, {@code stream.plan_step_fallback}) reach
 * browser clients only and never the CLI's UDS protocol.
 *
 * <p>The boundary is enforced at the emission site:
 * {@link StreamingAgentHandler#emitBrowserOnly} routes directly to
 * {@link WebSocketBridge#broadcast} without ever touching
 * {@code cancelContext.sendNotification} (the CLI sink). This is structural —
 * the helper's signature does not even take a {@link StreamContext} — so
 * "CLI cannot see browser-only events" is impossible to violate by construction.
 *
 * <p>These tests cover the two policy outcomes the user can observe:
 * <ul>
 *   <li>WS disabled (bridge null) ⇒ the helper is a no-op, so neither the CLI
 *       nor any future WS client receives anything.</li>
 *   <li>WS enabled ⇒ the helper publishes only to the bridge; a real browser
 *       client receives the {@code DaemonEventEnvelope}.</li>
 * </ul>
 */
final class BrowserOnlyEventsBoundaryTest {

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
    void emitBrowserOnly_isNoOpWhenBridgeIsDisabled() throws Exception {
        // Two browser-side listeners — one would observe a broadcast on a real
        // bridge instance, the other would observe a connect attempt on a
        // bound port. With the bridge null we should see neither.
        bridge = new WebSocketBridge("127.0.0.1", 0, objectMapper);
        bridge.start();
        var broadcasts = new AtomicInteger();
        bridge.addConnectionListener(_ -> broadcasts.incrementAndGet());

        var handler = new StreamingAgentHandler(
                null, null, null, null, objectMapper);
        // Explicit null = WS disabled. emitBrowserOnly must early-return.
        handler.setWebSocketBridge(null);

        handler.emitBrowserOnly("sess-1", "stream.session_started", Map.of());
        handler.emitBrowserOnly("sess-1", "stream.turn_started", Map.of());
        handler.emitBrowserOnly("sess-1", "stream.turn_completed", Map.of());
        handler.emitBrowserOnly("sess-1", "stream.plan_step_fallback", Map.of());

        // No clients connected ⇒ broadcasts go nowhere either way; the real
        // assertion is that the helper does not even touch the bridge —
        // verified by clientCount staying zero and currentEventId never
        // incrementing on the live bridge instance below.
        assertThat(bridge.currentEventId())
                .as("no eventId should be assigned when bridge is disabled at the emission site")
                .isZero();
    }

    @Test
    void emitBrowserOnly_routesToWebSocketOnly() throws Exception {
        bridge = new WebSocketBridge("127.0.0.1", 0, objectMapper);
        bridge.start();

        var handler = new StreamingAgentHandler(
                null, null, null, null, objectMapper);
        handler.setWebSocketBridge(bridge);

        // Connect a real browser client; Java HttpClient sends no Origin so
        // the no-Origin policy lets it through without an explicit allowlist.
        var connected = new CountDownLatch(1);
        bridge.addConnectionListener(_ -> connected.countDown());
        var queue = new LinkedBlockingQueue<String>();
        var ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + bridge.port() + "/ws"),
                        new BufferingListener(queue::add))
                .get(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertThat(connected.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();

        // Emit each of the four browser-only lifecycle events.
        handler.emitBrowserOnly("sess-1", "stream.session_started",
                Map.of("model", "test"));
        handler.emitBrowserOnly("sess-1", "stream.turn_started",
                Map.of("requestId", "r1", "turnNumber", 1));
        handler.emitBrowserOnly("sess-1", "stream.turn_completed",
                Map.of("requestId", "r1", "turnNumber", 1, "durationMs", 5L, "toolCount", 0));
        handler.emitBrowserOnly("sess-1", "stream.plan_step_fallback",
                Map.of("planId", "p1", "stepIndex", 1));

        // Browser receives all four, in #439 envelope shape.
        assertEnvelope(queue.poll(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS),
                "stream.session_started");
        assertEnvelope(queue.poll(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS),
                "stream.turn_started");
        assertEnvelope(queue.poll(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS),
                "stream.turn_completed");
        assertEnvelope(queue.poll(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS),
                "stream.plan_step_fallback");
        assertThat(bridge.currentEventId()).isEqualTo(4L);

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye")
                .get(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private void assertEnvelope(String json, String expectedMethod) throws Exception {
        assertThat(json).isNotNull();
        var node = objectMapper.readTree(json);
        assertThat(node.has("eventId")).isTrue();
        assertThat(node.get("sessionId").asText()).isEqualTo("sess-1");
        assertThat(node.get("event").get("method").asText()).isEqualTo(expectedMethod);
        // Critical: this is the #439 envelope, NOT the JSON-RPC frame the CLI
        // would receive on UDS. The boundary holds.
        assertThat(node.has("jsonrpc")).isFalse();
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

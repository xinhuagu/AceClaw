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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link BroadcastOnlyStreamContext} (#459 cron-as-session).
 *
 * The point of this context: cron runs (and any other daemon-internal
 * subsystem with no CLI client) get a {@link StreamContext} that
 * fans events out to the WS bridge as if they were a normal session,
 * AND has a no-op inbound channel so the agent loop can never block
 * on a permission response that won't arrive.
 */
final class BroadcastOnlyStreamContextTest {

    private static final long AWAIT_TIMEOUT_MS = 5_000;

    private final ObjectMapper mapper = new ObjectMapper();
    private WebSocketBridge bridge;

    @AfterEach
    void tearDown() {
        if (bridge != null) bridge.stop();
    }

    @Test
    void sendNotificationBroadcastsViaBridgeUnderTheGivenSessionId() throws Exception {
        bridge = new WebSocketBridge("127.0.0.1", 0, mapper);
        bridge.start();
        var connected = new CountDownLatch(1);
        bridge.addConnectionListener(_ -> connected.countDown());
        var queue = new LinkedBlockingQueue<String>();
        var ws = connect(queue::add);
        assertThat(connected.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();

        var ctx = new BroadcastOnlyStreamContext(bridge, "cron-daily-backup");
        ctx.sendNotification("stream.thinking", Map.of("delta", "considering options"));

        var msg = queue.poll(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertThat(msg).isNotNull();
        var node = mapper.readTree(msg);
        // sessionId on the wire is the cron-as-session id, which the
        // dashboard uses to route the events into the matching tree.
        assertThat(node.get("sessionId").asText()).isEqualTo("cron-daily-backup");
        assertThat(node.get("event").get("method").asText()).isEqualTo("stream.thinking");
        assertThat(node.get("event").get("params").get("delta").asText()).isEqualTo("considering options");

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    @Test
    void readMessageReturnsNullSoCallersDontBlockForever() throws Exception {
        // Cron has no client to read from. The agent loop's
        // permission-response read path checks for null and treats it
        // as "no client" — without this the cron run would hang
        // forever waiting for a stdin that doesn't exist.
        bridge = new WebSocketBridge("127.0.0.1", 0, mapper);
        bridge.start();
        var ctx = new BroadcastOnlyStreamContext(bridge, "cron-x");
        assertThat(ctx.readMessage()).isNull();
        assertThat(ctx.readMessage(50)).isNull();
    }

    @Test
    void rejectsNullBridgeAtConstruction() {
        // Defense in depth: a daemon with WS disabled must NOT pass a
        // null bridge here — CronScheduler is responsible for falling
        // back to SilentStreamHandler in that case.
        assertThatThrownBy(() -> new BroadcastOnlyStreamContext(null, "cron-x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankSessionIdAtConstruction() {
        bridge = new WebSocketBridge("127.0.0.1", 0, mapper);
        assertThatThrownBy(() -> new BroadcastOnlyStreamContext(bridge, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BroadcastOnlyStreamContext(bridge, "   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BroadcastOnlyStreamContext(bridge, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private WebSocket connect(java.util.function.Consumer<String> onText) throws Exception {
        return HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + bridge.port() + "/ws"),
                        new java.net.http.WebSocket.Listener() {
                            private final StringBuilder partial = new StringBuilder();

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
                        })
                .get(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }
}

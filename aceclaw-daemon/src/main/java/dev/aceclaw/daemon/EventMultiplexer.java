package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.Objects;

/**
 * Per-request {@link StreamContext} that tees outgoing JSON-RPC notifications
 * to both the original CLI sink (Unix domain socket) and the
 * {@link WebSocketBridge} fan-out for connected browser clients
 * (issue #431, epic #430).
 *
 * <p>Architectural rule from #431:
 * <ul>
 *   <li>{@code sendNotification} → delegate (CLI) + bridge.broadcast (all WS)</li>
 *   <li>{@code readMessage} → delegate only. Inbound from WS is browser→daemon
 *       traffic and is wired separately via {@link WebSocketBridge#setInboundHandler}
 *       in #433. Routing inbound WS messages into the per-request reader would
 *       race with the CLI's permission flow.</li>
 * </ul>
 *
 * <p>If the bridge is disabled (i.e. the daemon was started without the
 * WebSocket section), the call site uses the raw {@link StreamContext} directly
 * and does not instantiate this class — zero overhead, zero behavioural change
 * for the CLI path.
 */
public final class EventMultiplexer implements StreamContext {

    private final StreamContext delegate;
    private final WebSocketBridge bridge;

    public EventMultiplexer(StreamContext delegate, WebSocketBridge bridge) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.bridge = Objects.requireNonNull(bridge, "bridge");
    }

    @Override
    public void sendNotification(String method, Object params) throws IOException {
        // CLI sink: must propagate IOException so the per-request handler
        // can react to a broken Unix socket (cancel, cleanup, etc.).
        delegate.sendNotification(method, params);
        // WS sink: best-effort fan-out. A browser disconnect must NOT corrupt
        // the CLI's request flow. WebSocketBridge#broadcast already swallows
        // and logs per-client send failures.
        bridge.broadcast(method, params);
    }

    @Override
    public JsonNode readMessage() throws IOException {
        return delegate.readMessage();
    }

    @Override
    public JsonNode readMessage(long timeoutMs) throws IOException {
        return delegate.readMessage(timeoutMs);
    }
}

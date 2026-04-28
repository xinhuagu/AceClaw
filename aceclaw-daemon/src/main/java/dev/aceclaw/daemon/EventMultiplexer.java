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
 *   <li>{@code sendNotification} → delegate (CLI, raw JSON-RPC) +
 *       {@code bridge.broadcast(sessionId, method, params)} (WS, wrapped in the
 *       {@code DaemonEventEnvelope} frozen by issue #439)</li>
 *   <li>{@code readMessage} → delegate only. Inbound from WS is browser→daemon
 *       traffic and is wired separately via {@link WebSocketBridge#setInboundHandler}
 *       in #433. Routing inbound WS messages into the per-request reader would
 *       race with the CLI's permission flow.</li>
 * </ul>
 *
 * <p>The CLI wire format (raw JSON-RPC over UDS) and the browser wire format
 * (envelope-wrapped) intentionally diverge: each is the right shape for its
 * consumer, and the multiplexer is the only place that sees both.
 *
 * <p>If the bridge is disabled (i.e. the daemon was started without the
 * WebSocket section), the call site uses the raw {@link StreamContext} directly
 * and does not instantiate this class — zero overhead, zero behavioural change
 * for the CLI path.
 */
public final class EventMultiplexer implements StreamContext {

    private final StreamContext delegate;
    private final WebSocketBridge bridge;
    private final String sessionId;

    public EventMultiplexer(StreamContext delegate, WebSocketBridge bridge, String sessionId) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
    }

    @Override
    public void sendNotification(String method, Object params) throws IOException {
        // CLI sink: must propagate IOException so the per-request handler
        // can react to a broken Unix socket (cancel, cleanup, etc.).
        delegate.sendNotification(method, params);
        // WS sink: best-effort fan-out wrapped in the #439 envelope. The
        // sessionId stamped on every envelope lets the reducer recover session
        // identity for events whose params don't carry it (text deltas,
        // tool_use, tool_completed, etc.). WebSocketBridge#broadcast already
        // swallows and logs per-client send failures so a browser disconnect
        // never corrupts the CLI's request flow.
        bridge.broadcast(sessionId, method, params);
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

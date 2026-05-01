package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@link StreamContext} for daemon-internal subsystems that need to emit
 * dashboard events but have no inbound channel. Used by the cron
 * scheduler (#459): a cron run has no CLI client to talk back to, but
 * the dashboard still wants to see {@code stream.thinking} /
 * {@code stream.tool_use} / {@code stream.text} as the run unfolds.
 *
 * <p>The "session" identity for these events is conceptual — for cron
 * it's {@code "cron-" + jobId}, deterministic across runs of the same
 * job so all triggers stack into the same ExecutionTree (each run
 * becomes a new turn under the same session).
 *
 * <p>{@link #readMessage()} always returns {@code null}: there is no
 * inbound channel, and any caller that blocks on it would hang
 * forever. The intended use is one-way notification only.
 */
final class BroadcastOnlyStreamContext implements StreamContext {

    private final WebSocketBridge bridge;
    private final String sessionId;

    BroadcastOnlyStreamContext(WebSocketBridge bridge, String sessionId) {
        if (bridge == null) {
            throw new IllegalArgumentException("bridge must not be null");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        this.bridge = bridge;
        this.sessionId = sessionId;
    }

    @Override
    public void sendNotification(String method, Object params) {
        bridge.broadcast(sessionId, method, params);
    }

    @Override
    public JsonNode readMessage() {
        // Daemon-internal subsystems have no client to read from. Returning
        // null tells callers "no message; treat as closed" — the agent
        // loop won't block waiting for a permission response that can
        // never arrive (cron paths run with their own permission checker
        // that auto-decides per the job's allowedTools set).
        return null;
    }
}

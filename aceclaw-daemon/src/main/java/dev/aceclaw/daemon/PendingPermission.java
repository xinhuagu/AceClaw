package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.concurrent.CompletableFuture;

/**
 * Tuple stored in {@code StreamingAgentHandler.permissionRegistry}: the
 * originating session, the future the permission flow is waiting on, and the
 * context that originated the request. Used by the WS routing path to
 * validate cross-session attempts and to push a {@code permission.cancelled}
 * notification back to the originating CLI's UDS connection when a browser
 * tab has already answered (issue #437).
 *
 * <p>Extracted from {@code StreamingAgentHandler} (originally a private
 * record). Promoted to top-level so {@link CancelAwareStreamContext} and the
 * daemon-wide registry can share the type cleanly across siblings rather
 * than reaching across a private inner declaration.
 */
record PendingPermission(
        String sessionId,
        CompletableFuture<JsonNode> future,
        StreamContext context) {}

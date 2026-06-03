package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.agent.CancellationToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wraps a delegate {@link StreamContext} with a background monitor that
 * reads inbound messages off the UDS socket without blocking the agent loop.
 * Routes {@code agent.cancel} to a {@link CancellationToken}, completes
 * pending permission futures from {@code permission.response}, and forwards
 * other unmatched responses (like {@code resume.response}) through a
 * blocking queue.
 *
 * <p>Extracted from {@code StreamingAgentHandler} (originally a static inner
 * class). Package-private — only the handler and its sibling permission /
 * resume paths reach for this directly.
 *
 * <p>The monitor thread reads messages from the socket and:
 * <ul>
 *   <li>On {@code agent.cancel}: triggers the cancellation token and
 *       drains pending permission futures with a {@code permission.cancelled}
 *       fan-out so the CLI's modal dismisses without waiting on stdin
 *       (issue #437).</li>
 *   <li>On {@code permission.response}: completes the matching future and
 *       mirrors removal into the daemon-wide registry shared with the WS
 *       bridge (issue #433).</li>
 *   <li>On connection close: triggers cancellation.</li>
 * </ul>
 */
final class CancelAwareStreamContext implements StreamContext {

    private static final Logger log = LoggerFactory.getLogger(CancelAwareStreamContext.class);

    private static final int READ_BUFFER_SIZE = 65536;
    private static final long SELECT_TIMEOUT_MS = 100;
    /**
     * Package-private so {@link PermissionAwareTool} (and the
     * inline-permission path in {@code StreamingAgentHandler}) can share the
     * same wait budget when blocking on a permission future. Was private
     * when both lived inside the same outer class — bump to package-private
     * to preserve access after the extraction.
     */
    static final long PERMISSION_RESPONSE_TIMEOUT_MS = 120_000;

    private final StreamContext delegate;
    private final CancellationToken cancellationToken;
    private final ObjectMapper objectMapper;
    private final SocketChannel channel;
    private final StringBuilder lineBuilder;
    private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> pendingPermissions = new ConcurrentHashMap<>();
    /**
     * Daemon-wide registry shared with the WS bridge (issue #433) so a
     * browser-sent {@code permission.response} can complete the same
     * future the CLI socket monitor would. Null when running outside a
     * full daemon (tests). All writes mirror {@link #pendingPermissions};
     * value type carries the sessionId for the cross-session guard in
     * {@code StreamingAgentHandler.routePermissionResponse}.
     */
    private final ConcurrentHashMap<String, PendingPermission> globalPermissionRegistry;
    private final BlockingQueue<JsonNode> unmatchedResponses = new LinkedBlockingQueue<>();
    private final Object permissionLifecycleLock = new Object();
    private final AtomicInteger toolUseCount = new AtomicInteger();
    private volatile boolean stopped = false;
    private volatile Thread monitorThread;
    private volatile Selector selector;

    CancelAwareStreamContext(StreamContext delegate, CancellationToken cancellationToken,
                             ObjectMapper objectMapper) {
        this(delegate, delegate, cancellationToken, objectMapper, null);
    }

    CancelAwareStreamContext(StreamContext delegate, CancellationToken cancellationToken,
                             ObjectMapper objectMapper,
                             ConcurrentHashMap<String, PendingPermission> globalPermissionRegistry) {
        this(delegate, delegate, cancellationToken, objectMapper, globalPermissionRegistry);
    }

    /**
     * Two-context overload: {@code rawContext} is the original socket-backed
     * context used for cancel-monitor channel extraction; {@code outboundContext}
     * is what {@link #sendNotification} delegates to and is typically an
     * {@link EventMultiplexer} that tees to the WebSocket bridge.
     */
    CancelAwareStreamContext(StreamContext rawContext, StreamContext outboundContext,
                             CancellationToken cancellationToken, ObjectMapper objectMapper) {
        this(rawContext, outboundContext, cancellationToken, objectMapper, null);
    }

    CancelAwareStreamContext(StreamContext rawContext, StreamContext outboundContext,
                             CancellationToken cancellationToken, ObjectMapper objectMapper,
                             ConcurrentHashMap<String, PendingPermission> globalPermissionRegistry) {
        this.delegate = outboundContext;
        this.cancellationToken = cancellationToken;
        this.objectMapper = objectMapper;
        this.globalPermissionRegistry = globalPermissionRegistry;

        // Extract the channel and lineBuilder from the underlying ChannelStreamContext
        if (rawContext instanceof ConnectionBridge.ChannelStreamContext channelCtx) {
            this.channel = channelCtx.channel();
            this.lineBuilder = channelCtx.lineBuilder();
        } else {
            this.channel = null;
            this.lineBuilder = null;
        }
    }

    /** Number of {@code stream.tool_use} notifications observed during this request. */
    int toolUseCount() {
        return toolUseCount.get();
    }

    /**
     * Registers a pending permission request keyed by requestId.
     * Returns a future that will be completed when the matching response arrives.
     */
    CompletableFuture<JsonNode> registerPermissionRequest(String requestId, String sessionId) {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(sessionId, "sessionId");
        var future = new CompletableFuture<JsonNode>();
        synchronized (permissionLifecycleLock) {
            if (stopped) {
                future.cancel(false);
                return future;
            }
            var previous = pendingPermissions.putIfAbsent(requestId, future);
            if (previous != null) {
                future.cancel(false);
                throw new IllegalStateException("Duplicate permission requestId: " + requestId);
            }
            // Mirror into the daemon-wide registry so the WS bridge's
            // permission.response routing (issue #433) can complete
            // the same future without knowing which context owns it.
            // Tag with sessionId for the cross-session guard.
            if (globalPermissionRegistry != null) {
                globalPermissionRegistry.put(requestId, new PendingPermission(sessionId, future, this));
            }
        }
        return future;
    }

    /**
     * Unregisters and cancels a pending permission request if it has not been completed.
     * Safe to call even if the monitor already removed and completed the future.
     */
    void unregisterPermissionRequest(String requestId) {
        Objects.requireNonNull(requestId, "requestId");
        CompletableFuture<JsonNode> future;
        synchronized (permissionLifecycleLock) {
            future = pendingPermissions.remove(requestId);
            if (globalPermissionRegistry != null) {
                globalPermissionRegistry.remove(requestId);
            }
        }
        if (future != null && !future.isDone()) {
            future.cancel(false);
        }
    }

    /**
     * Starts the background monitor thread that reads from the socket.
     * Switches the channel to non-blocking mode for the duration of monitoring.
     * Must be called before the agent loop starts.
     */
    void startMonitor() {
        if (channel == null) {
            log.debug("Cancel monitor: no socket channel available, skipping monitor");
            return;
        }
        monitorThread = Thread.ofVirtual()
                .name("aceclaw-cancel-monitor")
                .start(this::monitorLoop);
    }

    /**
     * Resolves every pending permission request as cancelled. Used by
     * the {@code agent.cancel} branch of the monitor so the agent
     * loop's {@code future.get()} in {@code PermissionAwareTool.execute}
     * unblocks immediately with {@code CancellationException}
     * instead of waiting up to {@link #PERMISSION_RESPONSE_TIMEOUT_MS}
     * (120 s) — without this, Ctrl-C sets the cancellation token
     * but the turn stays blocked on the permission future, so
     * {@code stopMonitor()} never runs and the CLI's modal sits
     * there until the daemon-side timeout fires.
     *
     * <p>Per request emits a best-effort {@code permission.cancelled}
     * notification so the originating CLI's
     * {@code TaskStreamReader.handlePermissionCancelled} can route
     * it into {@code PermissionBridge.cancelExternal} — that path
     * both completes the task-thread future and pokes the modal's
     * polling loop so it dismisses without waiting on stdin.
     * Send failures (socket closed, peer disconnected) don't roll
     * back the future cancellation: the in-memory state must stay
     * consistent even if the wire send fails.
     *
     * <p><b>Ordering:</b> the notification is sent <em>before</em>
     * {@code future.cancel(false)}. Cancelling the future first
     * would race the agent thread to the shared socket — once
     * unblocked it returns a denied {@code ToolResult} and the
     * turn's finally block emits the final JSON-RPC response. If
     * that response wins the write, the CLI's
     * {@code TaskStreamReader} stops reading further notifications
     * and the trailing {@code permission.cancelled} is dropped on
     * the floor, leaving the y/n modal stuck on stdin.
     *
     * <p>Idempotent. The snapshot + map drain runs under
     * {@link #permissionLifecycleLock} so it's atomic against other
     * writers (registerPermissionRequest / stopMonitor); the
     * subsequent socket writes and future cancellations happen
     * OUTSIDE the lock so a slow peer can't gate concurrent
     * permission lifecycle operations.
     */
    private void cancelAllPendingPermissions(String via) {
        // Drain the maps under the lock — but DON'T cancel futures yet.
        // The agent loop's future.get() stays blocked, so it can't race
        // us to the socket while we're sending permission.cancelled below.
        var snapshot = new ArrayList<Map.Entry<String, CompletableFuture<JsonNode>>>();
        synchronized (permissionLifecycleLock) {
            pendingPermissions.forEach((rid, future) -> {
                snapshot.add(Map.entry(rid, future));
                if (globalPermissionRegistry != null) {
                    // Match by future identity — same rationale as stopMonitor.
                    var pending = globalPermissionRegistry.get(rid);
                    if (pending != null && pending.future() == future) {
                        globalPermissionRegistry.remove(rid, pending);
                    }
                }
            });
            pendingPermissions.clear();
        }
        // Outside lock: send dismissal first, THEN cancel the future.
        // See class-level javadoc above for why this ordering is required.
        for (var entry : snapshot) {
            // Skip futures already resolved by a concurrent path —
            // typically a WS permission.response that completed the
            // future via routePermissionResponse without taking
            // permissionLifecycleLock (per-context pendingPermissions
            // is only drained by the monitor's UDS-side handler and
            // by agent-loop unregister, so we can snapshot an entry
            // whose future was already approved). Emitting an
            // approved=false cancellation here would advertise a
            // denial for a request that actually won via approval —
            // Codex P2 on PR #493. Tiny isDone-then-completed race
            // remains (microseconds) but is bounded and the CLI's
            // PermissionBridge.cancelExternal uses putIfAbsent so a
            // late-stale dismissal is dropped on the receiving side.
            var future = entry.getValue();
            if (future.isDone()) {
                log.debug("Cancel monitor: skipping permission.cancelled for "
                        + "already-resolved requestId={}", entry.getKey());
                continue;
            }
            try {
                var cancelParams = objectMapper.createObjectNode();
                cancelParams.put("requestId", entry.getKey());
                cancelParams.put("approved", false);
                cancelParams.put("via", via);
                delegate.sendNotification("permission.cancelled", cancelParams);
            } catch (IOException e) {
                log.debug("Cancel monitor: failed to send permission.cancelled "
                        + "for requestId={}: {}", entry.getKey(), e.getMessage());
            }
            future.cancel(false);
        }
    }

    /**
     * Stops the monitor thread cleanly without interrupting.
     * Sets the stopped flag and wakes the selector so the thread exits promptly.
     */
    void stopMonitor() {
        stopped = true;
        // Cancel all pending permission futures so waiting threads unblock.
        // No permission.cancelled fan-out here: stopMonitor runs at the
        // request's finally block, by which point the turn is wrapping up
        // and the CLI will see stream.cancelled / final response anyway.
        // The agent.cancel branch (cancelAllPendingPermissions) is what
        // handles the in-flight dismissal case.
        synchronized (permissionLifecycleLock) {
            pendingPermissions.forEach((rid, future) -> {
                future.cancel(false);
                if (globalPermissionRegistry != null) {
                    // Drop only OUR entries from the daemon-wide registry —
                    // other concurrent prompts may still have pending
                    // permissions there. Iterating pendingPermissions
                    // (per-context) gives us the correct subset.
                    //
                    // Match by future identity (not the wrapping
                    // PendingPermission record): the registry value
                    // wraps the same future together with sessionId
                    // and context, so a naive remove(rid, future)
                    // wouldn't equal the wrapper and would silently
                    // leak stale entries. Look up first, then remove
                    // by record-equality only when the contained
                    // future matches.
                    var pending = globalPermissionRegistry.get(rid);
                    if (pending != null && pending.future() == future) {
                        globalPermissionRegistry.remove(rid, pending);
                    }
                }
            });
            pendingPermissions.clear();
        }
        var sel = selector;
        if (sel != null) {
            sel.wakeup();
        }
        var thread = monitorThread;
        if (thread != null) {
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            monitorThread = null;
        }
    }

    private void monitorLoop() {
        try {
            channel.configureBlocking(false);
            selector = Selector.open();
            channel.register(selector, SelectionKey.OP_READ);

            var buffer = ByteBuffer.allocate(READ_BUFFER_SIZE);

            while (!stopped && !cancellationToken.isCancelled()) {
                int ready = selector.select(SELECT_TIMEOUT_MS);
                if (stopped || cancellationToken.isCancelled()) break;
                if (ready == 0) continue;

                selector.selectedKeys().clear();

                buffer.clear();
                int bytesRead = channel.read(buffer);
                if (bytesRead == -1) {
                    log.debug("Cancel monitor: connection closed, triggering cancellation");
                    cancellationToken.cancel();
                    return;
                }
                if (bytesRead == 0) continue;

                buffer.flip();
                synchronized (lineBuilder) {
                    lineBuilder.append(StandardCharsets.UTF_8.decode(buffer));

                    // Process complete lines
                    int newlineIdx;
                    while ((newlineIdx = lineBuilder.indexOf("\n")) != -1) {
                        var line = lineBuilder.substring(0, newlineIdx).trim();
                        lineBuilder.delete(0, newlineIdx + 1);

                        if (line.isEmpty()) continue;

                        try {
                            var message = objectMapper.readTree(line);
                            String method = message.has("method")
                                    ? message.get("method").asText("") : "";

                            if ("agent.cancel".equals(method)) {
                                log.info("Cancel monitor: received agent.cancel");
                                cancellationToken.cancel();
                                // Unblock PermissionAwareTool.execute's future.get():
                                // setting the cancellation token alone doesn't propagate to
                                // CompletableFutures, so without this the turn sits blocked
                                // until PERMISSION_RESPONSE_TIMEOUT_MS (120 s) — and because
                                // the request never completes, stopMonitor() never runs and
                                // the CLI's y/n modal stays on screen waiting on stdin.
                                cancelAllPendingPermissions("agent-cancel");
                                return;
                            } else if ("permission.response".equals(method)) {
                                log.debug("Cancel monitor: routing permission.response");
                                var respParams = message.get("params");
                                var rid = respParams != null && respParams.has("requestId")
                                        ? respParams.get("requestId").asText() : null;
                                if (rid != null) {
                                    var future = pendingPermissions.remove(rid);
                                    if (future != null) {
                                        // Keep the daemon-wide registry in sync —
                                        // only when THIS context owned the request
                                        // (local future found). Removing
                                        // unconditionally would let a stale or
                                        // cross-session CLI message delete another
                                        // context's legitimate entry, causing the
                                        // browser response for that request to be
                                        // rejected as "unknown requestId" until
                                        // timeout (codex P1 review on PR #454).
                                        if (globalPermissionRegistry != null) {
                                            globalPermissionRegistry.remove(rid);
                                        }
                                        future.complete(message);
                                    } else {
                                        log.warn("Cancel monitor: no pending request for requestId={}, dropping stale permission.response", rid);
                                    }
                                } else {
                                    log.warn("Cancel monitor: permission.response missing requestId, dropping message");
                                }
                            } else if ("resume.response".equals(method)
                                    || "resume.choice_response".equals(method)) {
                                // resume.choice_response added for #501 — explicit /continue
                                // surfaces a numbered choice when both plan + turn checkpoints
                                // resume. Without this branch the monitor silently dropped the
                                // client's pick and offerResumeChoicesAndWaitForResponse hung
                                // for the full 30s timeout under the per-session turnLock.
                                log.debug("Cancel monitor: routing {} to fallback", method);
                                unmatchedResponses.offer(message);
                            } else {
                                log.debug("Cancel monitor: ignoring '{}'", method);
                            }
                        } catch (Exception e) {
                            log.warn("Cancel monitor: failed to parse message: {}", e.getMessage());
                        }
                    }
                }
            }
        } catch (IOException e) {
            if (!stopped && !cancellationToken.isCancelled()) {
                log.debug("Cancel monitor: I/O error: {}", e.getMessage());
                cancellationToken.cancel();
            }
        } finally {
            // Restore blocking mode so subsequent reads work normally
            try {
                if (selector != null) selector.close();
                if (channel != null && channel.isOpen()) {
                    channel.configureBlocking(true);
                }
            } catch (IOException e) {
                log.warn("Cancel monitor: failed to restore blocking mode: {}", e.getMessage());
            }
            selector = null;
        }
    }

    @Override
    public void sendNotification(String method, Object params) throws IOException {
        if ("stream.tool_use".equals(method)) {
            toolUseCount.incrementAndGet();
        }
        delegate.sendNotification(method, params);
    }

    /**
     * Reads the next unmatched response from the fallback queue.
     * Used by ResumeRouter and other non-permission reads.
     * Returns null if cancelled or the monitor thread is no longer running.
     */
    @Override
    public JsonNode readMessage() throws IOException {
        return readMessage(PERMISSION_RESPONSE_TIMEOUT_MS);
    }

    @Override
    public JsonNode readMessage(long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!cancellationToken.isCancelled()) {
            long now = System.currentTimeMillis();
            long remaining = deadline - now;
            if (remaining <= 0) {
                throw new SocketTimeoutException("response timeout after " + timeoutMs + "ms");
            }
            try {
                JsonNode msg = unmatchedResponses.poll(Math.min(500, remaining), TimeUnit.MILLISECONDS);
                if (msg != null) {
                    return msg;
                }
                // Check if the monitor thread is still alive
                var thread = monitorThread;
                if (thread == null || !thread.isAlive()) {
                    return null;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }
}

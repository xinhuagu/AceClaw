package dev.aceclaw.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Bridges permission requests from task streaming threads to the main REPL thread.
 *
 * <p>Task threads call {@link #requestPermission(PermissionRequest)} which enqueues
 * the request and blocks until the main thread resolves it via
 * {@link #submitAnswer(String, PermissionAnswer)}.
 *
 * <p>The main thread polls with {@link #pollPending(long, TimeUnit)} and prompts
 * the user for a decision.
 */
public final class PermissionBridge {

    private static final Logger log = LoggerFactory.getLogger(PermissionBridge.class);

    private final BlockingQueue<PermissionRequest> pending = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<String, CompletableFuture<PermissionAnswer>> futures =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PermissionAnswer> resolvedAnswers =
            new ConcurrentHashMap<>();
    /**
     * Tracks externally resolved request ids — answered by another client
     * (typically the dashboard via the WebSocket bridge, issue #437) while
     * a CLI prompt was still showing. The TUI's blocking terminal-read
     * loop polls {@link #consumeExternalCancellation(String)} each
     * iteration so it can dismiss the y/n prompt instead of waiting on
     * stdin that has no effect anymore.
     */
    private final ConcurrentHashMap<String, PermissionAnswer> externalCancellations =
            new ConcurrentHashMap<>();
    private volatile RequestListener requestListener;

    /**
     * Listener invoked when a new permission request is enqueued.
     */
    @FunctionalInterface
    public interface RequestListener {
        void onPermissionRequested(PermissionRequest request);
    }

    /**
     * Registers a listener for new permission requests.
     */
    public void setRequestListener(RequestListener listener) {
        this.requestListener = listener;
    }

    /**
     * Called by a task thread to request permission. Blocks until the main thread answers.
     *
     * @param request the permission details
     * @return the user's answer
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public PermissionAnswer requestPermission(PermissionRequest request) throws InterruptedException {
        try {
            return requestPermission(request, 0, null);
        } catch (TimeoutException e) {
            throw new IllegalStateException("Unexpected timeout for unbounded permission request", e);
        }
    }

    /**
     * Called by a task thread to request permission with an optional timeout.
     * Blocks until the main thread answers or the timeout elapses.
     *
     * @param request the permission details
     * @param timeout how long to wait; {@code <= 0} means wait indefinitely
     * @param unit    the timeout unit; ignored when {@code timeout <= 0}
     * @return the user's answer
     * @throws InterruptedException if the thread is interrupted while waiting
     * @throws TimeoutException     if the timeout elapses before the main thread answers
     */
    public PermissionAnswer requestPermission(PermissionRequest request, long timeout, TimeUnit unit)
            throws InterruptedException, TimeoutException {
        Objects.requireNonNull(request, "request");
        // Race window: TaskStreamReader handles permission.request on a
        // virtual thread now (#437), so an out-of-order
        // permission.cancelled from the daemon can land BEFORE this
        // call registers its future. cancelExternal stores the answer
        // in externalCancellations either way; here we drain that map
        // before blocking. Pre-check + post-register re-check closes
        // the sub-millisecond gap where a cancellation lands between
        // the two operations.
        var earlyCancel = externalCancellations.remove(request.requestId());
        if (earlyCancel != null) {
            return earlyCancel;
        }
        var future = new CompletableFuture<PermissionAnswer>();
        futures.put(request.requestId(), future);
        // Re-check after register: covers the race where cancelExternal
        // ran concurrently and saw no future, but its externalCancellations
        // entry was added between our pre-check and futures.put. Without
        // this, requestPermission would block until timeout for a
        // dashboard answer that already landed.
        var lateCancel = externalCancellations.remove(request.requestId());
        if (lateCancel != null) {
            future.complete(lateCancel);
        }
        boolean enqueued = false;
        try {
            pending.put(request);
            enqueued = true;
            var listener = requestListener;
            if (listener != null) {
                try {
                    listener.onPermissionRequested(request);
                } catch (Exception e) {
                    log.debug("Permission request listener failed: {}", e.getMessage());
                }
            }
            if (timeout <= 0) {
                return future.get();
            }
            Objects.requireNonNull(unit, "unit");
            return future.get(timeout, unit);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Permission request failed unexpectedly", e);
        } finally {
            futures.remove(request.requestId());
            if (enqueued) {
                pending.remove(request);
            }
        }
    }

    /**
     * Polls for a pending permission request (non-blocking or with timeout).
     *
     * @param timeout the maximum time to wait
     * @param unit    the time unit
     * @return the next pending request, or null if none available within timeout
     * @throws InterruptedException if interrupted while waiting
     */
    public PermissionRequest pollPending(long timeout, TimeUnit unit) throws InterruptedException {
        return pending.poll(timeout, unit);
    }

    /**
     * Checks whether there is a pending permission request without blocking.
     *
     * @return true if at least one request is pending
     */
    public boolean hasPending() {
        return !pending.isEmpty();
    }

    /**
     * Returns the number of pending permission requests.
     */
    public int pendingCount() {
        return pending.size();
    }

    /**
     * Returns a snapshot of pending requests.
     */
    public java.util.List<PermissionRequest> pendingSnapshot() {
        return java.util.List.copyOf(pending);
    }

    /**
     * Takes the next pending permission request (blocking).
     *
     * @return the next pending request
     * @throws InterruptedException if interrupted while waiting
     */
    public PermissionRequest takePending() throws InterruptedException {
        return pending.take();
    }

    /**
     * Called by the main thread to deliver the user's answer for a permission request.
     *
     * @param requestId the request ID to resolve
     * @param answer    the user's decision
     */
    public void submitAnswer(String requestId, PermissionAnswer answer) {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(answer, "answer");
        var future = futures.get(requestId);
        if (future != null) {
            resolvedAnswers.put(requestId, answer);
            future.complete(answer);
        } else {
            log.warn("No pending permission future for requestId={}, answer dropped", requestId);
        }
    }

    /**
     * Records that {@code requestId} has been resolved externally — the
     * daemon completed its tool-execution future via another path (e.g.
     * the dashboard answered first via the WebSocket bridge, #437). The
     * task thread's pending {@link #requestPermission} call is unblocked
     * with {@code answer}, and the TUI's blocking terminal-read loop
     * picks up the cancellation via
     * {@link #consumeExternalCancellation(String)} so it can dismiss
     * its modal instead of waiting on stdin that no longer matters.
     *
     * <p>Idempotent: repeating the call for an already-cancelled
     * requestId is a no-op.
     */
    public void cancelExternal(String requestId, PermissionAnswer answer) {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(answer, "answer");
        externalCancellations.putIfAbsent(requestId, answer);
        var future = futures.get(requestId);
        if (future != null) {
            future.complete(answer);
        }
    }

    /**
     * Returns and clears any pending external cancellation for the given
     * request id. The TUI's modal calls this once per polling tick so a
     * cancellation that arrives mid-prompt can dismiss the modal.
     */
    public PermissionAnswer consumeExternalCancellation(String requestId) {
        Objects.requireNonNull(requestId, "requestId");
        return externalCancellations.remove(requestId);
    }

    /**
     * Drops any external-cancellation entry left over for {@code requestId}.
     * Called by the modal cleanup so a cancellation that arrived AFTER
     * the user had already typed y/n (i.e. cancelExternal completed the
     * future a millisecond too late) doesn't leak into the map for the
     * lifetime of the CLI process. Idempotent.
     */
    private void clearExternalCancellation(String requestId) {
        externalCancellations.remove(requestId);
    }

    /**
     * Returns and clears a previously resolved answer for a request, if present.
     *
     * <p>This is used by the UI polling loop to detect requests that were
     * auto-resolved before the interactive permission dialog was shown.
     */
    public PermissionAnswer consumeResolvedAnswer(String requestId) {
        if (requestId == null || requestId.isBlank()) return null;
        // Also drop any external-cancellation entry the modal didn't
        // pick up (e.g. user typed y a millisecond before the daemon's
        // permission.cancelled arrived) so the map doesn't leak entries
        // for the lifetime of the CLI.
        clearExternalCancellation(requestId);
        return resolvedAnswers.remove(requestId);
    }

    /**
     * A permission request from a task thread.
     */
    public record PermissionRequest(
            String taskId,
            String tool,
            String description,
            String requestId
    ) {
        public PermissionRequest {
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(requestId, "requestId");
        }
    }

    /**
     * The user's answer to a permission request.
     *
     * <p>{@code external = true} means the daemon already resolved the
     * request via another client (typically the dashboard, #437). The
     * task thread receiving this answer must NOT re-send a
     * permission.response over UDS — the daemon-side future is already
     * done and a duplicate is dropped on the floor by the cancel
     * monitor's stale-response guard.
     */
    public record PermissionAnswer(boolean approved, boolean remember, boolean external) {
        public PermissionAnswer(boolean approved, boolean remember) {
            this(approved, remember, false);
        }
    }
}

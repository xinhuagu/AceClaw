package dev.aceclaw.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

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
        Objects.requireNonNull(request, "request");
        var future = new CompletableFuture<PermissionAnswer>();
        futures.put(request.requestId(), future);
        pending.put(request);
        var listener = requestListener;
        if (listener != null) {
            try {
                listener.onPermissionRequested(request);
            } catch (Exception e) {
                log.debug("Permission request listener failed: {}", e.getMessage());
            }
        }
        try {
            return future.join();
        } finally {
            futures.remove(request.requestId());
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
        resolvedAnswers.put(requestId, answer);
        var future = futures.get(requestId);
        if (future != null) {
            future.complete(answer);
        } else {
            log.warn("No pending permission future for requestId={}, answer dropped", requestId);
        }
    }

    /**
     * Returns and clears a previously resolved answer for a request, if present.
     *
     * <p>This is used by the UI polling loop to detect requests that were
     * auto-resolved before the interactive permission dialog was shown.
     */
    public PermissionAnswer consumeResolvedAnswer(String requestId) {
        if (requestId == null || requestId.isBlank()) return null;
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
     */
    public record PermissionAnswer(boolean approved, boolean remember) {}
}

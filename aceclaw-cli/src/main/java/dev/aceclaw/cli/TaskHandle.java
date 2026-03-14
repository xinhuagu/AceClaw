package dev.aceclaw.cli;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Represents a running or completed agent task.
 *
 * <p>Each task maps to one {@code agent.prompt} request sent on its own
 * {@link DaemonConnection}. The streaming loop runs on a virtual thread,
 * and the task state is updated atomically as the task progresses.
 *
 * <p>The output sink is held in an {@link AtomicReference} so that
 * it can be swapped at runtime (e.g. foreground → background buffer).
 */
public final class TaskHandle {

    /** Unique identifier for this task. */
    private final String taskId;

    /** First 60 chars of the user prompt (for display in /tasks). */
    private final String promptSummary;

    /** Dedicated connection for this task's streaming I/O. */
    private final DaemonConnection connection;

    /** Swappable output sink — enables /bg and /fg transitions. */
    private final AtomicReference<OutputSink> sinkRef;

    /** Virtual thread running the stream reader loop. */
    private volatile Thread streamThread;

    /** Client-side cancellation flag. */
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /** Whether background completion has been displayed to the user. */
    private final AtomicBoolean completionNotified = new AtomicBoolean(false);

    /** When the task was submitted. */
    private final Instant startedAt;

    /** Current task state (volatile for cross-thread visibility). */
    private volatile TaskState state;

    /** Final JSON-RPC result (null while running). */
    private volatile JsonNode result;

    /** Last time we received task activity from the daemon stream. */
    private volatile Instant lastActivityAt;

    /** Human-readable activity label (e.g. thinking, tool:bash). */
    private volatile String activityLabel;

    /** Whether the task is currently waiting on a permission decision. */
    private volatile boolean waitingPermission;

    /** Optional permission description shown while waiting. */
    private volatile String permissionDetail;

    /** Latest per-call input tokens from the most recent LLM call (real-time). */
    private volatile long liveInputTokens;
    /** Context window size in tokens (0 if unknown). */
    private final int contextWindow;
    /** Recent tool events for resume checkpointing. */
    private final Deque<ToolEvent> recentToolEvents;

    public TaskHandle(String taskId, String promptSummary, DaemonConnection connection,
                      OutputSink initialSink, int contextWindow) {
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.promptSummary = promptSummary != null && promptSummary.length() > 60
                ? promptSummary.substring(0, 60) + "..."
                : promptSummary;
        this.connection = Objects.requireNonNull(connection, "connection");
        this.sinkRef = new AtomicReference<>(Objects.requireNonNull(initialSink, "initialSink"));
        this.startedAt = Instant.now();
        this.state = TaskState.RUNNING;
        this.lastActivityAt = this.startedAt;
        this.activityLabel = "starting";
        this.waitingPermission = false;
        this.permissionDetail = "";
        this.contextWindow = Math.max(0, contextWindow);
        this.recentToolEvents = new ArrayDeque<>();
    }

    public String taskId() { return taskId; }
    public String promptSummary() { return promptSummary; }
    public DaemonConnection connection() { return connection; }
    public Instant startedAt() { return startedAt; }
    public TaskState state() { return state; }
    public JsonNode result() { return result; }
    public AtomicBoolean cancelled() { return cancelled; }
    public Instant lastActivityAt() { return lastActivityAt; }
    public String activityLabel() { return activityLabel; }
    public boolean waitingPermission() { return waitingPermission; }
    public String permissionDetail() { return permissionDetail; }
    public List<ToolEvent> recentToolEventsSnapshot() {
        synchronized (recentToolEvents) {
            return List.copyOf(recentToolEvents);
        }
    }

    /**
     * Returns the current output sink.
     */
    public OutputSink outputSink() { return sinkRef.get(); }

    /**
     * Atomically swaps the output sink and returns the previous one.
     * Used for /bg (swap to BackgroundOutputBuffer) and /fg (swap to ForegroundOutputSink).
     *
     * @param newSink the new sink to install
     * @return the previous sink
     */
    public OutputSink swapOutputSink(OutputSink newSink) {
        return sinkRef.getAndSet(Objects.requireNonNull(newSink, "newSink"));
    }

    /**
     * Marks this task's completion as notified. Returns true if this is the first call
     * (i.e., the notification hasn't been shown yet).
     */
    public boolean markNotified() { return completionNotified.compareAndSet(false, true); }

    public Thread streamThread() { return streamThread; }
    public void setStreamThread(Thread thread) { this.streamThread = thread; }

    public void setState(TaskState state) { this.state = state; }
    public void setResult(JsonNode result) { this.result = result; }

    /**
     * Marks daemon-stream activity for this task and optionally updates a label.
     */
    public void markActivity(String label) {
        this.lastActivityAt = Instant.now();
        this.waitingPermission = false;
        this.permissionDetail = "";
        if (label != null && !label.isBlank()) {
            this.activityLabel = label;
        }
    }

    /**
     * Marks that this task is blocked waiting for user permission.
     */
    public void markWaitingPermission(String detail) {
        this.lastActivityAt = Instant.now();
        this.waitingPermission = true;
        this.permissionDetail = detail != null ? detail : "";
        this.activityLabel = "awaiting permission";
    }

    /**
     * Clears permission-blocked status when the user responds.
     */
    public void clearWaitingPermission() {
        this.lastActivityAt = Instant.now();
        this.waitingPermission = false;
        this.permissionDetail = "";
        this.activityLabel = "resumed";
    }

    /**
     * Appends a tool/runtime event for resume checkpointing.
     */
    public void appendToolEvent(String toolName, String eventType, boolean isError, long durationMs, String summary) {
        var event = new ToolEvent(
                toolName != null ? toolName : "unknown",
                eventType != null ? eventType : "unknown",
                Instant.now(),
                isError,
                durationMs,
                summary != null ? summary : ""
        );
        synchronized (recentToolEvents) {
            recentToolEvents.addLast(event);
            if (recentToolEvents.size() > 20) {
                recentToolEvents.removeFirst();
            }
        }
    }

    public long liveInputTokens() { return liveInputTokens; }
    public void setLiveInputTokens(long tokens) { this.liveInputTokens = tokens; }
    public int contextWindow() { return contextWindow; }

    public boolean isRunning() { return state == TaskState.RUNNING; }
    public boolean isTerminal() {
        return state == TaskState.COMPLETED || state == TaskState.FAILED
                || state == TaskState.CANCELLED;
    }

    /**
     * Task lifecycle states.
     */
    public enum TaskState {
        RUNNING, COMPLETED, FAILED, CANCELLED
    }

    public record ToolEvent(
            String toolName,
            String eventType,
            Instant timestamp,
            boolean isError,
            long durationMs,
            String summary
    ) {}
}

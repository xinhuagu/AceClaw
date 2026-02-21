package dev.aceclaw.cli;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Represents a running or completed agent task.
 *
 * <p>Each task maps to one {@code agent.prompt} request sent on its own
 * {@link DaemonConnection}. The streaming loop runs on a virtual thread,
 * and the task state is updated atomically as the task progresses.
 */
public final class TaskHandle {

    /** Unique identifier for this task. */
    private final String taskId;

    /** First 60 chars of the user prompt (for display in /tasks). */
    private final String promptSummary;

    /** Dedicated connection for this task's streaming I/O. */
    private final DaemonConnection connection;

    /** Virtual thread running the stream reader loop. */
    private volatile Thread streamThread;

    /** Client-side cancellation flag. */
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /** When the task was submitted. */
    private final Instant startedAt;

    /** Current task state (volatile for cross-thread visibility). */
    private volatile TaskState state;

    /** Final JSON-RPC result (null while running). */
    private volatile JsonNode result;

    public TaskHandle(String taskId, String promptSummary, DaemonConnection connection) {
        this.taskId = taskId;
        this.promptSummary = promptSummary != null && promptSummary.length() > 60
                ? promptSummary.substring(0, 60) + "..."
                : promptSummary;
        this.connection = connection;
        this.startedAt = Instant.now();
        this.state = TaskState.RUNNING;
    }

    public String taskId() { return taskId; }
    public String promptSummary() { return promptSummary; }
    public DaemonConnection connection() { return connection; }
    public Instant startedAt() { return startedAt; }
    public TaskState state() { return state; }
    public JsonNode result() { return result; }
    public AtomicBoolean cancelled() { return cancelled; }

    public Thread streamThread() { return streamThread; }
    public void setStreamThread(Thread thread) { this.streamThread = thread; }

    public void setState(TaskState state) { this.state = state; }
    public void setResult(JsonNode result) { this.result = result; }

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
}

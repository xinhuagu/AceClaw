package dev.aceclaw.cli;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages concurrent agent tasks, each running on its own
 * {@link DaemonConnection} and virtual thread.
 *
 * <p>Supports foreground/background semantics: at most one task
 * is "foreground" (rendering to terminal), others are buffered in background.
 */
public final class TaskManager {

    private static final Logger log = LoggerFactory.getLogger(TaskManager.class);

    private final ConcurrentHashMap<String, TaskHandle> tasks = new ConcurrentHashMap<>();
    private final AtomicInteger taskSeq = new AtomicInteger(1);

    /** The task ID currently rendering to the terminal (null = none). */
    private volatile String foregroundTaskId;

    /**
     * Callback invoked when any task completes (on the task's virtual thread).
     */
    @FunctionalInterface
    public interface TaskCompleteCallback {
        void onTaskComplete(TaskHandle handle);
    }

    private volatile TaskCompleteCallback onTaskComplete;

    public void setOnTaskComplete(TaskCompleteCallback callback) {
        this.onTaskComplete = callback;
    }

    /**
     * Submits a new agent task.
     *
     * @param prompt           the user prompt
     * @param connection       dedicated connection for this task
     * @param sessionId        daemon session ID
     * @param outputSink       where streaming output goes
     * @param permissionBridge bridge for permission requests
     * @return the created TaskHandle
     */
    public TaskHandle submit(String prompt, DaemonConnection connection, String sessionId,
                             OutputSink outputSink, PermissionBridge permissionBridge) {
        String taskId = String.valueOf(taskSeq.getAndIncrement());
        var handle = new TaskHandle(taskId, prompt, connection);
        tasks.put(taskId, handle);

        // Send agent.prompt request on the task connection
        var reader = new TaskStreamReader(handle, connection, sessionId,
                prompt, outputSink, permissionBridge, this::handleTaskComplete);

        Thread thread = Thread.ofVirtual()
                .name("aceclaw-task-" + taskId)
                .start(reader);
        handle.setStreamThread(thread);

        log.debug("Submitted task {} with prompt: {}", taskId, handle.promptSummary());
        return handle;
    }

    /**
     * Sets the given task as the foreground task.
     */
    public void setForeground(String taskId) {
        this.foregroundTaskId = taskId;
    }

    /**
     * Returns the current foreground task ID, or null if none.
     */
    public String foregroundTaskId() {
        return foregroundTaskId;
    }

    /**
     * Returns the foreground task handle, or null if none.
     */
    public TaskHandle foregroundTask() {
        String id = foregroundTaskId;
        return id != null ? tasks.get(id) : null;
    }

    /**
     * Returns true if the foreground task has finished (completed/failed/cancelled).
     */
    public boolean foregroundCompleted() {
        var handle = foregroundTask();
        return handle != null && handle.isTerminal();
    }

    /**
     * Clears the foreground task reference (called after rendering completion).
     */
    public void clearForeground() {
        foregroundTaskId = null;
    }

    /**
     * Returns true if a foreground task is currently running.
     */
    public boolean hasForegroundTask() {
        var handle = foregroundTask();
        return handle != null && handle.isRunning();
    }

    /**
     * Cancels the foreground task by sending {@code agent.cancel} on its connection.
     */
    public void cancelForeground() {
        var handle = foregroundTask();
        if (handle != null && handle.isRunning()) {
            cancel(handle);
        }
    }

    /**
     * Cancels a specific task.
     */
    public void cancel(String taskId) {
        var handle = tasks.get(taskId);
        if (handle != null && handle.isRunning()) {
            cancel(handle);
        }
    }

    /**
     * Returns a snapshot of all tasks, sorted by task ID (numeric order).
     */
    public List<TaskHandle> list() {
        var result = new ArrayList<>(tasks.values());
        result.sort(Comparator.comparing(h -> Integer.parseInt(h.taskId())));
        return result;
    }

    /**
     * Returns a specific task by ID.
     */
    public TaskHandle get(String taskId) {
        return tasks.get(taskId);
    }

    /**
     * Returns the number of currently running tasks.
     */
    public int runningCount() {
        return (int) tasks.values().stream().filter(TaskHandle::isRunning).count();
    }

    // -- internal --------------------------------------------------------

    private void cancel(TaskHandle handle) {
        if (handle.cancelled().compareAndSet(false, true)) {
            try {
                ObjectNode params = handle.connection().objectMapper().createObjectNode();
                params.put("sessionId", ""); // session ID is bound to the connection
                handle.connection().sendNotification("agent.cancel", params);
                log.debug("Sent cancel for task {}", handle.taskId());
            } catch (IOException e) {
                log.warn("Failed to send cancel for task {}: {}", handle.taskId(), e.getMessage());
            }
        }
    }

    private void handleTaskComplete(TaskHandle handle) {
        log.debug("Task {} completed with state {}", handle.taskId(), handle.state());
        var callback = onTaskComplete;
        if (callback != null) {
            callback.onTaskComplete(handle);
        }
    }
}

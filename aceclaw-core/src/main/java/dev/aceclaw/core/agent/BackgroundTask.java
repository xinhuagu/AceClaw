package dev.aceclaw.core.agent;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Represents a sub-agent task running in the background on a virtual thread.
 *
 * <p>Background tasks are launched via {@code SubAgentRunner.runInBackground()}
 * and can be polled or awaited via {@code SubAgentRunner.getBackgroundTask()}.
 *
 * @param taskId      unique identifier for this background task
 * @param agentType   the agent type name (e.g., "explore", "general")
 * @param prompt      the original task prompt
 * @param future      the completable future tracking the async execution
 * @param startedAt   when the task was launched
 * @param completedAt atomically set when the future completes (for cleanup timing)
 */
public record BackgroundTask(
        String taskId,
        String agentType,
        String prompt,
        CompletableFuture<SubAgentResult> future,
        Instant startedAt,
        AtomicReference<Instant> completedAt
) {

    /**
     * Creates a background task and registers a completion callback to record
     * the completion timestamp.
     */
    public BackgroundTask(String taskId, String agentType, String prompt,
                          CompletableFuture<SubAgentResult> future, Instant startedAt) {
        this(taskId, agentType, prompt, future, startedAt, new AtomicReference<>());
        future.whenComplete((_, _) -> completedAt.set(Instant.now()));
    }

    /**
     * Status of a background task.
     */
    public enum Status {
        RUNNING, COMPLETED, FAILED
    }

    /**
     * Derives the current status from the future's state.
     */
    public Status currentStatus() {
        if (!future.isDone()) {
            return Status.RUNNING;
        }
        if (future.isCompletedExceptionally()) {
            return Status.FAILED;
        }
        return Status.COMPLETED;
    }
}

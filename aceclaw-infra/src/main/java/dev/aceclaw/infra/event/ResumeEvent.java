package dev.aceclaw.infra.event;

import java.time.Instant;

/**
 * Events emitted during plan checkpoint resume lifecycle.
 *
 * <p>Permits:
 * <ul>
 *   <li>{@link Detected} — a resumable checkpoint was found for this session/workspace</li>
 *   <li>{@link BoundTask} — a checkpoint was bound to a new session for resumption</li>
 *   <li>{@link Injected} — resume context was injected into the agent loop</li>
 *   <li>{@link Fallback} — resume was declined or failed, falling back to fresh execution</li>
 * </ul>
 */
public sealed interface ResumeEvent extends AceClawEvent {

    /** The plan identifier. */
    String planId();

    /** A resumable checkpoint was detected for this session/workspace. */
    record Detected(
            String planId,
            String sessionId,
            String workspaceHash,
            int completedSteps,
            int totalSteps,
            String route,
            Instant timestamp
    ) implements ResumeEvent {}

    /** A checkpoint was bound to a new session for resumption. */
    record BoundTask(
            String planId,
            String originalSessionId,
            String newSessionId,
            Instant timestamp
    ) implements ResumeEvent {}

    /** Resume context was injected into the agent loop. */
    record Injected(
            String planId,
            int resumeFromStep,
            int totalSteps,
            Instant timestamp
    ) implements ResumeEvent {}

    /** Resume was offered but rejected/failed; falling back to fresh execution. */
    record Fallback(
            String planId,
            String reason,
            Instant timestamp
    ) implements ResumeEvent {}
}

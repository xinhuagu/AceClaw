package dev.aceclaw.infra.event;

import java.time.Instant;

/**
 * Events emitted by the deferred action scheduler during action lifecycle.
 *
 * <p>Permits:
 * <ul>
 *   <li>{@link ActionScheduled} — a deferred action has been scheduled</li>
 *   <li>{@link ActionTriggered} — a deferred action has been triggered and is starting execution</li>
 *   <li>{@link ActionCompleted} — a deferred action has completed successfully</li>
 *   <li>{@link ActionFailed} — a deferred action has failed (with optional retry info)</li>
 *   <li>{@link ActionExpired} — a deferred action expired before it could run</li>
 *   <li>{@link ActionCancelled} — a deferred action was cancelled by the user or system</li>
 * </ul>
 */
public sealed interface DeferEvent extends AceClawEvent {

    /** The deferred action identifier. */
    String actionId();

    /** The session that owns this action. */
    String sessionId();

    record ActionScheduled(String actionId, String sessionId, String goal,
                           Instant runAt, Instant timestamp) implements DeferEvent {}

    record ActionTriggered(String actionId, String sessionId, Instant timestamp)
            implements DeferEvent {}

    record ActionCompleted(String actionId, String sessionId, long durationMs,
                           String summary, Instant timestamp) implements DeferEvent {}

    record ActionFailed(String actionId, String sessionId, String error,
                        int attempt, int maxAttempts, Instant timestamp) implements DeferEvent {}

    record ActionExpired(String actionId, String sessionId, Instant timestamp)
            implements DeferEvent {}

    record ActionCancelled(String actionId, String sessionId, String reason,
                           Instant timestamp) implements DeferEvent {}
}

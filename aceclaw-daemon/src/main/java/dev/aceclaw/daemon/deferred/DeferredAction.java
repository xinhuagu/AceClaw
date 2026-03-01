package dev.aceclaw.daemon.deferred;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Objects;

/**
 * An immutable deferred action record, serialized to/from JSON.
 *
 * @param actionId       unique action identifier
 * @param sessionId      owning session
 * @param idempotencyKey dedup key (sessionId + sha256(goal))
 * @param createdAt      when the action was scheduled
 * @param runAt          when the action should execute
 * @param expiresAt      when the action expires if not yet executed
 * @param goal           natural-language goal for the agent
 * @param maxRetries     maximum retry attempts on failure
 * @param attempts       number of attempts so far
 * @param state          current lifecycle state
 * @param lastError      last error message (null if no error)
 * @param lastOutput     last successful output summary (null if not completed)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeferredAction(
        String actionId,
        String sessionId,
        String idempotencyKey,
        Instant createdAt,
        Instant runAt,
        Instant expiresAt,
        String goal,
        int maxRetries,
        int attempts,
        DeferredActionState state,
        String lastError,
        String lastOutput
) {
    public DeferredAction {
        Objects.requireNonNull(actionId, "actionId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(runAt, "runAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(state, "state");
    }

    /**
     * Returns a copy with the given state.
     */
    public DeferredAction withState(DeferredActionState newState) {
        return new DeferredAction(actionId, sessionId, idempotencyKey, createdAt, runAt,
                expiresAt, goal, maxRetries, attempts, newState, lastError, lastOutput);
    }

    /**
     * Returns a copy with incremented attempt count.
     */
    public DeferredAction withAttempt() {
        return new DeferredAction(actionId, sessionId, idempotencyKey, createdAt, runAt,
                expiresAt, goal, maxRetries, attempts + 1, state, lastError, lastOutput);
    }

    /**
     * Returns a copy marked as successfully completed.
     */
    public DeferredAction withSuccess(String output) {
        return new DeferredAction(actionId, sessionId, idempotencyKey, createdAt, runAt,
                expiresAt, goal, maxRetries, attempts, DeferredActionState.COMPLETED,
                null, output);
    }

    /**
     * Returns a copy marked as failed with the given error.
     */
    public DeferredAction withFailure(String error) {
        DeferredActionState newState = (attempts + 1 >= maxRetries)
                ? DeferredActionState.FAILED
                : DeferredActionState.PENDING;
        return new DeferredAction(actionId, sessionId, idempotencyKey, createdAt, runAt,
                expiresAt, goal, maxRetries, attempts + 1, newState, error, lastOutput);
    }

    /**
     * Returns whether this action is due for execution.
     */
    public boolean isDue(Instant now) {
        return state == DeferredActionState.PENDING && !runAt.isAfter(now);
    }

    /**
     * Returns whether this action has expired.
     */
    public boolean isExpired(Instant now) {
        return state == DeferredActionState.PENDING && expiresAt.isBefore(now);
    }
}

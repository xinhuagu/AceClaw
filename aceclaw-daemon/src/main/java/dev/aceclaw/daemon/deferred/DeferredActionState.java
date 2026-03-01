package dev.aceclaw.daemon.deferred;

/**
 * Lifecycle state of a deferred action.
 */
public enum DeferredActionState {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    EXPIRED
}

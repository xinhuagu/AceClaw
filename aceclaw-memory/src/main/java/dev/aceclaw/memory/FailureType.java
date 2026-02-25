package dev.aceclaw.memory;

/**
 * Normalized runtime failure taxonomy used by the learning pipeline.
 */
public enum FailureType {
    PERMISSION_DENIED("permission_denied"),
    PERMISSION_PENDING_TIMEOUT("permission_pending_timeout"),
    TIMEOUT("timeout"),
    DEPENDENCY_MISSING("dependency_missing"),
    CAPABILITY_MISMATCH("capability_mismatch"),
    BROKEN("broken"),
    CANCELLED("cancelled");

    private final String wireName;

    FailureType(String wireName) {
        this.wireName = wireName;
    }

    /**
     * Stable lowercase identifier used in tags and serialized event content.
     */
    public String wireName() {
        return wireName;
    }
}

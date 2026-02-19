package dev.aceclaw.core.agent;

/**
 * Result of a tool permission check.
 *
 * @param allowed whether the tool execution is permitted
 * @param reason  human-readable reason when denied (null if allowed)
 */
public record ToolPermissionResult(boolean allowed, String reason) {

    /** Pre-built result for allowed operations. */
    public static final ToolPermissionResult ALLOWED = new ToolPermissionResult(true, null);

    /**
     * Creates a denied result with the given reason.
     */
    public static ToolPermissionResult denied(String reason) {
        return new ToolPermissionResult(false, reason);
    }
}

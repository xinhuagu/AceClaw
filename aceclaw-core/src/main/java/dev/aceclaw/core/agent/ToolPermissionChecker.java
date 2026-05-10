package dev.aceclaw.core.agent;

/**
 * Functional interface for checking tool permissions before execution.
 *
 * <p>This abstraction allows the core agent loop to delegate permission
 * decisions without depending on aceclaw-security directly (dependency inversion).
 *
 * <p>Implementations typically wrap a {@code PermissionManager} from aceclaw-security.
 *
 * <h3>Per-session sessionId — issue #457</h3>
 *
 * The 3-arg {@link #check(String, String, String)} entry point carries the
 * owning session id so a per-session allow-list cannot leak across sessions.
 * Pre-#457 the sub-agent path used a daemon-wide {@code hasAnySessionApproval}
 * lookup that returned true if <em>any</em> session had approved the tool —
 * meaning a sub-agent in workspace B would inherit a "remember" approval
 * granted in workspace A. The 3-arg form lets the daemon wire a per-session
 * predicate so that leak is closed.
 *
 * <p>The legacy 2-arg form is preserved as a deprecated default that
 * delegates with a {@code null} sessionId — implementations should override
 * the 3-arg form. New call sites must always use the 3-arg form so they
 * cannot accidentally drop the sessionId.
 */
public interface ToolPermissionChecker {

    /**
     * Checks whether the given tool is permitted to execute in the given
     * session. {@code sessionId} is the calling agent's session — for a
     * sub-agent path this is the parent session's id, so an approval
     * granted to the user in session A does NOT auto-approve a sub-agent
     * tool call originating in session B.
     *
     * @param toolName  the tool name (e.g. "bash", "write_file")
     * @param inputJson the raw JSON input to the tool
     * @param sessionId the owning session, or {@code null} for daemon-internal
     *                  paths that have no session in scope
     * @return the permission result
     */
    ToolPermissionResult check(String toolName, String inputJson, String sessionId);

    /**
     * Legacy 2-arg form retained for source compatibility with code that
     * predates #457. Delegates to the 3-arg form with {@code sessionId = null},
     * which means "treat as if no session is in scope" — fine for daemon-
     * internal callers, NOT fine for sub-agents (which is exactly the leak
     * #457 closes; new code must call the 3-arg form).
     *
     * @deprecated Use {@link #check(String, String, String)} so the
     *             session-scoped allow-list lookup works correctly.
     */
    @Deprecated
    default ToolPermissionResult check(String toolName, String inputJson) {
        return check(toolName, inputJson, null);
    }
}

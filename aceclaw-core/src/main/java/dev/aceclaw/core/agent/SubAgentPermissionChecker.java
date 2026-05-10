package dev.aceclaw.core.agent;

import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Permission checker for sub-agent tool execution.
 *
 * <p>Sub-agents cannot interactively prompt the user for approval, so this
 * checker auto-approves read-only tools and session-approved tools, and
 * denies everything else.
 *
 * <p>This prevents sub-agents from silently executing write/execute operations
 * that the user has not explicitly approved.
 *
 * <h3>Per-session scope (#457)</h3>
 *
 * Pre-#457 this checker used a daemon-wide
 * {@code hasAnySessionApproval(toolName)} lookup that returned true if any
 * session had approved the tool — meaning a sub-agent in workspace B would
 * silently inherit a "remember" decision the user made in workspace A.
 * Now keyed on {@code (sessionId, toolName)} so each session's allow-list
 * stays in its own scope.
 */
public final class SubAgentPermissionChecker implements ToolPermissionChecker {

    private final Set<String> readOnlyTools;
    private final BiPredicate<String, String> isSessionApproved;

    /**
     * Creates a sub-agent permission checker.
     *
     * @param readOnlyTools     tool names considered safe (auto-approved)
     * @param isSessionApproved predicate of {@code (sessionId, toolName) ->
     *                          isApproved} that does the per-session
     *                          allow-list lookup. Wire to
     *                          {@code permissionManager::hasSessionApproval}
     *                          on the daemon side.
     */
    public SubAgentPermissionChecker(Set<String> readOnlyTools,
                                     BiPredicate<String, String> isSessionApproved) {
        // Fail fast on miswiring: a null predicate would only surface on
        // the first non-read-only tool execution, where the NPE would
        // bubble up as a permission-check error well after construction.
        // Construction-time null-guard makes the wiring bug obvious at
        // daemon boot. (CodeRabbit review on #491.)
        Objects.requireNonNull(readOnlyTools, "readOnlyTools");
        this.readOnlyTools = Set.copyOf(readOnlyTools);
        this.isSessionApproved = Objects.requireNonNull(isSessionApproved, "isSessionApproved");
    }

    @Override
    public ToolPermissionResult check(String toolName, String inputJson, String sessionId) {
        // Read-only tools are always allowed
        if (readOnlyTools.contains(toolName)) {
            return ToolPermissionResult.ALLOWED;
        }

        // Tools with session-level approval are allowed — keyed by THIS
        // session's id so an approval granted in a different session does
        // not silently apply here. A null sessionId (daemon-internal path
        // that didn't supply one) cannot match any session-scoped approval,
        // so falls through to denied — fail-closed by construction.
        if (sessionId != null && isSessionApproved.test(sessionId, toolName)) {
            return ToolPermissionResult.ALLOWED;
        }

        // Everything else is denied — sub-agents cannot prompt for approval
        return ToolPermissionResult.denied(
                "Sub-agent cannot execute '" + toolName +
                "' without prior session approval. Approve this tool in the parent agent first.");
    }
}

package dev.aceclaw.core.agent;

import java.util.Set;
import java.util.function.Predicate;

/**
 * Permission checker for sub-agent tool execution.
 *
 * <p>Sub-agents cannot interactively prompt the user for approval, so this
 * checker auto-approves read-only tools and session-approved tools, and
 * denies everything else.
 *
 * <p>This prevents sub-agents from silently executing write/execute operations
 * that the user has not explicitly approved.
 */
public final class SubAgentPermissionChecker implements ToolPermissionChecker {

    private final Set<String> readOnlyTools;
    private final Predicate<String> isSessionApproved;

    /**
     * Creates a sub-agent permission checker.
     *
     * @param readOnlyTools     tool names considered safe (auto-approved)
     * @param isSessionApproved predicate that checks if a tool has session-level approval
     */
    public SubAgentPermissionChecker(Set<String> readOnlyTools,
                                      Predicate<String> isSessionApproved) {
        this.readOnlyTools = Set.copyOf(readOnlyTools);
        this.isSessionApproved = isSessionApproved;
    }

    @Override
    public ToolPermissionResult check(String toolName, String inputJson) {
        // Read-only tools are always allowed
        if (readOnlyTools.contains(toolName)) {
            return ToolPermissionResult.ALLOWED;
        }

        // Tools with session-level approval are allowed
        if (isSessionApproved.test(toolName)) {
            return ToolPermissionResult.ALLOWED;
        }

        // Everything else is denied — sub-agents cannot prompt for approval
        return ToolPermissionResult.denied(
                "Sub-agent cannot execute '" + toolName +
                "' without prior session approval. Approve this tool in the parent agent first.");
    }
}

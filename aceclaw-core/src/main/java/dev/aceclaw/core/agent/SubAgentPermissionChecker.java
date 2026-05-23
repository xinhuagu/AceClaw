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
 * Pre-#457 this checker used a daemon-wide allow-list lookup that returned
 * true if any session had approved the tool — meaning a sub-agent in
 * workspace B would silently inherit a "remember" decision the user made
 * in workspace A. Now keyed on {@code (sessionId, toolName)} so each
 * session's allow-list stays in its own scope.
 */
public final class SubAgentPermissionChecker implements ToolPermissionChecker {

    private final Set<String> readOnlyTools;
    private final BiPredicate<String, String> isSessionApproved;
    private final SubAgentStructuralCheck structuralCheck;

    /**
     * Creates a sub-agent permission checker.
     *
     * @param readOnlyTools     tool names considered safe (auto-approved)
     * @param isSessionApproved predicate of {@code (sessionId, toolName) ->
     *                          isApproved} that does the per-session
     *                          allow-list lookup. Wire to
     *                          {@code permissionManager::hasSessionApproval}
     *                          on the daemon side.
     * @param structuralCheck   probe that runs <em>before</em> the read-only
     *                          and session-approval shortcuts. Returns the
     *                          denial reason when the tool's intended
     *                          (toolName, inputJson) violates a cross-cutting
     *                          rule like "never write to {@code .env}", so
     *                          that a prior "always allow {@code write_file}"
     *                          approval cannot route a sub-agent past the
     *                          structural hard-denial layer (Codex P1 on
     *                          #495). Pass {@link SubAgentStructuralCheck#NONE}
     *                          in tests that don't exercise structural rules.
     */
    public SubAgentPermissionChecker(Set<String> readOnlyTools,
                                     BiPredicate<String, String> isSessionApproved,
                                     SubAgentStructuralCheck structuralCheck) {
        // Fail fast on miswiring: a null predicate would only surface on
        // the first non-read-only tool execution, where the NPE would
        // bubble up as a permission-check error well after construction.
        // Construction-time null-guard makes the wiring bug obvious at
        // daemon boot. (CodeRabbit review on #491.)
        Objects.requireNonNull(readOnlyTools, "readOnlyTools");
        this.readOnlyTools = Set.copyOf(readOnlyTools);
        this.isSessionApproved = Objects.requireNonNull(isSessionApproved, "isSessionApproved");
        this.structuralCheck = Objects.requireNonNull(structuralCheck, "structuralCheck");
    }

    @Override
    public ToolPermissionResult check(String toolName, String inputJson, String sessionId) {
        Objects.requireNonNull(toolName, "toolName");

        // Structural denials fire BEFORE any allow-list lookup. A prior
        // session-blanket approval for the tool name (e.g. "always allow
        // write_file") MUST NOT let a sub-agent target .env / .ssh /
        // /etc/ etc. — the "overrides every approval" invariant of the
        // hard-denial layer (Codex P1 on #495). The sessionId is forwarded
        // so the daemon-side probe can attribute the audit entry to the
        // originating session, matching the main-dispatcher's audit shape.
        String structuralReason = structuralCheck.denyReason(toolName, inputJson, sessionId);
        if (structuralReason != null) {
            return ToolPermissionResult.denied(structuralReason);
        }

        // Read-only tools are always allowed (structural rules don't cover
        // FileRead, so reaching here means no rule applied).
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

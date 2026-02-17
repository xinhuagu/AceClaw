package dev.chelava.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central permission manager that evaluates permission requests
 * against the active policy and tracks session-level approvals.
 *
 * <p>Thread-safe: may be called from multiple virtual threads concurrently.
 */
public final class PermissionManager {

    private static final Logger log = LoggerFactory.getLogger(PermissionManager.class);

    private final PermissionPolicy policy;

    /**
     * Tracks tool names that have been approved for the current session.
     * Key format: "toolName" for blanket approval, "toolName:detail" for specific approval.
     */
    private final Set<String> sessionApprovals = ConcurrentHashMap.newKeySet();

    public PermissionManager(PermissionPolicy policy) {
        this.policy = policy;
    }

    /**
     * Checks whether the given request is permitted.
     *
     * <p>First checks session-level approvals, then falls through to the policy.
     *
     * @param request the permission request
     * @return the decision
     */
    public PermissionDecision check(PermissionRequest request) {
        // Check session-level blanket approval for the tool
        if (sessionApprovals.contains(request.toolName())) {
            log.debug("Permission auto-approved (session blanket): tool={}", request.toolName());
            return new PermissionDecision.Approved();
        }

        // Delegate to policy
        var decision = policy.evaluate(request);
        log.debug("Permission check: tool={}, level={}, decision={}",
                request.toolName(), request.level(), decision.getClass().getSimpleName());
        return decision;
    }

    /**
     * Records a blanket session-level approval for a tool.
     * After this call, all future requests for this tool are auto-approved.
     *
     * @param toolName the tool name to approve for the session
     */
    public void approveForSession(String toolName) {
        sessionApprovals.add(toolName);
        log.info("Session-level approval granted for tool: {}", toolName);
    }

    /**
     * Clears all session-level approvals.
     */
    public void clearSessionApprovals() {
        sessionApprovals.clear();
        log.debug("Session approvals cleared");
    }

    /**
     * Returns whether the given tool has session-level blanket approval.
     */
    public boolean hasSessionApproval(String toolName) {
        return sessionApprovals.contains(toolName);
    }
}

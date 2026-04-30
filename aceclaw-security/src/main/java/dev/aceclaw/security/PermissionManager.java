package dev.aceclaw.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central permission manager that evaluates permission requests
 * against the active policy and tracks session-level approvals.
 *
 * <p>Thread-safe: may be called from multiple virtual threads concurrently.
 *
 * <h3>Per-session "remember" scope (issue #456)</h3>
 *
 * <p>Earlier versions kept a single shared {@code Set<String>} of
 * approved tool names for the entire daemon, which meant clicking
 * "Always allow" in session A silently disabled the prompt for that
 * tool in every other concurrent session — including sessions for
 * unrelated workspaces. Fix: keyed by sessionId so each session's
 * "remember" decision stays in its own scope. Sessions clear their
 * entry on destroy via {@link #clearSessionApprovals(String)}.
 */
public final class PermissionManager {

    private static final Logger log = LoggerFactory.getLogger(PermissionManager.class);

    private final PermissionPolicy policy;

    /**
     * Per-session blanket approvals: {@code sessionId → toolName set}.
     * Empty entries are pruned by {@link #clearSessionApprovals(String)}
     * so a long-lived daemon doesn't leak state for ended sessions.
     */
    private final Map<String, Set<String>> sessionApprovals = new ConcurrentHashMap<>();

    public PermissionManager(PermissionPolicy policy) {
        this.policy = policy;
    }

    /**
     * Checks whether the given request is permitted within the given
     * session.
     *
     * <p>Order: session-level blanket approval → policy. The
     * blanket-approval lookup is per-session — a tool approved in
     * session A is NOT auto-approved in session B (issue #456).
     *
     * @param request   the permission request
     * @param sessionId the session this check belongs to. {@code null}
     *                  skips the per-session allow-list lookup and
     *                  goes straight to the policy — useful for
     *                  daemon-internal checks where no session owns
     *                  the request.
     * @return the decision
     */
    public PermissionDecision check(PermissionRequest request, String sessionId) {
        Objects.requireNonNull(request, "request");
        if (sessionId != null) {
            var allow = sessionApprovals.get(sessionId);
            if (allow != null && allow.contains(request.toolName())) {
                log.debug("Permission auto-approved (session blanket): tool={}, sessionId={}",
                        request.toolName(), sessionId);
                return new PermissionDecision.Approved();
            }
        }
        var decision = policy.evaluate(request);
        log.debug("Permission check: tool={}, level={}, sessionId={}, decision={}",
                request.toolName(), request.level(), sessionId,
                decision.getClass().getSimpleName());
        return decision;
    }

    /**
     * Records a blanket session-level approval for a tool. After this
     * call, future requests for this tool from THIS session are
     * auto-approved (other sessions are unaffected — see #456).
     */
    public void approveForSession(String sessionId, String toolName) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(toolName, "toolName");
        sessionApprovals
                .computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet())
                .add(toolName);
        log.info("Session-level approval granted: tool={}, sessionId={}",
                toolName, sessionId);
    }

    /**
     * Clears the allow-list for a single session. Intended to be wired
     * to the daemon's session-destroy hook so allow-lists for ended
     * sessions don't leak indefinitely.
     */
    public void clearSessionApprovals(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        var removed = sessionApprovals.remove(sessionId);
        if (removed != null) {
            log.debug("Session approvals cleared for sessionId={}", sessionId);
        }
    }

    /**
     * Clears every session's allow-list. Test/admin helper — use
     * {@link #clearSessionApprovals(String)} for normal session
     * teardown.
     */
    public void clearAllSessionApprovals() {
        sessionApprovals.clear();
        log.debug("All session approvals cleared");
    }

    /**
     * Returns whether the tool has blanket approval in the given session.
     */
    public boolean hasSessionApproval(String sessionId, String toolName) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(toolName, "toolName");
        var allow = sessionApprovals.get(sessionId);
        return allow != null && allow.contains(toolName);
    }

    /**
     * Returns whether ANY session has blanket-approved the given tool.
     *
     * <p>Compatibility shim for the sub-agent permission checker: that
     * checker is constructed once at daemon startup and doesn't have a
     * session in scope at check time, so it can't ask the per-session
     * variant. Until the sub-agent path threads sessionId through
     * {@code ToolPermissionChecker} (separate refactor), this preserves
     * the pre-#456 behaviour where a session-approved tool was reachable
     * by sub-agents anywhere in the daemon.
     *
     * <p>This is permissive on purpose: a sub-agent in session B inherits
     * approvals granted in session A. The right scoping is per-session,
     * but losing the approval-flow entirely (always-deny) would regress
     * usability. Tracked as a follow-up.
     */
    public boolean hasAnySessionApproval(String toolName) {
        Objects.requireNonNull(toolName, "toolName");
        for (var allow : sessionApprovals.values()) {
            if (allow.contains(toolName)) return true;
        }
        return false;
    }
}

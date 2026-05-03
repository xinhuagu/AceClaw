package dev.aceclaw.security;

import dev.aceclaw.security.audit.CapabilityAuditLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
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

    /**
     * Optional signed audit log of every decision (#465 Layer 8 v1).
     * Null = audit disabled (default for unit tests that construct a
     * manager without disk I/O setup). When non-null, every call to
     * {@link #check} records one entry — best-effort, never throws.
     */
    private final CapabilityAuditLog auditLog;

    public PermissionManager(PermissionPolicy policy) {
        this(policy, null);
    }

    /**
     * Constructs a manager that signs and persists every decision to
     * {@code auditLog}. Pass {@code null} to disable auditing (this is
     * what the single-arg constructor does).
     */
    public PermissionManager(PermissionPolicy policy, CapabilityAuditLog auditLog) {
        this.policy = policy;
        this.auditLog = auditLog;
    }

    /**
     * Checks whether the given request is permitted within the given
     * session. <strong>Legacy entry point</strong> retained for callers that
     * still construct flat {@link PermissionRequest}s; under the hood it
     * wraps the request as a {@link Capability.LegacyToolUse} and delegates
     * to {@link #check(Capability, Provenance)}, so legacy and structured
     * paths share one decision-and-audit pipeline (#480 Layer 1, PR 2).
     *
     * <p>Order is unchanged: session-level blanket approval → policy. The
     * blanket-approval lookup is per-session — a tool approved in session A
     * is NOT auto-approved in session B (issue #456).
     *
     * @param request   the permission request
     * @param sessionId the session this check belongs to. {@code null}
     *                  skips the per-session allow-list lookup — daemon-
     *                  internal checks (cron, boot scripts) use this.
     * @return the decision
     */
    public PermissionDecision check(PermissionRequest request, String sessionId) {
        Objects.requireNonNull(request, "request");
        var capability = new Capability.LegacyToolUse(request.toolName(), request.level());
        var provenance = Provenance.legacy(sessionId);
        // Legacy callers' allowlist key is already the tool name — pass it
        // through unchanged so existing "always allow X" approvals stay valid.
        return check(capability, provenance, request.toolName(), request.description());
    }

    /**
     * Structured-capability entry point introduced by #480 PR 2. Takes a
     * {@link Capability} (one of the sealed variants) plus the
     * {@link Provenance} that records how the agent arrived at this check,
     * and returns the same decision the legacy method does.
     *
     * <p>This convenience overload uses the capability's
     * {@link Capability#allowlistKey() default allowlist key} (the variant
     * class name) and {@link Capability#displayLabel()} as the prompt
     * description. Tool dispatchers should prefer
     * {@link #check(Capability, Provenance, String, String)} so existing
     * tool-name-keyed allowlists keep working through migration and the
     * user sees a richer prompt than the synthetic {@code displayLabel()}.
     */
    public PermissionDecision check(Capability capability, Provenance provenance) {
        return check(capability, provenance, capability.allowlistKey(), capability.displayLabel());
    }

    /**
     * Full structured-capability entry point. Caller supplies an explicit
     * {@code allowlistKey} (typically the originating tool's name) and a
     * {@code description} (typically the dispatcher's rich human-readable
     * tool summary). Used by the dispatcher in {@code StreamingAgentHandler}
     * so that:
     *
     * <ul>
     *   <li>"Always allow {@code write_file}" approvals granted before #480
     *       keep auto-approving even after {@code WriteFileTool} migrates
     *       to {@code CapabilityAware} — the allowlist is keyed by tool
     *       name, not by capability variant.</li>
     *   <li>The user sees the same prompt for both legacy and migrated
     *       tools — no UX regression during migration.</li>
     * </ul>
     *
     * <p>Pipeline is unchanged: session allowlist first, then policy.
     * PolicyEngine (#465 Scope #2) will eventually consume the structured
     * {@link Capability} directly; until then this method bridges by
     * constructing a {@link PermissionRequest} on the fly.
     */
    public PermissionDecision check(
            Capability capability,
            Provenance provenance,
            String allowlistKey,
            String description) {
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(provenance, "provenance");
        Objects.requireNonNull(allowlistKey, "allowlistKey");
        Objects.requireNonNull(description, "description");

        String sessionIdOrNull = provenance.sessionId().map(s -> s.value()).orElse(null);
        if (sessionIdOrNull != null) {
            var allow = sessionApprovals.get(sessionIdOrNull);
            if (allow != null && allow.contains(allowlistKey)) {
                log.debug("Permission auto-approved (session blanket): key={}, sessionId={}",
                        allowlistKey, sessionIdOrNull);
                var decision = new PermissionDecision.Approved();
                audit(allowlistKey, capability.risk(), sessionIdOrNull, decision, "session-blanket-approval");
                return decision;
            }
        }

        // PolicyEngine is still PermissionRequest-shaped today (#465 Scope
        // #2 will change that). Build a request from the capability.
        var request = new PermissionRequest(allowlistKey, description, capability.risk());
        var decision = policy.evaluate(request);
        log.debug("Permission check: key={}, level={}, sessionId={}, decision={}",
                allowlistKey, capability.risk(), sessionIdOrNull,
                decision.getClass().getSimpleName());
        audit(allowlistKey, capability.risk(), sessionIdOrNull, decision, null);
        return decision;
    }

    /**
     * Writes one entry to the audit log if one is attached. Flattens
     * the sealed {@link PermissionDecision} into the on-disk string
     * form ({@code APPROVED} / {@code DENIED} / {@code NEEDS_APPROVAL})
     * and chooses the {@code reason} field: caller-supplied note
     * (for the session-blanket branch), the denial reason (for
     * Denied), the prompt (for NeedsUserApproval), or null.
     *
     * <p>Audit shape is still v1 (flat fields) in PR 2 — PR 3 will swap
     * in a structured form that carries the {@link Capability} and
     * {@link Provenance} directly. For now we project them down to the
     * v1 fields {@code (toolName, level)}.
     */
    private void audit(
            String allowlistKey,
            PermissionLevel risk,
            String sessionId,
            PermissionDecision decision,
            String approvalNote) {
        if (auditLog == null) return;
        String kind;
        String reason;
        switch (decision) {
            case PermissionDecision.Approved _ -> {
                kind = "APPROVED";
                reason = approvalNote;
            }
            case PermissionDecision.Denied d -> {
                kind = "DENIED";
                reason = d.reason();
            }
            case PermissionDecision.NeedsUserApproval n -> {
                kind = "NEEDS_APPROVAL";
                reason = n.prompt();
            }
        }
        auditLog.record(Instant.now(), sessionId, allowlistKey, risk, kind, reason);
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

package dev.aceclaw.core.agent;

/**
 * Structural / system-invariant denial probe wired into
 * {@link SubAgentPermissionChecker}. Returns the denial reason when the
 * sub-agent's intended (toolName, inputJson) violates a cross-cutting rule
 * (e.g. "never write to {@code .env}"), or {@code null} when no structural
 * rule applies.
 *
 * <p>Lives in {@code aceclaw-core} as a string-shaped predicate so the core
 * agent loop does not have to depend on {@code aceclaw-security}'s
 * {@code Capability} / {@code PermissionDecision} types. The daemon wires
 * the implementation: it resolves the tool, builds the structured
 * {@code Capability}, calls {@code PermissionManager.checkStructural}, and
 * returns the {@code Denied.reason()} when present.
 *
 * <p>Why structural denials must reach the sub-agent path: the sub-agent
 * permission flow auto-approves any tool the parent session has blanket-
 * approved. Without a structural probe, a prior "always allow {@code write_file}"
 * would let a sub-agent call {@code write_file(".env")} without the same
 * cross-cutting refusal the main dispatcher enforces — Codex P1 on #495.
 */
@FunctionalInterface
public interface SubAgentStructuralCheck {

    /**
     * Returns a non-null denial reason when {@code (toolName, inputJson)}
     * matches a structural denial rule, given the calling sub-agent's
     * {@code sessionId}. Returns {@code null} when no structural rule
     * applies — the caller then defers to the normal read-only /
     * session-approval logic.
     *
     * <p>{@code sessionId} is forwarded so the daemon-side implementation
     * can build a {@code Provenance} and write an audit entry tagged with
     * the originating session, matching the main-dispatcher's audit
     * shape. {@code null} is allowed for daemon-internal callers that have
     * no session in scope.
     *
     * <p>Implementations must not throw on malformed input — they should
     * return {@code null} for unparseable args so the standard fall-through
     * logic decides, rather than crash the dispatcher.
     */
    String denyReason(String toolName, String inputJson, String sessionId);

    /** No-op probe — returns {@code null} for every input. */
    SubAgentStructuralCheck NONE = (toolName, inputJson, sessionId) -> null;
}

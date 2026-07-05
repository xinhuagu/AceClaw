package dev.aceclaw.security;

/**
 * Strategy interface for permission evaluation.
 *
 * <p>Receives the structured {@link Capability} the agent wants to exercise
 * together with the {@link Provenance} that records how the agent arrived
 * here, plus a {@code description} the dispatcher computed for human display
 * (used by the policy when it builds the
 * {@link PermissionDecision.NeedsUserApproval} prompt text so the user sees
 * the rich tool-side phrasing rather than the variant's synthetic label).
 *
 * <p>Policies pattern-match on the sealed {@code Capability} variant to
 * encode rules like "deny writes to {@code .env}", "block HTTP egress to
 * non-allowlisted hosts", or "tighten constraints when
 * {@code provenance.subAgentDepth() > 0}". The {@code description} is
 * presentation-only — policies should make decisions from {@code capability}
 * and {@code provenance}, never from the human string, since it can vary
 * per dispatcher.
 *
 * <p>This signature replaces the pre-#480-PR-4 {@code evaluate(PermissionRequest)}
 * shape, which only carried a flat tool name + 4-tier risk level and forced
 * policies to be capability-blind.
 */
@FunctionalInterface
public interface PermissionPolicy {

    /**
     * Evaluates whether the given capability use should be approved, denied,
     * or needs interactive user confirmation.
     *
     * @param capability  the structured capability the agent will exercise
     * @param provenance  how the agent arrived at this check — session,
     *                    sub-agent depth, plan step, retry chain
     * @param description rich human-readable phrasing for the prompt; not
     *                    used for policy decisions
     * @return the decision
     */
    PermissionDecision evaluate(Capability capability, Provenance provenance, String description);

    /**
     * Structural / system-invariant denials that fire <em>before</em> any
     * session-level allow-list lookup or mode-based decision in
     * {@link PermissionManager}. Use this for cross-cutting rules that must
     * not be bypassable by "always allow X" approvals — e.g. "never write
     * to {@code .env}", "never delete {@code .ssh/id_rsa}".
     *
     * <p>Returning {@code null} (the default) means "no structural rule
     * applies; defer to the rest of the pipeline." Returning a
     * {@link PermissionDecision.Denied} ends the check immediately with
     * that denial; other decision variants are not accepted here (returning
     * an Approved would meaningfully short-circuit the user-approval prompt,
     * which is what {@link #evaluate} is for).
     */
    default PermissionDecision.Denied evaluateStructural(Capability capability) {
        return null;
    }
}

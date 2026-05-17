package dev.aceclaw.security.rules;

import dev.aceclaw.security.Capability;
import dev.aceclaw.security.PermissionDecision;
import dev.aceclaw.security.PermissionLevel;
import dev.aceclaw.security.PolicyContext;
import dev.aceclaw.security.Provenance;
import dev.aceclaw.security.Rule;

import java.util.Optional;

/**
 * Denies {@link PermissionLevel#DANGEROUS} capabilities when a
 * sub-agent's nesting depth exceeds a configured maximum (#465 Scope #2 —
 * canonical cross-cutting example from the epic: "no {@code DANGEROUS}
 * for sub-agents below depth N").
 *
 * <h3>Depth semantics</h3>
 *
 * {@link Provenance#subAgentDepth()} is {@code 0} for the top-level
 * agent's own checks, {@code 1} for a sub-agent spawned by the top-level,
 * {@code 2} for a sub-sub-agent, and so on. This rule denies when
 * {@code depth > maxDepth}, so:
 *
 * <ul>
 *   <li>{@code maxDepth = 0} — only the top-level agent may do
 *       {@code DANGEROUS} ops. All sub-agents are blocked.</li>
 *   <li>{@code maxDepth = 1} — top-level and direct sub-agents may;
 *       sub-sub-agents are blocked. This is the daemon default — the
 *       common Task tool case (top-level delegates one sub-agent) keeps
 *       working; the rarer chained delegation needs an explicit user
 *       turn.</li>
 *   <li>{@code maxDepth = Integer.MAX_VALUE} — effectively disabled.</li>
 * </ul>
 *
 * <h3>Why depth-bound DANGEROUS</h3>
 *
 * The user prompt is the consent boundary. A top-level agent's
 * {@code rm -rf} runs because the operator typed something that asked
 * for cleanup. A sub-sub-agent invoking {@code rm -rf} is two
 * delegation hops removed from that consent — the operator may not even
 * see the prompt before the LLM auto-approves it via session blanket
 * approval (sub-agents inherit the parent session, see {@code #457}).
 * Bounding by depth makes the dangerous-action surface scale with
 * operator visibility, not with the agent's planning ambition.
 *
 * <h3>What this rule does NOT do</h3>
 *
 * It does not deny {@code WRITE} or {@code EXECUTE} at depth. The
 * justification only holds for irrecoverable operations; ordinary
 * writes are still gated by the mode policy and per-tool approvals.
 * Operators wanting tighter sub-agent confinement should add an
 * additional rule rather than widening this one — keeping rules
 * single-purpose makes "why was this denied?" answerable from the rule
 * name.
 */
public final class DenyDangerousForDeepSubAgentRule implements Rule {

    private final int maxDepth;

    /**
     * @param maxDepth the largest sub-agent depth that may use a
     *                 {@link PermissionLevel#DANGEROUS} capability.
     *                 Must be {@code >= 0}.
     */
    public DenyDangerousForDeepSubAgentRule(int maxDepth) {
        if (maxDepth < 0) {
            throw new IllegalArgumentException(
                    "maxDepth must be non-negative; got " + maxDepth);
        }
        this.maxDepth = maxDepth;
    }

    @Override
    public Optional<PermissionDecision> evaluate(
            Capability capability, Provenance provenance, PolicyContext context) {
        if (capability.risk() != PermissionLevel.DANGEROUS) return Optional.empty();
        int depth = provenance.subAgentDepth();
        if (depth <= maxDepth) return Optional.empty();
        return Optional.of(new PermissionDecision.Denied(
                "DANGEROUS capability blocked for sub-agent at depth " + depth
                        + " (max=" + maxDepth + "): " + capability.displayLabel()
                        + " (rule=" + name() + ")"));
    }
}

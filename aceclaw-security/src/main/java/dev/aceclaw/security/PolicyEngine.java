package dev.aceclaw.security;

import java.util.Objects;

/**
 * One rule-evaluation surface for {@link Capability} checks (#465 Scope #2).
 *
 * <p>Pre-Layer 2 the {@link PermissionManager} built a synthetic
 * {@link PermissionRequest} from the capability's flat {@link PermissionLevel}
 * and handed it to a {@link PermissionPolicy}. Cross-cutting rules ("no
 * {@code .env} reads from any adapter", "no network in plan mode", "no
 * {@code DANGEROUS} for sub-agents past depth N") cannot be expressed
 * against the flat shape — they need the structured variant and the
 * {@link Provenance} chain. PolicyEngine consumes both.
 *
 * <h3>Relationship to {@link PermissionPolicy}</h3>
 *
 * {@link PermissionPolicy} stays as the legacy functional interface
 * (request in, decision out) so existing callers compile unchanged. The
 * adapter {@link #fromLegacyPolicy(PermissionPolicy)} wraps a legacy
 * policy as a PolicyEngine that ignores the capability variant and routes
 * a synthesised request through the legacy decision; this is what backs
 * the {@code PermissionManager(PermissionPolicy)} constructor.
 *
 * <h3>Composition</h3>
 *
 * Engines compose. {@link RuleBasedPolicyEngine} takes an ordered list of
 * {@link Rule}s and a fallback engine; the daemon wires built-in rules
 * with a legacy-adapter fallback so default mode-driven behaviour is
 * unchanged when no rule matches.
 */
@FunctionalInterface
public interface PolicyEngine {

    /**
     * Evaluates a structured capability check.
     *
     * @param capability the structured capability the agent wants to use
     * @param provenance how the agent arrived at this check (session, plan
     *                   step, sub-agent depth, ...)
     * @param context    per-check context the caller needs to pass through
     *                   to the legacy fallback (allowlist key, description)
     * @return the decision
     */
    PermissionDecision evaluate(Capability capability, Provenance provenance, PolicyContext context);

    /**
     * Adapts a legacy {@link PermissionPolicy} (request in, decision out)
     * into a PolicyEngine. Used by {@link PermissionManager}'s legacy
     * constructor and as the terminal fallback for {@link RuleBasedPolicyEngine}.
     *
     * <p>Reconstructs a {@link PermissionRequest} from the
     * {@link PolicyContext}'s {@code allowlistKey} and {@code description}
     * plus the capability's derived {@link Capability#risk()}. Crucially,
     * the {@code allowlistKey} is preserved from the caller — two
     * different tools producing the same {@link Capability} variant stay
     * distinct in the legacy request (so existing "always allow X"
     * approvals don't accidentally cross adapters).
     */
    static PolicyEngine fromLegacyPolicy(PermissionPolicy legacy) {
        Objects.requireNonNull(legacy, "legacy");
        return (capability, provenance, context) -> {
            var request = new PermissionRequest(
                    context.allowlistKey(),
                    context.description(),
                    capability.risk());
            return legacy.evaluate(request);
        };
    }
}

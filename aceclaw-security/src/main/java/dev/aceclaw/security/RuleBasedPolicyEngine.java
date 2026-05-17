package dev.aceclaw.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * {@link PolicyEngine} that evaluates an ordered list of {@link Rule}s and
 * falls back to a wrapped engine when no rule matches (#465 Scope #2).
 *
 * <h3>Decision flow</h3>
 *
 * <ol>
 *   <li>Walk {@link #rules} in declaration order; the first rule whose
 *       {@code evaluate(...)} returns a non-empty {@link PermissionDecision}
 *       wins and short-circuits the chain.</li>
 *   <li>If every rule defers (returns {@link java.util.Optional#empty()}),
 *       delegate to {@link #fallback}. For the daemon this is
 *       {@link PolicyEngine#fromLegacyPolicy(PermissionPolicy)} wrapping a
 *       {@link DefaultPermissionPolicy}, so unmatched checks keep the
 *       pre-Layer-2 mode-based behaviour exactly.</li>
 * </ol>
 *
 * <p>The fallback is mandatory rather than defaulting to a permissive or
 * restrictive built-in: silently approving / denying on "no rule
 * matched" hides configuration bugs. Callers must make the policy
 * explicit by passing one.
 *
 * <h3>Why "first match wins"</h3>
 *
 * The alternative is "deny wins over approve" (collect all decisions,
 * pick the strictest). That makes rule order irrelevant but couples rule
 * authors to each other — adding a permissive rule has to consider every
 * other rule's denials. First-match keeps each rule's contract local
 * ("if I fire, my answer stands") and makes ordering the explicit lever
 * for "deny first, then narrow approve". Operators wanting a deny to
 * trump a later approval put the deny earlier in the list.
 *
 * <h3>Logging</h3>
 *
 * Every fired rule logs at {@code DEBUG} so the dashboard timeline /
 * operator can see which rule produced a decision without having to run
 * the audit log through a query tool. Audit recording itself is in
 * {@link PermissionManager#check}, not here — keeping engines pure of
 * I/O lets them be tested without disk setup.
 */
public final class RuleBasedPolicyEngine implements PolicyEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleBasedPolicyEngine.class);

    private final List<Rule> rules;
    private final PolicyEngine fallback;

    /**
     * @param rules    ordered list of rules; first match wins. Defensively
     *                 copied — later mutation of the source list cannot
     *                 change the engine's behaviour out from under it.
     * @param fallback engine consulted when every rule defers. Must not
     *                 be null — see class-level note on explicitness.
     */
    public RuleBasedPolicyEngine(List<Rule> rules, PolicyEngine fallback) {
        Objects.requireNonNull(rules, "rules");
        this.rules = List.copyOf(rules);
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    @Override
    public PermissionDecision evaluate(
            Capability capability, Provenance provenance, PolicyContext context) {
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(provenance, "provenance");
        Objects.requireNonNull(context, "context");
        for (Rule rule : rules) {
            var maybeDecision = rule.evaluate(capability, provenance, context);
            if (maybeDecision.isPresent()) {
                var decision = maybeDecision.get();
                log.debug("Rule '{}' fired for capability {} -> {}",
                        rule.name(),
                        capability.getClass().getSimpleName(),
                        decision.getClass().getSimpleName());
                return decision;
            }
        }
        return fallback.evaluate(capability, provenance, context);
    }

    /**
     * Exposed for tests / dashboard introspection — read-only view of the
     * configured rule chain.
     */
    public List<Rule> rules() {
        return rules;
    }
}

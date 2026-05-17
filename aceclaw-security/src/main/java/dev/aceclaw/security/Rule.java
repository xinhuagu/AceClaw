package dev.aceclaw.security;

import java.util.Optional;

/**
 * One cross-cutting policy rule for {@link PolicyEngine} (#465 Scope #2).
 *
 * <p>A rule examines a structured {@link Capability} and the
 * {@link Provenance} that produced it; it returns
 * {@link Optional#empty()} if it has no opinion (let the next rule or
 * the fallback decide) or {@link Optional#of(Object)} with a concrete
 * {@link PermissionDecision} to short-circuit the chain.
 *
 * <h3>Why {@code Optional} rather than a sentinel "PASS" decision</h3>
 *
 * The decision sealed type has three meaningful outcomes — Approved,
 * Denied, NeedsUserApproval — every one of which is a real answer.
 * Adding a fourth "no opinion" value would force every consumer to
 * pattern-match on a case that doesn't represent a permission outcome,
 * blurring the meaning of the type. {@code Optional<PermissionDecision>}
 * keeps the decision type pure and makes "has the rule fired?" a
 * presence check that's hard to misread.
 *
 * <h3>Ordering and conflict</h3>
 *
 * Rules are evaluated in declaration order; the first match wins. Two
 * rules cannot both produce a decision for the same input — short-
 * circuit avoids the conflict. Operators wanting "deny wins over approve"
 * should put the deny rule first in the engine's rule list.
 *
 * <h3>Rules should be stateless or carry only configuration</h3>
 *
 * Per-check state lives on {@link PolicyContext}; engine-wide state
 * (e.g. mode) is owned by the engine that wires the rule. A rule that
 * mutates state on evaluate() will produce non-deterministic decisions
 * and is essentially impossible to audit.
 */
@FunctionalInterface
public interface Rule {

    /**
     * Evaluates this rule against a capability check.
     *
     * @param capability the structured capability under review
     * @param provenance how the agent arrived at this check
     * @param context    per-check caller context (allowlist key, description)
     * @return {@link Optional#empty()} to defer to the next rule;
     *         {@link Optional#of(Object)} to short-circuit with a decision
     */
    Optional<PermissionDecision> evaluate(
            Capability capability, Provenance provenance, PolicyContext context);

    /**
     * Human-readable rule name for logs and audit. Defaults to the
     * implementing class's simple name; lambdas should override (or use
     * a named record).
     */
    default String name() {
        return getClass().getSimpleName();
    }
}

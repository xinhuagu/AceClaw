package dev.aceclaw.security.rules;

import dev.aceclaw.security.Capability;
import dev.aceclaw.security.DefaultPermissionPolicy;
import dev.aceclaw.security.PermissionDecision;
import dev.aceclaw.security.PolicyContext;
import dev.aceclaw.security.Provenance;
import dev.aceclaw.security.Rule;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Denies network-touching capabilities while the daemon is in
 * {@link DefaultPermissionPolicy#MODE_PLAN plan mode} (#465 Scope #2 —
 * canonical cross-cutting example from the epic: "no {@code NETWORK}
 * during {@code plan} mode").
 *
 * <h3>Why this rule when DefaultPermissionPolicy already denies in plan mode</h3>
 *
 * {@link DefaultPermissionPolicy} denies any {@code WRITE} / {@code EXECUTE}
 * / {@code DANGEROUS} request in plan mode with a generic message ("plan
 * mode is read-only"). This rule fires <em>before</em> the fallback and
 * produces a precise, actionable message naming the network capability,
 * so operators reading the dashboard see "network use blocked in plan
 * mode" instead of the generic mode message. That precision matters
 * because plan mode legitimately allows local {@code EXECUTE} on some
 * tools in user-extended configurations (e.g. dry-runs) — keeping the
 * network deny called out separately makes the rationale auditable.
 *
 * <p>It also matters going forward: if {@link DefaultPermissionPolicy}'s
 * plan-mode policy ever relaxes (e.g. allow read-only HTTP in plan mode),
 * this rule continues to enforce the network ban explicitly — the
 * cross-cutting concern doesn't depend on the mode rule's contents.
 *
 * <h3>Mode supplier</h3>
 *
 * Mode is read per-check via a {@link Supplier}, not captured at
 * construction, so future mode-switching (e.g. an {@code agent.setMode}
 * RPC mid-session) doesn't require rebuilding the engine. Today's daemon
 * passes a constant supplier (mode is set once at startup); the
 * indirection costs nothing and avoids a refactor when mode becomes
 * dynamic.
 */
public final class DenyNetworkInPlanModeRule implements Rule {

    private final Supplier<String> modeSupplier;

    public DenyNetworkInPlanModeRule(Supplier<String> modeSupplier) {
        this.modeSupplier = Objects.requireNonNull(modeSupplier, "modeSupplier");
    }

    @Override
    public Optional<PermissionDecision> evaluate(
            Capability capability, Provenance provenance, PolicyContext context) {
        if (!DefaultPermissionPolicy.MODE_PLAN.equals(modeSupplier.get())) {
            return Optional.empty();
        }
        return switch (capability) {
            case Capability.HttpFetch h -> Optional.of(new PermissionDecision.Denied(
                    "Network use blocked in plan mode: " + h.displayLabel()
                            + " (rule=" + name() + ")"));
            case Capability.BrowserAction b -> Optional.of(new PermissionDecision.Denied(
                    "Network use blocked in plan mode: " + b.displayLabel()
                            + " (rule=" + name() + ")"));
            default -> Optional.empty();
        };
    }
}

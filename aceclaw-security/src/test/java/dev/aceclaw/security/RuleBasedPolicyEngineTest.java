package dev.aceclaw.security;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the {@link RuleBasedPolicyEngine}'s composition contract: ordered
 * rules with first-match-wins short-circuit, mandatory non-null fallback,
 * defensive copy of the rule list, and zero-rule behaviour identical to
 * just running the fallback.
 *
 * <p>Built-in rule semantics are tested per-rule under {@code rules/};
 * this file is strictly about the engine's plumbing.
 */
final class RuleBasedPolicyEngineTest {

    private static final Capability FILE_READ =
            new Capability.FileRead(Path.of("/tmp/x"));

    private static final Provenance PROV = Provenance.daemonInternal();
    private static final PolicyContext CTX =
            new PolicyContext("read_file", "read /tmp/x");

    private static final PolicyEngine ALWAYS_APPROVE_FALLBACK =
            (cap, prov, ctx) -> new PermissionDecision.Approved();
    private static final PolicyEngine ALWAYS_DENY_FALLBACK =
            (cap, prov, ctx) -> new PermissionDecision.Denied("fallback denies");

    @Test
    void firstMatchingRuleWinsAndShortCircuits() {
        AtomicInteger laterRuleCalls = new AtomicInteger();
        Rule denyImmediately = (cap, prov, ctx) ->
                Optional.of(new PermissionDecision.Denied("denied by first rule"));
        Rule countCalls = (cap, prov, ctx) -> {
            laterRuleCalls.incrementAndGet();
            return Optional.of(new PermissionDecision.Approved());
        };

        var engine = new RuleBasedPolicyEngine(
                List.of(denyImmediately, countCalls),
                ALWAYS_APPROVE_FALLBACK);

        var decision = engine.evaluate(FILE_READ, PROV, CTX);

        assertThat(decision).isInstanceOf(PermissionDecision.Denied.class);
        assertThat(laterRuleCalls.get())
                .as("rules after the first match must not be evaluated")
                .isEqualTo(0);
    }

    @Test
    void deferringRuleFallsThroughToNextRule() {
        Rule defer = (cap, prov, ctx) -> Optional.empty();
        Rule denySecond = (cap, prov, ctx) ->
                Optional.of(new PermissionDecision.Denied("denied by second rule"));

        var engine = new RuleBasedPolicyEngine(
                List.of(defer, denySecond),
                ALWAYS_APPROVE_FALLBACK);

        assertThat(engine.evaluate(FILE_READ, PROV, CTX))
                .isInstanceOf(PermissionDecision.Denied.class);
    }

    @Test
    void allRulesDeferingDelegatesToFallback() {
        Rule defer = (cap, prov, ctx) -> Optional.empty();
        var engine = new RuleBasedPolicyEngine(
                List.of(defer, defer, defer),
                ALWAYS_DENY_FALLBACK);

        assertThat(engine.evaluate(FILE_READ, PROV, CTX))
                .as("when no rule fires, fallback engine is consulted")
                .isInstanceOf(PermissionDecision.Denied.class);
    }

    @Test
    void emptyRuleListBehavesLikeJustTheFallback() {
        var engine = new RuleBasedPolicyEngine(List.of(), ALWAYS_DENY_FALLBACK);
        assertThat(engine.evaluate(FILE_READ, PROV, CTX))
                .isInstanceOf(PermissionDecision.Denied.class);
    }

    @Test
    void ruleListIsDefensivelyCopiedAtConstruction() {
        // Mutating the caller-held list after construction must NOT change
        // engine behaviour — otherwise a long-lived daemon could see its
        // policy chain silently replaced by anyone holding the original
        // reference.
        var mutable = new java.util.ArrayList<Rule>();
        mutable.add((cap, prov, ctx) -> Optional.empty());
        var engine = new RuleBasedPolicyEngine(mutable, ALWAYS_APPROVE_FALLBACK);

        mutable.add((cap, prov, ctx) ->
                Optional.of(new PermissionDecision.Denied("late-added rule")));

        assertThat(engine.evaluate(FILE_READ, PROV, CTX))
                .as("post-construction mutation of source list must not affect engine")
                .isInstanceOf(PermissionDecision.Approved.class);
        assertThat(engine.rules())
                .as("rules() exposes only the snapshot taken at construction")
                .hasSize(1);
    }

    @Test
    void rulesViewIsImmutable() {
        var engine = new RuleBasedPolicyEngine(List.of(), ALWAYS_APPROVE_FALLBACK);
        assertThatThrownBy(() -> engine.rules().add((c, p, ctx) -> Optional.empty()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullFallbackIsRejected() {
        assertThatThrownBy(() -> new RuleBasedPolicyEngine(List.of(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("fallback");
    }

    @Test
    void nullRulesListIsRejected() {
        assertThatThrownBy(() -> new RuleBasedPolicyEngine(null, ALWAYS_APPROVE_FALLBACK))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void fromLegacyPolicyAdapterPassesAllowlistKeyAndDescriptionToRequest() {
        // The legacy adapter is what backs PermissionManager's
        // (PermissionPolicy) constructor and RuleBasedPolicyEngine's
        // typical fallback. Pin that PolicyContext fields reach the
        // legacy PermissionRequest unchanged — losing them would mean
        // legacy policies suddenly see a synthesised allowlistKey
        // (variant class name) instead of the originating tool's name,
        // breaking the per-tool "always allow" semantics.
        var captured = new java.util.concurrent.atomic.AtomicReference<PermissionRequest>();
        PermissionPolicy capturing = request -> {
            captured.set(request);
            return new PermissionDecision.Approved();
        };

        var engine = PolicyEngine.fromLegacyPolicy(capturing);
        engine.evaluate(
                new Capability.FileWrite(Path.of("/tmp/x"), WriteMode.OVERWRITE),
                Provenance.daemonInternal(),
                new PolicyContext("write_file", "Write /tmp/x (123 bytes)"));

        assertThat(captured.get().toolName()).isEqualTo("write_file");
        assertThat(captured.get().description()).isEqualTo("Write /tmp/x (123 bytes)");
        assertThat(captured.get().level()).isEqualTo(PermissionLevel.WRITE);
    }
}

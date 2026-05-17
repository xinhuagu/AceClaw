package dev.aceclaw.security.rules;

import dev.aceclaw.security.Capability;
import dev.aceclaw.security.DefaultPermissionPolicy;
import dev.aceclaw.security.PermissionDecision;
import dev.aceclaw.security.PolicyContext;
import dev.aceclaw.security.Provenance;
import dev.aceclaw.security.WriteMode;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

final class DenyNetworkInPlanModeRuleTest {

    private static final PolicyContext CTX = new PolicyContext("any", "any");
    private static final Provenance PROV = Provenance.daemonInternal();

    @Test
    void denyHttpFetchInPlanMode() {
        var rule = new DenyNetworkInPlanModeRule(() -> DefaultPermissionPolicy.MODE_PLAN);
        var decision = rule.evaluate(
                new Capability.HttpFetch(URI.create("https://example.com"), "GET"),
                PROV, CTX);
        assertThat(decision).isPresent();
        assertThat(((PermissionDecision.Denied) decision.get()).reason())
                .as("denial reason must call out plan mode + name the rule")
                .contains("plan mode")
                .contains("DenyNetworkInPlanModeRule");
    }

    @Test
    void denyBrowserActionInPlanMode() {
        var rule = new DenyNetworkInPlanModeRule(() -> DefaultPermissionPolicy.MODE_PLAN);
        var decision = rule.evaluate(
                new Capability.BrowserAction("navigate", Optional.of(URI.create("https://example.com"))),
                PROV, CTX);
        assertThat(decision).isPresent();
    }

    @Test
    void deferInNonPlanModes() {
        for (var mode : new String[]{
                DefaultPermissionPolicy.MODE_NORMAL,
                DefaultPermissionPolicy.MODE_ACCEPT_EDITS,
                DefaultPermissionPolicy.MODE_AUTO_ACCEPT}) {
            var rule = new DenyNetworkInPlanModeRule(() -> mode);
            assertThat(rule.evaluate(
                    new Capability.HttpFetch(URI.create("https://x"), "GET"), PROV, CTX))
                    .as("rule must defer in mode '%s'", mode)
                    .isEmpty();
        }
    }

    @Test
    void deferForNonNetworkCapabilitiesEvenInPlanMode() {
        var rule = new DenyNetworkInPlanModeRule(() -> DefaultPermissionPolicy.MODE_PLAN);
        // Filesystem writes ARE denied in plan mode, but by the fallback
        // mode policy — not by this rule. Confirm the rule itself defers
        // so the audit log credits the correct rule for the denial.
        assertThat(rule.evaluate(
                new Capability.FileWrite(Path.of("/tmp/x"), WriteMode.OVERWRITE), PROV, CTX))
                .as("network rule defers on non-network capabilities; mode policy handles them")
                .isEmpty();
        assertThat(rule.evaluate(
                new Capability.BashExec("ls", Path.of("/tmp")), PROV, CTX))
                .isEmpty();
    }

    @Test
    void modeIsReadPerCheckNotCaptured() {
        // Supplier indirection enables future mode-switching mid-session
        // without rebuilding the engine. Confirm the rule re-reads.
        var mode = new AtomicReference<>(DefaultPermissionPolicy.MODE_NORMAL);
        var rule = new DenyNetworkInPlanModeRule(mode::get);

        var http = new Capability.HttpFetch(URI.create("https://x"), "GET");
        assertThat(rule.evaluate(http, PROV, CTX))
                .as("mode=normal — rule defers")
                .isEmpty();

        mode.set(DefaultPermissionPolicy.MODE_PLAN);
        assertThat(rule.evaluate(http, PROV, CTX))
                .as("mode flipped to plan — rule now denies same capability")
                .isPresent();
    }
}

package dev.aceclaw.security.rules;

import dev.aceclaw.security.Capability;
import dev.aceclaw.security.PermissionDecision;
import dev.aceclaw.security.PolicyContext;
import dev.aceclaw.security.Provenance;
import dev.aceclaw.security.ids.SessionId;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class DenyDangerousForDeepSubAgentRuleTest {

    private static final PolicyContext CTX = new PolicyContext("bash", "rm -rf /tmp/x");
    /** Self-escalates to DANGEROUS via Capability.BashExec.isDestructive. */
    private static final Capability DANGEROUS_BASH =
            new Capability.BashExec("rm -rf /tmp/x", Path.of("/tmp"));
    private static final Capability EXECUTE_BASH =
            new Capability.BashExec("echo hi", Path.of("/tmp"));

    private static Provenance provAtDepth(int depth) {
        return new Provenance(
                Optional.empty(),
                Optional.of(new SessionId("s")),
                Optional.empty(),
                depth,
                List.of());
    }

    @Test
    void denyDangerousPastMaxDepth() {
        var rule = new DenyDangerousForDeepSubAgentRule(1);
        var decision = rule.evaluate(DANGEROUS_BASH, provAtDepth(2), CTX);
        assertThat(decision).isPresent();
        assertThat(((PermissionDecision.Denied) decision.get()).reason())
                .as("denial reason must name the depth, the max, and the rule")
                .contains("depth 2")
                .contains("max=1")
                .contains("DenyDangerousForDeepSubAgentRule");
    }

    @Test
    void allowDangerousAtOrBelowMaxDepth() {
        var rule = new DenyDangerousForDeepSubAgentRule(1);
        assertThat(rule.evaluate(DANGEROUS_BASH, provAtDepth(0), CTX))
                .as("depth 0 (top-level) is allowed")
                .isEmpty();
        assertThat(rule.evaluate(DANGEROUS_BASH, provAtDepth(1), CTX))
                .as("depth 1 (direct sub-agent) is allowed at maxDepth=1")
                .isEmpty();
    }

    @Test
    void deferForNonDangerousAtAnyDepth() {
        // EXECUTE-level capabilities are not depth-bounded by this rule.
        var rule = new DenyDangerousForDeepSubAgentRule(0);
        for (int depth = 0; depth <= 5; depth++) {
            assertThat(rule.evaluate(EXECUTE_BASH, provAtDepth(depth), CTX))
                    .as("EXECUTE bash at depth=%d must NOT be matched by depth rule", depth)
                    .isEmpty();
        }
    }

    @Test
    void maxDepthZeroBlocksAllSubAgentsButPermitsTopLevel() {
        var rule = new DenyDangerousForDeepSubAgentRule(0);
        assertThat(rule.evaluate(DANGEROUS_BASH, provAtDepth(0), CTX))
                .as("top-level always allowed regardless of maxDepth")
                .isEmpty();
        assertThat(rule.evaluate(DANGEROUS_BASH, provAtDepth(1), CTX))
                .as("maxDepth=0 blocks any sub-agent from DANGEROUS")
                .isPresent();
    }

    @Test
    void negativeMaxDepthRejected() {
        assertThatThrownBy(() -> new DenyDangerousForDeepSubAgentRule(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
    }
}

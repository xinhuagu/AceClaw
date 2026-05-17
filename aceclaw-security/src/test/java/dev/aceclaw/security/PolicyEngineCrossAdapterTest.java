package dev.aceclaw.security;

import dev.aceclaw.security.rules.DenyEnvFileAccessRule;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-adapter consistency check for {@link PolicyEngine} (#465 Scope #2,
 * explicit success criterion from the epic):
 *
 * <blockquote>
 *   Test matrix: same rule, different adapters → identical decision.
 * </blockquote>
 *
 * <p>The whole point of capability-style governance is that two different
 * adapters producing semantically the same {@link Capability} get the
 * <em>same</em> policy answer. {@link Capability} intentionally omits an
 * "adapter" field so policies cannot fork by who asked; the originating
 * tool's name is recorded on {@link PolicyContext}'s {@code allowlistKey}
 * (for legacy fallback + per-session approvals only) but the engine's
 * rules must reach the same verdict regardless.
 *
 * <p>This test simulates two adapters by passing different allowlist
 * keys ({@code "read_file"} vs. a hypothetical {@code "mcp_fs.read"})
 * for the same {@link Capability.FileRead} and asserts identical
 * decisions from the rule chain.
 */
final class PolicyEngineCrossAdapterTest {

    private static final PolicyEngine FALLBACK_APPROVE =
            (cap, prov, ctx) -> new PermissionDecision.Approved();

    @Test
    void sameCapabilityFromDifferentAdaptersGetsSameDecision() {
        var engine = new RuleBasedPolicyEngine(
                List.of(new DenyEnvFileAccessRule()),
                FALLBACK_APPROVE);

        var envFile = new Capability.FileRead(Path.of("/project/.env"));
        var provenance = Provenance.daemonInternal();

        var builtinAdapterDecision = engine.evaluate(
                envFile, provenance, new PolicyContext("read_file", "read /project/.env"));
        var mcpAdapterDecision = engine.evaluate(
                envFile, provenance, new PolicyContext("mcp_fs.read", "read /project/.env via MCP"));
        var skillAdapterDecision = engine.evaluate(
                envFile, provenance, new PolicyContext("skill:dump-env", "skill reads .env"));

        // All three adapters must hit the same rule and produce the
        // SAME decision class. Any divergence would mean an adapter is
        // smuggling state into the engine — exactly the failure mode
        // capability-style governance prevents.
        assertThat(builtinAdapterDecision)
                .isInstanceOf(PermissionDecision.Denied.class);
        assertThat(mcpAdapterDecision.getClass())
                .as("MCP adapter must reach the same decision class as the built-in adapter")
                .isEqualTo(builtinAdapterDecision.getClass());
        assertThat(skillAdapterDecision.getClass())
                .as("skill adapter must reach the same decision class as the built-in adapter")
                .isEqualTo(builtinAdapterDecision.getClass());
    }

    @Test
    void capabilityShapeDictatesDecisionNotAllowlistKey() {
        // Negative angle: even a benign allowlist key ("safe_tool") still
        // gets denied if the capability shape says so. The rule doesn't
        // look at allowlistKey, by design.
        var engine = new RuleBasedPolicyEngine(
                List.of(new DenyEnvFileAccessRule()),
                FALLBACK_APPROVE);

        var decision = engine.evaluate(
                new Capability.FileRead(Path.of("id_rsa")),
                Provenance.daemonInternal(),
                new PolicyContext("totally_safe_looking_tool", "boring description"));

        assertThat(decision)
                .as("rule's verdict comes from the capability variant + fields, not the caller's chosen key")
                .isInstanceOf(PermissionDecision.Denied.class);
    }
}

package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.security.Capability;
import dev.aceclaw.security.CapabilityAware;
import dev.aceclaw.security.DefaultPermissionPolicy;
import dev.aceclaw.security.PermissionManager;
import dev.aceclaw.security.WriteMode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AceClawDaemon#subAgentStructuralProbe} — the
 * exception-handling contract for the sub-agent structural-denial probe
 * (CodeRabbit major on #495).
 *
 * <p>The PR's invariant is "hard-denial overrides every approval". The probe
 * lives between the sub-agent gate and the policy's
 * {@code evaluateStructural}, and an over-broad {@code catch (Exception)}
 * would silently let a prior session-blanket approval route a sub-agent past
 * a structural rule the inference layer crashed on. These tests pin the
 * three contractual paths:
 *
 * <ul>
 *   <li>structural denial → returns the denial reason;</li>
 *   <li>malformed JSON args → returns {@code null} so the standard
 *       allow-list gate handles it (LLMs occasionally emit invalid JSON;
 *       refusing here would be an unrelated DoS);</li>
 *   <li>unexpected {@link RuntimeException} from a
 *       {@link CapabilityAware#toCapability} impl → returns a non-null
 *       denial reason (fail-closed). Inference-code bugs MUST NOT open the
 *       structural-bypass hole.</li>
 * </ul>
 */
final class SubAgentStructuralProbeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void allowsCallWhenStructuralPolicyDoesNotApply() {
        // Happy path: capability resolves cleanly, policy has nothing to
        // say structurally → probe returns null and the sub-agent gate
        // moves on to the allow-list check.
        CapabilityAware safeWrite = args ->
                new Capability.FileWrite(Path.of("/tmp/safe.txt"), WriteMode.OVERWRITE);
        var pm = new PermissionManager(new DefaultPermissionPolicy("normal"));

        String result = AceClawDaemon.subAgentStructuralProbe(
                "write_file", "{\"path\":\"/tmp/safe.txt\"}", "sess-1",
                safeWrite, MAPPER, pm);

        assertThat(result).isNull();
    }

    @Test
    void deniesCallWhenStructuralPolicyRejects() {
        // The structural layer matches → probe returns the policy's denial
        // reason so the sub-agent gate can short-circuit before the
        // allow-list check.
        CapabilityAware writeToEnv = args ->
                new Capability.FileWrite(Path.of("/repo/.env"), WriteMode.OVERWRITE);
        // Sensitive-path denials are opt-in on the policy — enable here so
        // the structural rule actually fires and the probe sees a denial.
        var pm = new PermissionManager(
                new DefaultPermissionPolicy("normal", /* denySensitivePaths */ true));

        String result = AceClawDaemon.subAgentStructuralProbe(
                "write_file", "{\"path\":\"/repo/.env\"}", "sess-1",
                writeToEnv, MAPPER, pm);

        assertThat(result)
                .as("structural denial of .env write should propagate as the reason string")
                .isNotNull();
    }

    @Test
    void malformedJsonFallsThroughToAllowList() {
        // JsonProcessingException is expected input (LLMs sometimes emit
        // bad JSON). Returning a denial here would convert a parse error
        // into a structural-policy DoS unrelated to the actual capability.
        // The downstream gate will deny non-read-only tools that lack
        // session approval anyway, so fallthrough is safe.
        CapabilityAware neverReached = args -> {
            throw new AssertionError("toCapability must not be reached when readTree throws");
        };
        var pm = new PermissionManager(new DefaultPermissionPolicy("normal"));

        String result = AceClawDaemon.subAgentStructuralProbe(
                "write_file", "{not valid json", "sess-1",
                neverReached, MAPPER, pm);

        assertThat(result)
                .as("malformed JSON args must fall through (null) to the standard allow-list gate")
                .isNull();
    }

    @Test
    void runtimeExceptionFromToCapabilityFailsClosed() {
        // The core invariant: if toCapability throws unexpectedly, the
        // probe MUST NOT return null. Returning null here would let a
        // session-blanket approval (e.g. "always allow write_file") route
        // the sub-agent past the structural layer that just crashed —
        // breaking the PR's "hard-denial overrides every approval" sales
        // pitch. Fail-closed: return a non-null denial reason that names
        // the tool, so the audit + the agent's user-visible error both
        // point at the bug.
        CapabilityAware boom = args -> {
            throw new IllegalStateException("inference bug");
        };
        var pm = new PermissionManager(new DefaultPermissionPolicy("normal"));

        String result = AceClawDaemon.subAgentStructuralProbe(
                "write_file", "{\"path\":\"/repo/.env\"}", "sess-1",
                boom, MAPPER, pm);

        assertThat(result)
                .as("unexpected toCapability exception must fail-closed with a denial reason")
                .isNotNull();
        assertThat(result).contains("write_file");
    }

    @Test
    void emptyInputUsesEmptyObjectAndStillCallsToCapability() {
        // null/blank inputJson is a real path — the dispatcher sometimes
        // hands the probe an empty args payload. Verify it doesn't go
        // through the JSON parser at all (no parse exception) and
        // toCapability still runs against an empty object node.
        Capability[] seen = new Capability[1];
        CapabilityAware capturingAware = args -> {
            assertThat(args).isNotNull();
            assertThat(args.isObject()).isTrue();
            assertThat(args.size()).isEqualTo(0);
            var cap = new Capability.FileWrite(Path.of("/tmp/x"), WriteMode.OVERWRITE);
            seen[0] = cap;
            return cap;
        };
        var pm = new PermissionManager(new DefaultPermissionPolicy("normal"));

        String result = AceClawDaemon.subAgentStructuralProbe(
                "write_file", null, "sess-1",
                capturingAware, MAPPER, pm);

        assertThat(result).isNull();
        assertThat(seen[0]).isNotNull();

        // Blank string takes the same branch.
        seen[0] = null;
        AceClawDaemon.subAgentStructuralProbe(
                "write_file", "   ", "sess-1",
                capturingAware, MAPPER, pm);
        assertThat(seen[0]).isNotNull();
    }

    @Test
    void nullCapabilityFromToCapabilityFallsThrough() {
        // Some CapabilityAware impls return null when they decline to
        // classify (e.g. the MCP bridge falling back to McpInvoke). That
        // is NOT an error path — the probe should just return null so
        // the standard gate handles the tool with the default flow.
        CapabilityAware declines = args -> null;
        var pm = new PermissionManager(new DefaultPermissionPolicy("normal"));

        String result = AceClawDaemon.subAgentStructuralProbe(
                "write_file", "{}", "sess-1",
                declines, MAPPER, pm);

        assertThat(result).isNull();
    }

    @Test
    void nullJsonNodePathStillProbesPolicy() {
        // Sanity: a non-null JsonNode (object) flows through to the
        // policy. Pairs with malformedJsonFallsThroughToAllowList — the
        // happy parse path must end up calling checkStructural, otherwise
        // the policy is bypassed for every well-formed call.
        boolean[] called = {false};
        CapabilityAware tracking = (JsonNode args) -> {
            called[0] = true;
            return new Capability.FileWrite(Path.of("/tmp/x"), WriteMode.OVERWRITE);
        };
        var pm = new PermissionManager(new DefaultPermissionPolicy("normal"));

        AceClawDaemon.subAgentStructuralProbe(
                "write_file", "{\"path\":\"/tmp/x\"}", null,
                tracking, MAPPER, pm);

        assertThat(called[0]).isTrue();
    }
}

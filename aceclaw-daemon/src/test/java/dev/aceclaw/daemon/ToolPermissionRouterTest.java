package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.agent.Tool;
import dev.aceclaw.security.Capability;
import dev.aceclaw.security.CapabilityAware;
import dev.aceclaw.security.PermissionDecision;
import dev.aceclaw.security.PermissionLevel;
import dev.aceclaw.security.PermissionManager;
import dev.aceclaw.security.PermissionPolicy;
import dev.aceclaw.security.PermissionRequest;
import dev.aceclaw.security.WriteMode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the dispatcher branching contract for #480 PR 2: tools that implement
 * {@link CapabilityAware} are routed through the structured
 * {@link Capability} path; everything else takes the legacy
 * {@link PermissionRequest} path. Both paths land in the same
 * {@link PermissionManager} decision pipeline.
 *
 * <p>Without this test, a regression that hides {@code CapabilityAware} (e.g.
 * a tool decorator that delegates {@code execute} but not the marker
 * interface) would silently drop the structured policy/audit benefit and
 * never fail in CI.
 */
final class ToolPermissionRouterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Capturing policy: records the {@link PermissionRequest} so the test can pin what was sent. */
    private static final class CapturingPolicy implements PermissionPolicy {
        final AtomicReference<PermissionRequest> last = new AtomicReference<>();
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public PermissionDecision evaluate(PermissionRequest request) {
            last.set(request);
            calls.incrementAndGet();
            return new PermissionDecision.Approved();
        }
    }

    /** Plain tool, NOT CapabilityAware — must take the legacy path. */
    private static final class LegacyOnlyTool implements Tool {
        @Override public String name() { return "legacy_tool"; }
        @Override public String description() { return ""; }
        @Override public JsonNode inputSchema() { return MAPPER.createObjectNode(); }
        @Override public ToolResult execute(String inputJson) { return new ToolResult("ok", false); }
    }

    /** CapabilityAware tool that records whether toCapability was called. */
    private static final class SpyCapabilityAwareTool implements Tool, CapabilityAware {
        final AtomicInteger toCapabilityCalls = new AtomicInteger();
        Capability capabilityToReturn = new Capability.FileWrite(Path.of("/tmp/x"), WriteMode.OVERWRITE);
        boolean throwOnConvert = false;
        boolean returnNull = false;

        @Override public String name() { return "spy_tool"; }
        @Override public String description() { return ""; }
        @Override public JsonNode inputSchema() { return MAPPER.createObjectNode(); }
        @Override public ToolResult execute(String inputJson) { return new ToolResult("ok", false); }
        @Override public Capability toCapability(JsonNode args) {
            toCapabilityCalls.incrementAndGet();
            if (throwOnConvert) {
                throw new IllegalArgumentException("simulated bad args");
            }
            if (returnNull) {
                return null;
            }
            return capabilityToReturn;
        }
    }

    @Test
    void capabilityAwareToolTakesStructuredPath() {
        // The whole point of CapabilityAware: when the tool can produce a
        // structured Capability, the router uses it instead of the flat
        // legacy PermissionRequest. Pin both: toCapability was called, AND
        // the policy received a request derived from the capability.
        var policy = new CapturingPolicy();
        var pm = new PermissionManager(policy);
        var tool = new SpyCapabilityAwareTool();

        ToolPermissionRouter.check(
                tool, "{\"file_path\":\"/tmp/x\"}", "sess-1", "Write /tmp/x",
                PermissionLevel.WRITE, pm, MAPPER);

        assertThat(tool.toCapabilityCalls.get())
                .as("structured path must call toCapability")
                .isEqualTo(1);
        assertThat(policy.last.get().toolName())
                .as("router passes the originating tool name as allowlist key")
                .isEqualTo("spy_tool");
        assertThat(policy.last.get().description())
                .as("router passes the rich caller-supplied description, not displayLabel")
                .isEqualTo("Write /tmp/x");
        assertThat(policy.last.get().level())
                .as("level comes from the capability variant, not the fallback level")
                .isEqualTo(PermissionLevel.WRITE);
    }

    @Test
    void plainToolTakesLegacyPath() {
        // A non-CapabilityAware tool must not reach toCapability (there's
        // nothing to call) and must produce the same legacy
        // PermissionRequest the dispatcher built before #480.
        var policy = new CapturingPolicy();
        var pm = new PermissionManager(policy);
        var tool = new LegacyOnlyTool();

        ToolPermissionRouter.check(
                tool, "{}", "sess-1", "describe me", PermissionLevel.EXECUTE, pm, MAPPER);

        assertThat(policy.last.get().toolName()).isEqualTo("legacy_tool");
        assertThat(policy.last.get().description()).isEqualTo("describe me");
        assertThat(policy.last.get().level()).isEqualTo(PermissionLevel.EXECUTE);
    }

    @Test
    void toCapabilityThrowFallsBackToLegacy() {
        // A tool that throws from toCapability (bad args, parse error)
        // should not crash the dispatcher — fall back to the legacy path
        // so the user gets a normal approval prompt. Pin: toCapability was
        // attempted, then the legacy path produced a request with the
        // dispatcher-supplied fallback level (not the variant's risk).
        var policy = new CapturingPolicy();
        var pm = new PermissionManager(policy);
        var tool = new SpyCapabilityAwareTool();
        tool.throwOnConvert = true;

        var decision = ToolPermissionRouter.check(
                tool, "{}", "sess-1", "fallback prompt", PermissionLevel.WRITE, pm, MAPPER);

        assertThat(decision).isInstanceOf(PermissionDecision.Approved.class);
        assertThat(tool.toCapabilityCalls.get()).isEqualTo(1);
        assertThat(policy.last.get().level())
                .as("fallback uses the caller-supplied level, not a derived risk")
                .isEqualTo(PermissionLevel.WRITE);
    }

    @Test
    void toCapabilityReturningNullFailsFast() {
        // Contract violation: CapabilityAware.toCapability is documented
        // non-null. Silently downgrading to legacy here would mask a broken
        // tool and drop structured policy/audit data. Verify the router
        // throws IllegalStateException naming the offending tool.
        var policy = new CapturingPolicy();
        var pm = new PermissionManager(policy);
        var tool = new SpyCapabilityAwareTool();
        tool.returnNull = true;

        assertThatThrownBy(() -> ToolPermissionRouter.check(
                tool, "{}", "sess-1", "any", PermissionLevel.WRITE, pm, MAPPER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spy_tool")
                .hasMessageContaining("null capability");

        assertThat(policy.calls.get())
                .as("policy must NOT be consulted when the contract is violated")
                .isZero();
    }

    @Test
    void permissionManagerErrorPropagates() {
        // The narrow fallback intentionally does NOT cover errors from
        // permissionManager.check itself — those would be policy/audit
        // bugs. Re-running the legacy path on them would mask real
        // failures and could change the decision behind the user's back.
        // Pin: a policy that throws bubbles up through the structured path.
        PermissionPolicy crashingPolicy = req -> {
            throw new IllegalStateException("policy bug");
        };
        var pm = new PermissionManager(crashingPolicy);
        var tool = new SpyCapabilityAwareTool();

        assertThatThrownBy(() -> ToolPermissionRouter.check(
                tool, "{}", "sess-1", "any", PermissionLevel.WRITE, pm, MAPPER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("policy bug");
    }

    @Test
    void nullInputJsonFallsBackToLegacyInsteadOfCrashing() {
        // Upstream (ContentBlock.ToolUse, PermissionAwareTool's parse
        // path) can deliver a tool call with no arguments payload. The
        // router must treat that as "couldn't build a Capability, prompt
        // via legacy" rather than throw NPE — otherwise a recoverable
        // upstream flow becomes an unhandled crash. (Codex review on
        // #482, second pass.)
        var policy = new CapturingPolicy();
        var pm = new PermissionManager(policy);
        var tool = new SpyCapabilityAwareTool();

        var decision = ToolPermissionRouter.check(
                tool, null, "sess-1", "fallback prompt", PermissionLevel.WRITE, pm, MAPPER);

        assertThat(decision).isInstanceOf(PermissionDecision.Approved.class);
        assertThat(tool.toCapabilityCalls.get())
                .as("null input never reaches toCapability — readTree fails first, caught, legacy fallback")
                .isZero();
        assertThat(policy.last.get().toolName())
                .as("legacy fallback was used")
                .isEqualTo("spy_tool");
        assertThat(policy.last.get().level())
                .as("legacy fallback uses caller-supplied level")
                .isEqualTo(PermissionLevel.WRITE);
    }

    @Test
    void daemonInternalCallNullSessionIdGoesThroughBothPaths() {
        // Cron, boot scripts, and other daemon-internal callers pass
        // sessionId == null. The router must accept it (no NPE) and
        // produce a Provenance.daemonInternal()-shaped check via the
        // structured path, falling back unchanged through the legacy
        // path otherwise.
        var policy = new CapturingPolicy();
        var pm = new PermissionManager(policy);

        ToolPermissionRouter.check(
                new SpyCapabilityAwareTool(), "{}", null, "no session",
                PermissionLevel.WRITE, pm, MAPPER);
        assertThat(policy.last.get().toolName()).isEqualTo("spy_tool");

        ToolPermissionRouter.check(
                new LegacyOnlyTool(), "{}", null, "no session",
                PermissionLevel.READ, pm, MAPPER);
        assertThat(policy.last.get().toolName()).isEqualTo("legacy_tool");
    }
}

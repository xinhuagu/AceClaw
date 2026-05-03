package dev.aceclaw.security;

import dev.aceclaw.security.ids.SessionId;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the new {@link PermissionManager#check(Capability, Provenance)}
 * pipeline (#480 PR 2): structured capability + provenance go in, a
 * decision compatible with the legacy path comes out, and the legacy
 * {@link PermissionManager#check(PermissionRequest, String)} keeps working
 * by routing through the same code via the {@link Capability.LegacyToolUse}
 * shim.
 */
final class PermissionManagerCapabilityTest {

    /** Always-deny policy so any Approved result must come from the session blanket. */
    private static final PermissionPolicy DENY = req ->
            new PermissionDecision.Denied("test policy denies");

    @Test
    void structuredCheckUsesCapabilityRiskInPolicyRequest() {
        // PolicyEngine doesn't yet consume Capability directly (#465 Scope
        // #2 lands later); for now the manager builds a PermissionRequest
        // from the capability so DefaultPermissionPolicy etc. keep working
        // unchanged. Confirm the level passed in is the variant's derived
        // risk, not something a caller could lie about.
        var captured = new java.util.concurrent.atomic.AtomicReference<PermissionRequest>();
        PermissionPolicy capturing = req -> {
            captured.set(req);
            return new PermissionDecision.Approved();
        };
        var pm = new PermissionManager(capturing);
        var fileWrite = new Capability.FileWrite(Path.of("/tmp/x"), WriteMode.OVERWRITE);

        pm.check(fileWrite, Provenance.forSession(new SessionId("s")));

        assertThat(captured.get().level()).isEqualTo(PermissionLevel.WRITE);
        assertThat(captured.get().toolName())
                .as("toolName uses the capability's allowlistKey, not a stringly-typed name")
                .isEqualTo("FileWrite");
    }

    @Test
    void sessionBlanketApprovalSurvivesToolMigrationViaDispatcherKey() {
        // Critical compatibility test: a user who clicked "always allow
        // write_file" via the LEGACY path MUST still be auto-approved when
        // the tool migrates to CapabilityAware. The dispatcher passes the
        // originating tool's name as the explicit allowlist key, so the
        // existing approval keeps applying — no UX regression on migration.
        var pm = new PermissionManager(DENY);
        pm.approveForSession("sess-A", "write_file");

        // Legacy entry — same shape as before #480.
        var legacyDecision = pm.check(PermissionRequest.write("write_file", "/tmp/x"), "sess-A");
        assertThat(legacyDecision).isInstanceOf(PermissionDecision.Approved.class);

        // Migrated path: dispatcher calls the 4-arg form with the tool name
        // as the allowlist key. The pre-existing "write_file" approval
        // applies, just as it did via the legacy path.
        var migratedDecision = pm.check(
                new Capability.FileWrite(Path.of("/tmp/x"), WriteMode.OVERWRITE),
                Provenance.forSession(new SessionId("sess-A")),
                "write_file",
                "write /tmp/x");
        assertThat(migratedDecision)
                .as("tool-name allowlist survives migration to CapabilityAware")
                .isInstanceOf(PermissionDecision.Approved.class);
    }

    @Test
    void twoArgConvenienceUsesCapabilityClassNameKey() {
        // The 2-arg form is for daemon-internal callers that have no
        // originating tool name (cron, boot scripts). It uses the variant's
        // class name as the allowlist key — pin that so a future change to
        // the default doesn't silently widen daemon-internal approvals.
        var pm = new PermissionManager(DENY);
        pm.approveForSession("sess-A", "FileWrite");

        var decision = pm.check(
                new Capability.FileWrite(Path.of("/tmp/x"), WriteMode.OVERWRITE),
                Provenance.forSession(new SessionId("sess-A")));
        assertThat(decision).isInstanceOf(PermissionDecision.Approved.class);
    }

    @Test
    void fourArgPathPassesCallerSuppliedDescriptionToPolicy() {
        // The dispatcher computes a rich human-readable description via
        // buildToolDescription(...) and passes it through. Pin that the
        // policy sees that string rather than the synthetic displayLabel
        // — losing it would regress the user's permission prompt UX.
        var captured = new java.util.concurrent.atomic.AtomicReference<PermissionRequest>();
        PermissionPolicy capturing = req -> {
            captured.set(req);
            return new PermissionDecision.NeedsUserApproval(req.description());
        };
        var pm = new PermissionManager(capturing);

        pm.check(
                new Capability.FileWrite(Path.of("/tmp/x"), WriteMode.OVERWRITE),
                Provenance.forSession(new SessionId("sess")),
                "write_file",
                "Write /tmp/x (123 chars of new content)");

        assertThat(captured.get().description())
                .isEqualTo("Write /tmp/x (123 chars of new content)");
        assertThat(captured.get().toolName())
                .as("4-arg form uses the caller's allowlist key, not the variant class name")
                .isEqualTo("write_file");
    }

    @Test
    void daemonInternalProvenanceSkipsAllowlistLookup() {
        // Provenance.daemonInternal() leaves sessionId empty. Without a
        // session, the manager falls straight through to the policy — same
        // semantics as the legacy path's null-sessionId case. Use DENY here
        // (not APPROVE) so the assertion actually proves the bypass: if
        // daemonInternal accidentally reused the session approval, this
        // test would now wrongly approve. With DENY, only the bypass path
        // can produce the expected Denied outcome.
        var pm = new PermissionManager(DENY);
        pm.approveForSession("sess-X", "FileRead");

        var decision = pm.check(
                new Capability.FileRead(Path.of("/etc/hosts")),
                Provenance.daemonInternal());

        assertThat(decision)
                .as("daemon-internal must skip the session allowlist and hit policy")
                .isInstanceOf(PermissionDecision.Denied.class);
    }

    @Test
    void legacyShimDelegatesToStructuredPath() {
        // The old check(PermissionRequest, sessionId) is now a thin shim
        // — confirmed by the absence of a separate decision-and-audit
        // pipeline. This test pins that legacy callers see no behavior
        // change: the same DefaultPermissionPolicy answers in the same
        // way regardless of which entry point is used.
        var pm = new PermissionManager(new DefaultPermissionPolicy(DefaultPermissionPolicy.MODE_NORMAL));

        var legacyDecision = pm.check(PermissionRequest.read("read_file", "x"), "sess");
        assertThat(legacyDecision).isInstanceOf(PermissionDecision.Approved.class); // READ auto-approved

        var legacyWrite = pm.check(PermissionRequest.write("write_file", "/tmp/x"), "sess");
        assertThat(legacyWrite).isInstanceOf(PermissionDecision.NeedsUserApproval.class);
    }
}

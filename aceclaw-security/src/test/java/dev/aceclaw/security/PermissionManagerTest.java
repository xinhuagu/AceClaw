package dev.aceclaw.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link PermissionManager}'s per-session "remember" scope
 * (issue #456). Earlier the allow-list was a single Set shared by
 * every session attached to the daemon, so clicking Always Allow in
 * session A silently auto-approved that tool in session B too.
 *
 * <p>The fix routes session approvals through a {@code Map<String,
 * Set<String>>} keyed by sessionId. These tests pin the resulting
 * scoping rules:
 *
 * <ul>
 *   <li>An approval in one session does NOT leak to another.</li>
 *   <li>Clearing one session's approvals does NOT touch another's.</li>
 *   <li>A null sessionId on {@code check} skips the lookup and falls
 *       through to the policy (used by daemon-internal checks).</li>
 *   <li>{@code clearAllSessionApprovals} is the test helper for
 *       resetting state between integration cases.</li>
 * </ul>
 */
class PermissionManagerTest {

    private static final PermissionRequest WRITE = PermissionRequest.write(
            "write_file", "Write to /tmp/foo");

    /** Always-deny policy so the only Approved path is via session-blanket. */
    private static final PermissionPolicy DENY_POLICY = req ->
            new PermissionDecision.Denied("test policy denies");

    @Test
    void approvalInOneSessionDoesNotLeakToAnother() {
        var pm = new PermissionManager(DENY_POLICY);
        pm.approveForSession("sess-A", "write_file");

        // Session A: blanket-approved → Approved.
        assertThat(pm.check(WRITE, "sess-A"))
                .isInstanceOf(PermissionDecision.Approved.class);
        // Session B: NOT in B's allow-list → policy runs → Denied.
        assertThat(pm.check(WRITE, "sess-B"))
                .isInstanceOf(PermissionDecision.Denied.class);
        // hasSessionApproval reflects the same scoping.
        assertThat(pm.hasSessionApproval("sess-A", "write_file")).isTrue();
        assertThat(pm.hasSessionApproval("sess-B", "write_file")).isFalse();
    }

    @Test
    void clearSessionApprovalsTouchesOnlyTheNamedSession() {
        var pm = new PermissionManager(DENY_POLICY);
        pm.approveForSession("sess-A", "write_file");
        pm.approveForSession("sess-B", "write_file");

        pm.clearSessionApprovals("sess-A");

        assertThat(pm.hasSessionApproval("sess-A", "write_file")).isFalse();
        assertThat(pm.hasSessionApproval("sess-B", "write_file")).isTrue();
    }

    @Test
    void clearAllSessionApprovalsResetsEverySession() {
        var pm = new PermissionManager(DENY_POLICY);
        pm.approveForSession("sess-A", "write_file");
        pm.approveForSession("sess-B", "bash");

        pm.clearAllSessionApprovals();

        assertThat(pm.hasSessionApproval("sess-A", "write_file")).isFalse();
        assertThat(pm.hasSessionApproval("sess-B", "bash")).isFalse();
    }

    @Test
    void nullSessionIdSkipsAllowListAndFallsThroughToPolicy() {
        // Daemon-internal callers may not have a session in scope —
        // null is allowed and bypasses the per-session lookup. The
        // request still has to clear the policy.
        var pm = new PermissionManager(DENY_POLICY);
        pm.approveForSession("sess-A", "write_file");
        // null sessionId: even though "write_file" is in sess-A's
        // allow-list, this caller doesn't get a free pass.
        assertThat(pm.check(WRITE, null))
                .isInstanceOf(PermissionDecision.Denied.class);
    }

    @Test
    void approveForSessionIsIdempotentAndUnionsAcrossTools() {
        var pm = new PermissionManager(DENY_POLICY);
        pm.approveForSession("sess-A", "write_file");
        pm.approveForSession("sess-A", "write_file"); // duplicate
        pm.approveForSession("sess-A", "bash");

        assertThat(pm.hasSessionApproval("sess-A", "write_file")).isTrue();
        assertThat(pm.hasSessionApproval("sess-A", "bash")).isTrue();
        // A tool that was never approved stays denied.
        assertThat(pm.hasSessionApproval("sess-A", "edit_file")).isFalse();
    }
}

package dev.aceclaw.security;

import dev.aceclaw.security.audit.CapabilityAuditEntry;
import dev.aceclaw.security.audit.CapabilityAuditLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Tests that {@link PermissionManager} writes one signed audit entry
 * per {@code .check()} call when an audit log is attached, and that
 * the three sealed {@link PermissionDecision} variants flatten to
 * the right on-disk representation.
 *
 * <p>This is the integration boundary for #465 Layer 8 v1: every
 * call site of permission checks goes through {@code PermissionManager},
 * so this is the single point where audit coverage is enforced. If
 * a future refactor removes the audit hook here, the audit log silently
 * stops growing — these tests catch that regression.
 */
final class PermissionManagerAuditTest {

    private static final PermissionRequest READ_REQ =
            PermissionRequest.read("read_file", "read /tmp/foo");
    private static final PermissionRequest WRITE_REQ =
            PermissionRequest.write("write_file", "write /tmp/foo");
    private static final PermissionRequest EXEC_REQ =
            PermissionRequest.execute("bash", "rm /tmp/foo");

    private static final PermissionPolicy APPROVE_POLICY = req ->
            new PermissionDecision.Approved();
    private static final PermissionPolicy DENY_POLICY = req ->
            new PermissionDecision.Denied("policy denies");
    private static final PermissionPolicy NEEDS_APPROVAL_POLICY = req ->
            new PermissionDecision.NeedsUserApproval("approve write to /tmp/foo?");

    @Test
    void approvedDecisionIsRecordedAsApproved(@TempDir Path tmp) throws IOException {
        var auditLog = CapabilityAuditLog.create(tmp);
        var pm = new PermissionManager(APPROVE_POLICY, auditLog);

        pm.check(READ_REQ, "sess-1");

        List<CapabilityAuditEntry> entries = auditLog.readVerified();
        assertThat(entries).hasSize(1);
        var e = entries.getFirst();
        assertThat(e.decisionKind()).isEqualTo("APPROVED");
        assertThat(e.toolName()).isEqualTo("read_file");
        assertThat(e.level()).isEqualTo(PermissionLevel.READ);
        assertThat(e.sessionId()).isEqualTo("sess-1");
        // No reason attached for plain policy-approved decisions.
        assertThat(e.reason()).isNull();
    }

    @Test
    void deniedDecisionRecordsTheDenialReason(@TempDir Path tmp) throws IOException {
        var auditLog = CapabilityAuditLog.create(tmp);
        var pm = new PermissionManager(DENY_POLICY, auditLog);

        pm.check(EXEC_REQ, "sess-1");

        var entry = auditLog.readVerified().getFirst();
        assertThat(entry.decisionKind()).isEqualTo("DENIED");
        // The reason from PermissionDecision.Denied makes it onto disk
        // so post-mortem queries can group denials by cause.
        assertThat(entry.reason()).isEqualTo("policy denies");
    }

    @Test
    void needsUserApprovalRecordsThePromptText(@TempDir Path tmp) throws IOException {
        var auditLog = CapabilityAuditLog.create(tmp);
        var pm = new PermissionManager(NEEDS_APPROVAL_POLICY, auditLog);

        pm.check(WRITE_REQ, "sess-1");

        var entry = auditLog.readVerified().getFirst();
        assertThat(entry.decisionKind()).isEqualTo("NEEDS_APPROVAL");
        assertThat(entry.reason()).isEqualTo("approve write to /tmp/foo?");
    }

    @Test
    void sessionBlanketApprovalIsTaggedInTheReasonField(@TempDir Path tmp) throws IOException {
        // The session-blanket branch short-circuits before the policy
        // — auditors need to be able to distinguish "user clicked
        // Always Allow earlier" from "policy auto-approved", because
        // the trust models differ.
        var auditLog = CapabilityAuditLog.create(tmp);
        var pm = new PermissionManager(DENY_POLICY, auditLog);
        pm.approveForSession("sess-1", "write_file");

        pm.check(WRITE_REQ, "sess-1");

        var entry = auditLog.readVerified().getFirst();
        assertThat(entry.decisionKind()).isEqualTo("APPROVED");
        assertThat(entry.reason()).isEqualTo("session-blanket-approval");
    }

    @Test
    void nullSessionIdIsRecordedAsNull(@TempDir Path tmp) throws IOException {
        var auditLog = CapabilityAuditLog.create(tmp);
        var pm = new PermissionManager(APPROVE_POLICY, auditLog);

        pm.check(READ_REQ, null);

        var entry = auditLog.readVerified().getFirst();
        assertThat(entry.sessionId()).isNull();
    }

    @Test
    void noAuditLogAttachedIsANoOp() {
        // Single-arg constructor (or null) means audit is disabled.
        // The check itself must still work end-to-end without throwing.
        var pm = new PermissionManager(APPROVE_POLICY);

        assertThatCode(() -> pm.check(READ_REQ, "sess-1")).doesNotThrowAnyException();
        assertThat(pm.check(WRITE_REQ, "sess-1"))
                .isInstanceOf(PermissionDecision.Approved.class);
    }

    @Test
    void everyCheckCallProducesExactlyOneEntry(@TempDir Path tmp) throws IOException {
        var auditLog = CapabilityAuditLog.create(tmp);
        var pm = new PermissionManager(APPROVE_POLICY, auditLog);

        for (int i = 0; i < 5; i++) {
            pm.check(READ_REQ, "sess-" + i);
        }

        // Sanity: 5 calls in, 5 entries out — no dropping, no
        // double-writing.
        assertThat(auditLog.readVerified()).hasSize(5);
    }
}

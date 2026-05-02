package dev.aceclaw.daemon;

import dev.aceclaw.security.DefaultPermissionPolicy;
import dev.aceclaw.security.PermissionManager;
import dev.aceclaw.security.PermissionRequest;
import dev.aceclaw.security.audit.CapabilityAuditEntry;
import dev.aceclaw.security.audit.CapabilityAuditLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the daemon-startup wiring that connects {@link CapabilityAuditLog}
 * into {@link PermissionManager} (#465 Layer 8 wiring; Codex P1 follow-up
 * from #474).
 *
 * <p>Two paths matter:
 * <ul>
 *   <li><b>Happy path</b> — the helper builds an audit log and the resulting
 *       PermissionManager actually writes a verifiable entry on every
 *       {@link PermissionManager#check} call. Without this, the audit
 *       subsystem produces no on-disk records in production even though
 *       all the pieces compile and load.</li>
 *   <li><b>Degraded path</b> — if the audit directory can't be created
 *       (read-only home dir, permission error), the helper returns
 *       {@code null} rather than throwing. The daemon must keep starting
 *       up even when auditing is unavailable; treating audit setup as
 *       fatal would turn a compliance feature into a denial-of-service
 *       on the agent itself.</li>
 * </ul>
 *
 * <p>Uses {@link TempDir} for the audit directory throughout — never
 * touches the real {@code ~/.aceclaw/}.
 */
final class AceClawDaemonAuditWiringTest {

    @Test
    void buildCapabilityAuditLog_returnsWorkingLogWhenDirIsWritable(@TempDir Path tmp) {
        // End-to-end: helper builds → PermissionManager records → entry
        // appears verified on disk. This is the wiring path the daemon
        // takes on a fresh install.
        CapabilityAuditLog auditLog = AceClawDaemon.buildCapabilityAuditLog(tmp);

        assertThat(auditLog)
                .as("writable dir must yield a non-null audit log")
                .isNotNull();

        var pm = new PermissionManager(new DefaultPermissionPolicy("normal"), auditLog);
        pm.check(PermissionRequest.execute("bash", "rm /tmp/test"), "session-1");

        List<CapabilityAuditEntry> entries = auditLog.readVerified();
        assertThat(entries).hasSize(1);
        var entry = entries.getFirst();
        assertThat(entry.toolName()).isEqualTo("bash");
        assertThat(entry.sessionId()).isEqualTo("session-1");
        // "normal" policy treats EXECUTE as needs-user-approval; the
        // exact decision is the policy's call, but it must surface in
        // the entry rather than being skipped.
        assertThat(entry.decisionKind()).isNotBlank();
        assertThat(entry.signature()).isNotBlank();
    }

    @Test
    void buildCapabilityAuditLog_returnsNullWhenDirCannotBeCreated(@TempDir Path tmp) throws IOException {
        // Stage a regular file at the path we're about to ask Files.
        // createDirectories to materialise as a directory. POSIX won't
        // turn a file into a directory, so create() throws IOException
        // and the helper must return null instead of letting the
        // daemon crash.
        Path blocked = tmp.resolve("audit-collision");
        Files.writeString(blocked, "this is a regular file, not a directory");

        CapabilityAuditLog auditLog = AceClawDaemon.buildCapabilityAuditLog(blocked);

        assertThat(auditLog)
                .as("unwritable audit dir must downgrade to null, never throw")
                .isNull();

        // Sanity: a PermissionManager built with the null result still
        // works (audit silently disabled). Mirrors what the daemon
        // actually does — the PermissionManager 2-arg constructor
        // accepts null for "audit off".
        var pm = new PermissionManager(new DefaultPermissionPolicy("normal"), auditLog);
        // Should not throw — proves the degraded path is genuinely
        // benign rather than masking a downstream NPE.
        pm.check(PermissionRequest.read("read_file", "x"), "session-1");
    }
}

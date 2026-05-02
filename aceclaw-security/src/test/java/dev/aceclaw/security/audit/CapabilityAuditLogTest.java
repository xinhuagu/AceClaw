package dev.aceclaw.security.audit;

import dev.aceclaw.security.PermissionLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link CapabilityAuditLog} — the signed JSONL append store
 * that anchors #465 Layer 8 v1. These cover:
 *
 * <ul>
 *   <li>round-trip: what we wrote is what we read, with the signature
 *       intact and the schema fields preserved (timestamp, sessionId
 *       nullable, tool, level, decision kind, reason);</li>
 *   <li>tamper detection: a hand-edited line is dropped from the
 *       verified read so reporting can't be poisoned;</li>
 *   <li>missing-file behaviour: queries before any write don't crash;</li>
 *   <li>concurrency: parallel virtual-thread appends produce one
 *       valid line per call, none lost or interleaved.</li>
 * </ul>
 */
final class CapabilityAuditLogTest {

    @Test
    void recordAndReadRoundtripPreservesAllFields(@TempDir Path tmp) throws IOException {
        var auditLog = CapabilityAuditLog.create(tmp);

        Instant ts = Instant.parse("2026-05-01T12:34:56Z");
        auditLog.record(ts, "sess-A", "bash", PermissionLevel.EXECUTE, "APPROVED", null);

        List<CapabilityAuditEntry> entries = auditLog.readVerified();
        assertThat(entries).hasSize(1);
        var e = entries.getFirst();
        assertThat(e.timestamp()).isEqualTo(ts);
        assertThat(e.sessionId()).isEqualTo("sess-A");
        assertThat(e.toolName()).isEqualTo("bash");
        assertThat(e.level()).isEqualTo(PermissionLevel.EXECUTE);
        assertThat(e.decisionKind()).isEqualTo("APPROVED");
        assertThat(e.reason()).isNull();
        assertThat(e.signature()).isNotBlank();
    }

    @Test
    void preservesAppendOrderAcrossMultipleWrites(@TempDir Path tmp) throws IOException {
        var auditLog = CapabilityAuditLog.create(tmp);
        auditLog.record(Instant.now(), "s", "read_file", PermissionLevel.READ, "APPROVED", null);
        auditLog.record(Instant.now(), "s", "write_file", PermissionLevel.WRITE,
                "NEEDS_APPROVAL", "Write to /tmp/foo?");
        auditLog.record(Instant.now(), "s", "bash", PermissionLevel.EXECUTE,
                "DENIED", "blocked by policy");

        List<CapabilityAuditEntry> entries = auditLog.readVerified();
        assertThat(entries).extracting(CapabilityAuditEntry::toolName)
                .containsExactly("read_file", "write_file", "bash");
        assertThat(entries).extracting(CapabilityAuditEntry::decisionKind)
                .containsExactly("APPROVED", "NEEDS_APPROVAL", "DENIED");
    }

    @Test
    void nullSessionIdSurvivesRoundtrip(@TempDir Path tmp) throws IOException {
        // Daemon-internal checks pass null sessionId. The on-disk
        // shape encodes this as JSON null and reads back as Java null.
        var auditLog = CapabilityAuditLog.create(tmp);
        auditLog.record(Instant.now(), null, "internal", PermissionLevel.READ, "APPROVED", null);

        var entry = auditLog.readVerified().getFirst();
        assertThat(entry.sessionId()).isNull();
    }

    @Test
    void readVerifiedReturnsEmptyWhenFileMissing(@TempDir Path tmp) throws IOException {
        // Construct an audit log whose file has not yet been written —
        // first call from a daemon process would land in this state.
        var auditLog = CapabilityAuditLog.create(tmp);
        assertThat(auditLog.readVerified()).isEmpty();
    }

    @Test
    void tamperedEntryIsDroppedOnRead(@TempDir Path tmp) throws IOException {
        var auditLog = CapabilityAuditLog.create(tmp);
        auditLog.record(Instant.now(), "s", "bash", PermissionLevel.EXECUTE, "DENIED", "no");
        auditLog.record(Instant.now(), "s", "bash", PermissionLevel.EXECUTE, "APPROVED", null);

        // Hand-edit the file: flip line 1's "DENIED" to "APPROVED"
        // without re-signing — exactly the attack the signature
        // exists to detect.
        Path file = auditLog.auditFile();
        String contents = Files.readString(file);
        String tampered = contents.replaceFirst("DENIED", "APPROVED");
        Files.writeString(file, tampered);

        List<CapabilityAuditEntry> entries = auditLog.readVerified();
        // The tampered line is dropped; the legitimate one survives.
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().decisionKind()).isEqualTo("APPROVED");
        assertThat(entries.getFirst().reason()).isNull();
    }

    @Test
    void malformedJsonLineIsSkippedNotThrown(@TempDir Path tmp) throws IOException {
        // A partial write or accidental edit could leave invalid JSON
        // mid-file. The reader should skip those and return what's
        // still verifiable rather than failing the whole query.
        var auditLog = CapabilityAuditLog.create(tmp);
        auditLog.record(Instant.now(), "s", "bash", PermissionLevel.EXECUTE, "APPROVED", null);

        Path file = auditLog.auditFile();
        Files.writeString(file, "this is not json\n" + Files.readString(file));

        var entries = auditLog.readVerified();
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().toolName()).isEqualTo("bash");
    }

    @Test
    void concurrentAppendsAreSerialized(@TempDir Path tmp) throws Exception {
        // PermissionManager.check() is called from many concurrent
        // virtual threads. Two threads racing on Files.writeString
        // could otherwise interleave bytes and produce a malformed
        // line. The ReentrantLock around append guards against that.
        var auditLog = CapabilityAuditLog.create(tmp);
        int writers = 50;
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(writers);

        IntStream.range(0, writers).forEach(i -> Thread.ofVirtual().start(() -> {
            try {
                start.await();
                auditLog.record(Instant.now(), "s-" + i, "bash",
                        PermissionLevel.EXECUTE, "APPROVED", null);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        }));
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS))
                .as("all writers must finish within 10s")
                .isTrue();

        var entries = auditLog.readVerified();
        // Every write produces exactly one verifiable entry — no
        // dropped or corrupted lines.
        assertThat(entries).hasSize(writers);
    }
}

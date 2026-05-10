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
    void v2EntryRoundtripsCapabilityAndProvenance(@TempDir Path tmp) throws IOException {
        // The defining test for #480 PR 3's audit shift: a v2 record must
        // serialize the typed Capability + Provenance, verify on read, and
        // come back as the same variant with the same fields. If this
        // fails the on-disk format diverged from what the codecs expect.
        //
        // Path is built with {@code tmp.resolve(...)} (not a hardcoded
        // {@code "/tmp/x"}) because Jackson's default {@link Path}
        // (de)serializer round-trips through {@code Path.toUri() ->
        // Paths.get(URI)}, which on Windows resolves a leading-slash path
        // against the working drive (so {@code "/tmp/x"} becomes
        // {@code D:\tmp\x} after the round trip and a string-shape
        // assertion fails). Using a real {@code @TempDir} path keeps the
        // identity stable on every host OS — {@link Path#equals} compares
        // normalised platform-native form, which the round trip preserves.
        var auditLog = CapabilityAuditLog.create(tmp);
        var ts = Instant.parse("2026-05-08T09:30:00Z");
        var targetPath = tmp.resolve("subdir").resolve("payload.txt");
        var cap = new dev.aceclaw.security.Capability.FileWrite(
                targetPath,
                dev.aceclaw.security.WriteMode.OVERWRITE);
        var prov = dev.aceclaw.security.Provenance.forSession(
                new dev.aceclaw.security.ids.SessionId("sess-A"));

        auditLog.record(ts, "write_file", cap, prov, "APPROVED", null);

        var entries = auditLog.readVerified();
        assertThat(entries).hasSize(1);
        var e = entries.getFirst();

        // Denormalised v1 fields populated for query-tool compatibility:
        // toolName carries the dispatcher's allowlist key, level mirrors
        // the capability's structural risk class.
        assertThat(e.toolName()).isEqualTo("write_file");
        assertThat(e.level())
                .as("v2 entry's flat level field must match the capability's risk")
                .isEqualTo(dev.aceclaw.security.PermissionLevel.WRITE);
        assertThat(e.sessionId()).isEqualTo("sess-A");
        assertThat(e.schemaVersion()).isEqualTo(CapabilityAuditEntry.SCHEMA_V2);

        // Structured payload survives the round trip.
        assertThat(e.capability())
                .isInstanceOf(dev.aceclaw.security.Capability.FileWrite.class);
        var fw = (dev.aceclaw.security.Capability.FileWrite) e.capability();
        assertThat(fw.path())
                .as("Path round-trips identically (Path.equals, not toString)")
                .isEqualTo(targetPath);
        assertThat(fw.mode()).isEqualTo(dev.aceclaw.security.WriteMode.OVERWRITE);
        assertThat(e.provenance().sessionId().get().value()).isEqualTo("sess-A");
    }

    @Test
    void v2FileSearchRoundtripsWithoutKindCollision(@TempDir Path tmp) throws IOException {
        // Regression for the Jackson type-discriminator collision (Codex
        // P2 on #491). FileSearch.kind (the SearchKind enum) used to
        // share JSON property name "kind" with the @JsonTypeInfo
        // discriminator on Capability — duplicate keys collapsed
        // last-wins on read, the discriminator became "GLOB"/"GREP"/"LIST"
        // instead of "FileSearch", and every glob/grep/list_directory v2
        // entry got dropped from readVerified() with no audit trail.
        // The fix: discriminator is now property "@type", which cannot
        // collide because "@" is not a legal Java identifier prefix.
        var auditLog = CapabilityAuditLog.create(tmp);
        for (var kind : dev.aceclaw.security.SearchKind.values()) {
            var cap = new dev.aceclaw.security.Capability.FileSearch(
                    tmp.resolve("project"), "**/*.java", kind);
            var prov = dev.aceclaw.security.Provenance.forSession(
                    new dev.aceclaw.security.ids.SessionId("sess-fs"));
            auditLog.record(Instant.now(), kind == dev.aceclaw.security.SearchKind.GLOB
                    ? "glob" : kind == dev.aceclaw.security.SearchKind.GREP ? "grep" : "list_directory",
                    cap, prov, "APPROVED", null);
        }

        var entries = auditLog.readVerified();
        assertThat(entries)
                .as("all 3 FileSearch variants must verify after the @type discriminator rename — "
                        + "regression guard for Codex P2 (kind/SearchKind collision)")
                .hasSize(dev.aceclaw.security.SearchKind.values().length);

        // Each entry must round-trip into the right SearchKind, not lose
        // the field to the type-discriminator overwrite.
        for (var entry : entries) {
            assertThat(entry.capability())
                    .isInstanceOf(dev.aceclaw.security.Capability.FileSearch.class);
            var fs = (dev.aceclaw.security.Capability.FileSearch) entry.capability();
            assertThat(fs.kind()).isNotNull();  // would be null pre-fix (collapsed key)
        }
    }

    @Test
    void v1EntriesContinueToVerifyAfterSchemaBump(@TempDir Path tmp) throws IOException {
        // Migration safety: v1 entries written under PR 2 must continue to
        // verify after PR 3 bumps the schema. The v1 signablePayload format
        // is preserved bit-for-bit; this test pins that.
        var auditLog = CapabilityAuditLog.create(tmp);
        auditLog.record(Instant.now(), "sess-X", "bash",
                dev.aceclaw.security.PermissionLevel.EXECUTE, "APPROVED", null);

        var entries = auditLog.readVerified();
        assertThat(entries).hasSize(1);
        var e = entries.getFirst();
        assertThat(e.schemaVersion()).isEqualTo(CapabilityAuditEntry.SCHEMA_V1);
        assertThat(e.capability()).isNull();
        assertThat(e.provenance()).isNull();
    }

    @Test
    void mixedV1AndV2EntriesInSameFileBothVerify(@TempDir Path tmp) throws IOException {
        // Real-world deployment: an existing audit file has v1 entries
        // already. After upgrading to PR 3, new entries are v2. The same
        // file must continue to read cleanly with all entries verifying
        // under the same key — proving each line's HMAC is computed
        // against its own schema, not a single global shape.
        var auditLog = CapabilityAuditLog.create(tmp);

        // Three v1 lines (legacy 6-arg overload) interleaved with two v2.
        auditLog.record(Instant.now(), "s1", "read_file",
                dev.aceclaw.security.PermissionLevel.READ, "APPROVED", null);
        auditLog.record(Instant.now(), "write_file",
                new dev.aceclaw.security.Capability.FileWrite(
                        java.nio.file.Path.of("/tmp/a"),
                        dev.aceclaw.security.WriteMode.CREATE_NEW),
                dev.aceclaw.security.Provenance.forSession(
                        new dev.aceclaw.security.ids.SessionId("s2")),
                "APPROVED", null);
        auditLog.record(Instant.now(), "s3", "bash",
                dev.aceclaw.security.PermissionLevel.EXECUTE, "DENIED",
                "blocked by policy");
        auditLog.record(Instant.now(), "bash",
                new dev.aceclaw.security.Capability.BashExec(
                        "rm -rf /tmp/x", java.nio.file.Path.of("/tmp")),
                dev.aceclaw.security.Provenance.forSession(
                        new dev.aceclaw.security.ids.SessionId("s4")),
                "NEEDS_APPROVAL", "destructive command");
        auditLog.record(Instant.now(), null, "internal",
                dev.aceclaw.security.PermissionLevel.READ, "APPROVED", null);

        var entries = auditLog.readVerified();
        assertThat(entries).hasSize(5);
        assertThat(entries.stream().map(CapabilityAuditEntry::schemaVersion).toList())
                .containsExactly(
                        CapabilityAuditEntry.SCHEMA_V1, CapabilityAuditEntry.SCHEMA_V2,
                        CapabilityAuditEntry.SCHEMA_V1, CapabilityAuditEntry.SCHEMA_V2,
                        CapabilityAuditEntry.SCHEMA_V1);

        // The v2 BashExec entry must have escalated to DANGEROUS via the
        // variant's self-classification — pinning that the on-disk
        // {@code level} field reflects the capability's actual risk class
        // after escalation, not the {@code EXECUTE} default.
        var bashEntry = entries.get(3);
        assertThat(bashEntry.level())
                .as("destructive bash must be recorded as DANGEROUS in audit, not EXECUTE")
                .isEqualTo(dev.aceclaw.security.PermissionLevel.DANGEROUS);
    }

    @Test
    void v2EntryHmacChangesIfCapabilityFieldsChange(@TempDir Path tmp) throws IOException {
        // Tamper-evidence guard: editing the structured payload (e.g.
        // changing /tmp/x → /etc/passwd in the on-disk JSON) must invalidate
        // the signature. The v2 signablePayload includes a canonical-
        // serialised form of the capability, so any field change flips it.
        var auditLog = CapabilityAuditLog.create(tmp);
        var prov = dev.aceclaw.security.Provenance.forSession(
                new dev.aceclaw.security.ids.SessionId("s"));
        auditLog.record(Instant.now(), "write_file",
                new dev.aceclaw.security.Capability.FileWrite(
                        java.nio.file.Path.of("/tmp/x"),
                        dev.aceclaw.security.WriteMode.OVERWRITE),
                prov, "APPROVED", null);

        // Hand-edit the path on disk — same signature, different capability.
        var contents = java.nio.file.Files.readString(auditLog.auditFile());
        var tampered = contents.replace("/tmp/x", "/etc/passwd");
        assertThat(tampered).isNotEqualTo(contents);
        java.nio.file.Files.writeString(auditLog.auditFile(), tampered);

        // Read should drop the tampered line — the audit is tamper-evident.
        assertThat(auditLog.readVerified())
                .as("path-tampered v2 entry must fail signature verification")
                .isEmpty();
    }


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

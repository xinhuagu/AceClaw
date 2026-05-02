package dev.aceclaw.security.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.aceclaw.security.PermissionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Append-only signed audit log for capability decisions (#465 Layer 8 v1).
 *
 * <p>Mirrors the {@code InjectionAuditLog} pattern used for memory
 * candidate injections — JSONL on disk, ReentrantLock around append
 * for concurrent virtual threads, lazy directory creation. The
 * differentiator vs. that log is signing: every entry is HMAC-SHA256
 * signed at write time and verified on read. Tampered or wrong-key
 * entries are dropped from query results with a warning so a corrupted
 * file doesn't poison reporting.
 *
 * <p>On-disk location: {@code ~/.aceclaw/audit/capability-audit.jsonl}
 * with the signing key at {@code ~/.aceclaw/audit/audit.key}.
 *
 * <p>This v1 attaches to the existing {@link dev.aceclaw.security.PermissionManager#check}
 * path. When the runtime-governance epic introduces unified
 * {@code CapabilityRequest}, the same log naturally extends to record
 * those richer requests too — schema is forward-additive (new fields
 * are added to {@link CapabilityAuditEntry}'s signable payload, old
 * keys removed only by version bump).
 */
public final class CapabilityAuditLog {

    private static final Logger log = LoggerFactory.getLogger(CapabilityAuditLog.class);
    private static final String AUDIT_FILE = "capability-audit.jsonl";
    private static final String KEY_FILE = "audit.key";

    private final Path auditFile;
    private final AuditLogSigner signer;
    private final ObjectMapper mapper;
    private final ReentrantLock writeLock = new ReentrantLock();

    /**
     * Creates an audit log rooted at {@code auditDir}. The directory
     * and key file are materialised up front — {@code auditDir} is
     * created if missing and the signing key is written to
     * {@code audit.key} on first call (POSIX 600 where supported).
     * Only the JSONL file itself is lazy: it appears on the first
     * {@link #record} call.
     */
    public static CapabilityAuditLog create(Path auditDir) throws IOException {
        Objects.requireNonNull(auditDir, "auditDir");
        Files.createDirectories(auditDir);
        AuditLogSigner signer = AuditLogSigner.loadOrCreate(auditDir.resolve(KEY_FILE));
        return new CapabilityAuditLog(auditDir.resolve(AUDIT_FILE), signer);
    }

    /** Visible for testing — production code should use {@link #create}. */
    CapabilityAuditLog(Path auditFile, AuditLogSigner signer) {
        this.auditFile = Objects.requireNonNull(auditFile, "auditFile");
        this.signer = Objects.requireNonNull(signer, "signer");
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /**
     * Signs and appends one decision. The signature is computed over
     * {@link CapabilityAuditEntry#signablePayload()}, then attached.
     * Best-effort: an IO failure logs a warning rather than throwing,
     * because the calling permission check has already produced its
     * decision and the agent shouldn't be aborted by a degraded log.
     */
    public void record(
            Instant timestamp,
            String sessionId,
            String toolName,
            PermissionLevel level,
            String decisionKind,
            String reason) {
        // Build with a placeholder signature, sign the canonical
        // payload, then re-emit with the real signature attached.
        // Two-step is necessary because the signature is a field of
        // the entry but is also derived from its other fields.
        var unsigned = new CapabilityAuditEntry(
                timestamp, sessionId, toolName, level, decisionKind, reason, "unsigned");
        var entry = unsigned.withSignature(signer.sign(unsigned.signablePayload()));
        appendEntry(entry);
    }

    /**
     * Returns all entries from the log whose signature verifies under
     * the current key. Tampered, malformed, or wrong-key entries are
     * skipped with a warning — they don't fail the read. Order matches
     * disk order (append order = decision order).
     *
     * <p>Returns an empty list if the file doesn't exist yet.
     */
    public List<CapabilityAuditEntry> readVerified() {
        if (!Files.isRegularFile(auditFile)) {
            return List.of();
        }
        var verified = new ArrayList<CapabilityAuditEntry>();
        try {
            int lineNo = 0;
            int dropped = 0;
            for (String line : Files.readAllLines(auditFile)) {
                lineNo++;
                if (line.isBlank()) continue;
                CapabilityAuditEntry entry;
                try {
                    entry = mapper.readValue(line, CapabilityAuditEntry.class);
                } catch (IOException parseError) {
                    log.warn("Audit log {} line {} is malformed; dropping. cause={}",
                            auditFile, lineNo, parseError.getMessage());
                    dropped++;
                    continue;
                }
                if (!signer.verify(entry.signablePayload(), entry.signature())) {
                    log.warn("Audit log {} line {} failed signature verification; dropping",
                            auditFile, lineNo);
                    dropped++;
                    continue;
                }
                verified.add(entry);
            }
            if (dropped > 0) {
                log.warn("Dropped {} of {} audit entries from {} due to verification failure",
                        dropped, lineNo, auditFile);
            }
        } catch (IOException e) {
            log.warn("Failed to read audit log {}: {}", auditFile, e.getMessage());
        }
        return verified;
    }

    /** Path to the audit file (may not exist yet if no entries written). */
    public Path auditFile() {
        return auditFile;
    }

    private void appendEntry(CapabilityAuditEntry entry) {
        writeLock.lock();
        try {
            // Parent dir was created in factory but tolerate manual
            // deletion between create() and first record() — a missing
            // parent directory should not crash the daemon.
            Files.createDirectories(auditFile.getParent());
            Files.writeString(auditFile, mapper.writeValueAsString(entry) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Failed to write capability audit entry: {}", e.getMessage());
        } finally {
            writeLock.unlock();
        }
    }
}

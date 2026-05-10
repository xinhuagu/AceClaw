package dev.aceclaw.security.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.aceclaw.security.Capability;
import dev.aceclaw.security.PermissionLevel;
import dev.aceclaw.security.Provenance;
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
 * Append-only signed audit log for capability decisions (#465 Layer 8).
 *
 * <p>Mirrors the {@code InjectionAuditLog} pattern used for memory candidate
 * injections — JSONL on disk, ReentrantLock around append for concurrent
 * virtual threads, lazy directory creation. The differentiator vs. that log
 * is signing: every entry is HMAC-SHA256 signed at write time and verified
 * on read. Tampered or wrong-key entries are dropped from query results
 * with a warning so a corrupted file doesn't poison reporting.
 *
 * <p>On-disk location: {@code ~/.aceclaw/audit/capability-audit.jsonl} with
 * the signing key at {@code ~/.aceclaw/audit/audit.key}.
 *
 * <h3>Schema versions</h3>
 *
 * <ul>
 *   <li><strong>v1</strong> (PR 2 era) — flat {@code (toolName, level)} only,
 *       no {@code schemaVersion} field on disk. Written by the legacy
 *       {@link #record(Instant, String, String, PermissionLevel, String, String)
 *       6-arg overload} and parsed back via the manual JsonNode path so the
 *       missing fields don't cause record-constructor failures.</li>
 *   <li><strong>v2</strong> (PR 3) — adds {@link Capability} and
 *       {@link Provenance} as nested JSON. Written by the new
 *       {@link #record(Instant, String, Capability, Provenance, String, String)
 *       7-arg overload}. Both versions coexist in the same file; the read
 *       path dispatches per-entry.</li>
 * </ul>
 *
 * <h3>Why two ObjectMappers</h3>
 *
 * The on-disk JSON ({@link #mapper}) is left in record-declaration order
 * because that's what humans expect when grepping. The HMAC payload
 * ({@link #canonicalMapper}) sorts properties alphabetically so two
 * semantically equal capabilities always produce the same signed bytes —
 * without that, a record could fail to verify after a JVM upgrade or
 * Jackson minor version bump that reordered record component output.
 */
public final class CapabilityAuditLog {

    private static final Logger log = LoggerFactory.getLogger(CapabilityAuditLog.class);
    private static final String AUDIT_FILE = "capability-audit.jsonl";
    private static final String KEY_FILE = "audit.key";

    private final Path auditFile;
    private final AuditLogSigner signer;
    private final ObjectMapper mapper;
    private final ObjectMapper canonicalMapper;
    private final ReentrantLock writeLock = new ReentrantLock();

    /**
     * Creates an audit log rooted at {@code auditDir}. The directory and key
     * file are materialised up front — {@code auditDir} is created if missing
     * and the signing key is written to {@code audit.key} on first call
     * (POSIX 600 where supported). Only the JSONL file itself is lazy: it
     * appears on the first {@link #record} call.
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
        this.mapper = buildMapper(false);
        this.canonicalMapper = buildMapper(true);
    }

    private static ObjectMapper buildMapper(boolean canonical) {
        var m = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .registerModule(new Jdk8Module())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        if (canonical) {
            // Deterministic byte order — sorted property names give the HMAC
            // a stable input no matter how Jackson decides to emit fields by
            // default. Without this, an apparently identical entry could
            // sign to a different hash on another JVM/Jackson minor version
            // and fail verification on read.
            m = m.configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
        }
        return m;
    }

    /**
     * Signs and appends one v1 (flat) entry. Best-effort: an IO failure logs
     * a warning rather than throwing, because the calling permission check
     * has already produced its decision and the agent shouldn't be aborted
     * by a degraded log.
     *
     * <p>This overload is preserved unchanged from PR 2 so existing callers
     * keep compiling. Internally it constructs a v1
     * {@link CapabilityAuditEntry} (capability/provenance null,
     * {@code schemaVersion = 1}); v1 entries can still be read back
     * indefinitely since {@link #readVerified()} dispatches on schema
     * version per-entry.
     */
    public void record(
            Instant timestamp,
            String sessionId,
            String toolName,
            PermissionLevel level,
            String decisionKind,
            String reason) {
        var unsigned = new CapabilityAuditEntry(
                timestamp, sessionId, toolName, level, decisionKind, reason, "unsigned");
        var entry = unsigned.withSignature(signer.sign(unsigned.signablePayload(canonicalMapper)));
        appendEntry(entry);
    }

    /**
     * Signs and appends one v2 (structured) entry. {@code allowlistKey} is
     * the originating tool's name (or whichever key the dispatcher used for
     * per-session blanket approvals); we record it as the entry's
     * {@code toolName} so legacy v1 query tools still group correctly.
     * {@code capability} and {@code provenance} carry the typed payload.
     *
     * <p>Best-effort like the v1 overload: IO errors log and return.
     */
    public void record(
            Instant timestamp,
            String allowlistKey,
            Capability capability,
            Provenance provenance,
            String decisionKind,
            String reason) {
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(provenance, "provenance");
        var unsigned = CapabilityAuditEntry.v2(
                timestamp, allowlistKey, capability, provenance, decisionKind, reason, "unsigned");
        var entry = unsigned.withSignature(signer.sign(unsigned.signablePayload(canonicalMapper)));
        appendEntry(entry);
    }

    /**
     * Returns all entries from the log whose signature verifies under the
     * current key. Tampered, malformed, or wrong-key entries are skipped
     * with a warning — they don't fail the read. Order matches disk order
     * (append order = decision order).
     *
     * <p>Mixed-version files are supported: each line is parsed and verified
     * individually against its own schema. v1 lines (no
     * {@code schemaVersion} field) parse via the legacy field set; v2 lines
     * parse the full record including {@code capability} and
     * {@code provenance}.
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
                    entry = parseEntry(line);
                } catch (IOException parseError) {
                    log.warn("Audit log {} line {} is malformed; dropping. cause={}",
                            auditFile, lineNo, parseError.getMessage());
                    dropped++;
                    continue;
                }
                if (!signer.verify(entry.signablePayload(canonicalMapper), entry.signature())) {
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

    /**
     * Per-line dispatch. Reads the JSON, determines schema, builds the right
     * record. v1 entries lack {@code capability}, {@code provenance}, and
     * {@code schemaVersion}; we construct them via the 7-arg constructor so
     * the canonical-constructor's {@code schemaVersion} validation doesn't
     * reject the {@code 0} default that Jackson would otherwise produce.
     */
    private CapabilityAuditEntry parseEntry(String line) throws IOException {
        JsonNode node = mapper.readTree(line);
        int schemaVersion = CapabilityAuditEntry.schemaVersionOf(node);
        if (schemaVersion == CapabilityAuditEntry.SCHEMA_V1) {
            return new CapabilityAuditEntry(
                    Instant.parse(requireText(node, "timestamp")),
                    nullableText(node, "sessionId"),
                    requireText(node, "toolName"),
                    PermissionLevel.valueOf(requireText(node, "level")),
                    requireText(node, "decisionKind"),
                    nullableText(node, "reason"),
                    requireText(node, "signature"));
        }
        // v2: full record. Jackson maps capability/provenance via the
        // @JsonTypeInfo annotations on the sealed types.
        return mapper.treeToValue(node, CapabilityAuditEntry.class);
    }

    private static String requireText(JsonNode node, String field) throws IOException {
        var n = node.get(field);
        if (n == null || n.isNull()) {
            throw new IOException("missing required field: " + field);
        }
        return n.asText();
    }

    private static String nullableText(JsonNode node, String field) {
        var n = node.get(field);
        return (n == null || n.isNull()) ? null : n.asText();
    }

    /** Path to the audit file (may not exist yet if no entries written). */
    public Path auditFile() {
        return auditFile;
    }

    private void appendEntry(CapabilityAuditEntry entry) {
        writeLock.lock();
        try {
            // Parent dir was created in factory but tolerate manual deletion
            // between create() and first record() — a missing parent
            // directory should not crash the daemon.
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

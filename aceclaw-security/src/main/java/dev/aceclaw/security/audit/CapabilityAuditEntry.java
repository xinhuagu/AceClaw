package dev.aceclaw.security.audit;

import dev.aceclaw.security.PermissionLevel;

import java.time.Instant;
import java.util.Objects;

/**
 * One signed record in the capability audit log (#465 Layer 8 v1).
 *
 * <p>Today every {@link dev.aceclaw.security.PermissionManager#check} call
 * produces an entry. When the runtime-governance epic later introduces
 * {@code CapabilityRequest}, those richer requests will produce richer
 * entries — but this v1 schema covers the single most-asked-for thing:
 * a tamper-evident record of every permission decision the daemon made.
 *
 * <h3>Schema</h3>
 *
 * <ul>
 *   <li>{@code timestamp} — wall-clock instant of the decision (ISO-8601
 *       on the wire).</li>
 *   <li>{@code sessionId} — owning session, or {@code null} for daemon-
 *       internal checks that don't belong to any session.</li>
 *   <li>{@code toolName} — the tool the request was for.</li>
 *   <li>{@code level} — the request's {@link PermissionLevel} (READ / WRITE
 *       / EXECUTE / DANGEROUS).</li>
 *   <li>{@code decisionKind} — the resulting decision flattened to a
 *       string ({@code "APPROVED"} / {@code "DENIED"} /
 *       {@code "NEEDS_APPROVAL"}). Strings instead of the sealed type
 *       so the on-disk format isn't tied to the JVM type identity.</li>
 *   <li>{@code reason} — denial / approval reason, or {@code null}.</li>
 *   <li>{@code signature} — HMAC-SHA256 hex of every other field,
 *       computed via {@link AuditLogSigner}. Verified on read; an entry
 *       with a bad signature has been tampered with (or written by a
 *       different installation's key) and gets dropped from query
 *       results.</li>
 * </ul>
 *
 * <p>The signed payload is assembled by {@link #signablePayload()} —
 * the same implementation must be used by signer and verifier. Field
 * order matters for HMAC reproducibility; see that method's doc.
 */
public record CapabilityAuditEntry(
        Instant timestamp,
        String sessionId,
        String toolName,
        PermissionLevel level,
        String decisionKind,
        String reason,
        String signature) {

    public CapabilityAuditEntry {
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(decisionKind, "decisionKind");
        Objects.requireNonNull(signature, "signature");
        // sessionId and reason intentionally nullable — captured above.
    }

    /**
     * Returns this entry with {@code signature} replaced. Used by the
     * signer to populate the signature after building the unsigned
     * shape of the entry.
     */
    public CapabilityAuditEntry withSignature(String newSignature) {
        return new CapabilityAuditEntry(
                timestamp, sessionId, toolName, level, decisionKind, reason, newSignature);
    }

    /**
     * Returns the canonical string used as the HMAC input. Field order
     * is fixed and tab-separated to keep the encoding unambiguous;
     * any rearrangement here MUST be matched on the verifier side.
     *
     * <p>Null fields are encoded as the literal string {@code "null"}
     * so a field that flips between null and present produces a
     * different signature.
     */
    public String signablePayload() {
        return String.join("\t",
                timestamp.toString(),
                sessionId == null ? "null" : sessionId,
                toolName,
                level.name(),
                decisionKind,
                reason == null ? "null" : reason);
    }
}

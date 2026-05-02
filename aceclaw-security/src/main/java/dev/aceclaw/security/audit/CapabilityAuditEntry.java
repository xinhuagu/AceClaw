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
     * Returns this entry with {@code signature} replaced. Package-
     * private on purpose — only {@link CapabilityAuditLog} should use
     * it, as part of the build-then-sign flow. Exposing this publicly
     * would let callers craft signed entries off-the-side, bypassing
     * the audit log's lock and write path.
     */
    CapabilityAuditEntry withSignature(String newSignature) {
        return new CapabilityAuditEntry(
                timestamp, sessionId, toolName, level, decisionKind, reason, newSignature);
    }

    /**
     * Returns the canonical string used as the HMAC input. Encoding
     * is length-prefixed so that no choice of nullable / variable-width
     * field values can collide:
     *
     * <pre>
     *   nullable string s → "-" if null, else "&lt;codeUnitLength&gt;:&lt;s&gt;"
     *   non-null fields   → ":&lt;value&gt;" (length omitted, fixed shape)
     *   joined with the literal pipe '|' character
     * </pre>
     *
     * <p>The earlier scheme used tab-joined raw values with the
     * literal string {@code "null"} for absent fields, which let
     * {@code sessionId = null} collide with {@code sessionId = "null"},
     * and let a tab inside a {@code reason} collide with the
     * separator. The length prefix on each nullable field makes both
     * collision classes impossible: an attacker can no longer forge
     * one entry's signature into another.
     *
     * <p>Field order is fixed; any rearrangement here MUST be matched
     * on the verifier side.
     */
    public String signablePayload() {
        return String.join("|",
                ":" + timestamp.toString(),
                encodeNullable(sessionId),
                encodeLen(toolName),
                ":" + level.name(),
                ":" + decisionKind,
                encodeNullable(reason));
    }

    private static String encodeNullable(String s) {
        return s == null ? "-" : encodeLen(s);
    }

    private static String encodeLen(String s) {
        // Length is char-count of the canonical string; HMAC then
        // operates on the UTF-8 bytes of the whole joined payload,
        // so any unicode in the value is folded in unchanged.
        return s.length() + ":" + s;
    }
}

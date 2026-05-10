package dev.aceclaw.security.audit;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.security.Capability;
import dev.aceclaw.security.PermissionLevel;
import dev.aceclaw.security.Provenance;

import java.time.Instant;
import java.util.Objects;

/**
 * One signed record in the capability audit log (#465 Layer 8).
 *
 * <h3>Schema versions</h3>
 *
 * <ul>
 *   <li><strong>v1</strong> — flat {@code (toolName, level)} only. Written
 *       by every PR-2-era {@code PermissionManager.check} call and by the
 *       legacy {@code check(PermissionRequest, ...)} shim. Still readable
 *       indefinitely so existing logs survive the schema bump.</li>
 *   <li><strong>v2</strong> — adds the structured {@link Capability} variant
 *       and the originating {@link Provenance}. Written by
 *       {@code PermissionManager.check(Capability, Provenance, ...)} when
 *       an audit log is attached. Carries the typed payload PolicyEngine
 *       and the dashboard timeline want; the legacy {@code (toolName, level)}
 *       fields are still populated as a denormalised lookup so v1-era query
 *       tooling that filters by tool name keeps working.</li>
 * </ul>
 *
 * <h3>Why both flat fields AND structured payload</h3>
 *
 * Migration safety. A query tool that filters by {@code toolName == "bash"}
 * (the v1 contract) keeps working against v2 entries because we still
 * populate that field — using the originating tool's name (e.g.
 * {@code "write_file"}) rather than a synthesised label. The
 * {@code capability} field gives the structured detail callers can opt into
 * when they're ready to switch.
 *
 * <h3>HMAC payload versioning</h3>
 *
 * The signed payload is built by {@link #signablePayload(ObjectMapper)} and
 * branches on {@code schemaVersion}: v1 entries verify under the original
 * {@code |}-joined string used since the audit log existed; v2 appends the
 * canonicalised JSON of {@code capability} and {@code provenance}. Because
 * each entry carries its own {@code schemaVersion}, the same key signs and
 * verifies entries from both eras without rotation.
 */
public record CapabilityAuditEntry(
        Instant timestamp,
        String sessionId,
        String toolName,
        PermissionLevel level,
        String decisionKind,
        String reason,
        String signature,
        // v2 additions: nullable on v1-era entries.
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Capability capability,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Provenance provenance,
        // schemaVersion: 1 for legacy entries (capability/provenance null);
        // 2 for entries written via the structured-record overload.
        int schemaVersion) {

    /** Schema version constant for v1 (flat) entries. */
    public static final int SCHEMA_V1 = 1;

    /** Schema version constant for v2 (structured) entries. */
    public static final int SCHEMA_V2 = 2;

    public CapabilityAuditEntry {
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(decisionKind, "decisionKind");
        Objects.requireNonNull(signature, "signature");
        // sessionId, reason, capability, provenance intentionally nullable.
        if (schemaVersion != SCHEMA_V1 && schemaVersion != SCHEMA_V2) {
            throw new IllegalArgumentException(
                    "schemaVersion must be " + SCHEMA_V1 + " or " + SCHEMA_V2 + "; got " + schemaVersion);
        }
        if (schemaVersion == SCHEMA_V2) {
            Objects.requireNonNull(capability,
                    "v2 entries require a non-null capability");
            Objects.requireNonNull(provenance,
                    "v2 entries require a non-null provenance");
        }
        // v1 entries SHOULD have capability/provenance null. We don't enforce
        // (defensive: a future migration may want to upgrade in place), but
        // signablePayload() ignores those fields for v1, so signing/verify is
        // unambiguous.
    }

    /**
     * Backward-compatible 7-arg constructor for v1 entries. Defaults
     * {@code schemaVersion} to {@link #SCHEMA_V1} and leaves
     * {@code capability}/{@code provenance} null. Used by the legacy
     * {@link CapabilityAuditLog#record(Instant, String, String, PermissionLevel,
     * String, String)} entry point and by every existing test that pre-dates
     * the v2 fields.
     */
    public CapabilityAuditEntry(
            Instant timestamp,
            String sessionId,
            String toolName,
            PermissionLevel level,
            String decisionKind,
            String reason,
            String signature) {
        this(timestamp, sessionId, toolName, level, decisionKind, reason, signature,
                null, null, SCHEMA_V1);
    }

    /**
     * Convenience factory for a v2 entry — fills the v1 denormalised fields
     * from the {@link Capability} so legacy query tools still see populated
     * {@code toolName}/{@code level}. {@code allowlistKey} is what the
     * dispatcher passed to {@link dev.aceclaw.security.PermissionManager} as
     * the per-session approval key (typically the originating tool's name);
     * we record it as {@code toolName} so existing "always allow X" reports
     * group consistently with v1.
     */
    public static CapabilityAuditEntry v2(
            Instant timestamp,
            String allowlistKey,
            Capability capability,
            Provenance provenance,
            String decisionKind,
            String reason,
            String signature) {
        Objects.requireNonNull(allowlistKey, "allowlistKey");
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(provenance, "provenance");
        String sessionId = provenance.sessionId().map(s -> s.value()).orElse(null);
        return new CapabilityAuditEntry(
                timestamp,
                sessionId,
                allowlistKey,
                capability.risk(),
                decisionKind,
                reason,
                signature,
                capability,
                provenance,
                SCHEMA_V2);
    }

    /**
     * Returns this entry with {@code signature} replaced. Package-private —
     * only {@link CapabilityAuditLog} uses it as part of build-then-sign.
     * Public exposure would let callers craft signed entries off-the-side
     * and bypass the log's lock and write path.
     */
    CapabilityAuditEntry withSignature(String newSignature) {
        return new CapabilityAuditEntry(
                timestamp, sessionId, toolName, level, decisionKind, reason, newSignature,
                capability, provenance, schemaVersion);
    }

    /**
     * Returns the canonical string used as the HMAC input.
     *
     * <p>v1 layout (preserved bit-for-bit so old entries verify):
     * <pre>
     *   :&lt;timestamp&gt;|&lt;sessionId|len-prefixed or "-"&gt;|&lt;toolName|len-prefixed&gt;
     *   |:&lt;level&gt;|:&lt;decisionKind&gt;|&lt;reason|len-prefixed or "-"&gt;
     * </pre>
     *
     * <p>v2 layout (v1 prefix + canonical capability + canonical provenance):
     * <pre>
     *   &lt;v1 layout&gt;|:v2|&lt;capabilityJson|len-prefixed&gt;|&lt;provenanceJson|len-prefixed&gt;
     * </pre>
     *
     * <p>The {@code :v2} marker is the boundary between v1's flat fields and
     * the new structured tail. It serves two purposes: (a) signature
     * deterministic boundary if a future v3 needs to slot in more fields,
     * and (b) prevents a forged v1 entry from masquerading as v2 by
     * appending crafted bytes — the marker would have to land at the
     * v2 boundary <em>and</em> the appended payload's length-prefix would
     * have to verify, which can't be done without the HMAC key.
     *
     * <p>The {@code canonicalMapper} should be configured to sort properties
     * alphabetically; {@link CapabilityAuditLog} provides one. Sorted JSON
     * is what makes the v2 payload deterministic regardless of variant
     * field order — without it, two records with semantically equal
     * capabilities could disagree on byte order and one would fail to
     * verify.
     */
    /**
     * No-arg overload usable only for {@link #SCHEMA_V1} entries — those
     * have no {@link Capability} or {@link Provenance} to canonicalise, so
     * no mapper is needed. v2 entries throw {@link IllegalStateException}
     * here; callers that handle v2 must use
     * {@link #signablePayload(ObjectMapper)}. Kept around so the existing
     * v1-only tests (collision sentinels) and any v1-only consumers don't
     * have to thread a canonical mapper through.
     */
    public String signablePayload() {
        if (schemaVersion != SCHEMA_V1) {
            throw new IllegalStateException(
                    "signablePayload() with no mapper is only valid for v1 entries; "
                            + "v" + schemaVersion + " entries must use signablePayload(ObjectMapper)");
        }
        return signablePayload(null);
    }

    public String signablePayload(ObjectMapper canonicalMapper) {
        String base = String.join("|",
                ":" + timestamp.toString(),
                encodeNullable(sessionId),
                encodeLen(toolName),
                ":" + level.name(),
                ":" + decisionKind,
                encodeNullable(reason));
        if (schemaVersion == SCHEMA_V1) {
            return base;
        }
        // v2: capability + provenance both required (constructor enforced).
        try {
            String capJson = canonicalMapper.writeValueAsString(capability);
            String provJson = canonicalMapper.writeValueAsString(provenance);
            return base + "|:v2|" + encodeLen(capJson) + "|" + encodeLen(provJson);
        } catch (Exception e) {
            // Serialisation should not fail for in-memory records, but if it
            // does we cannot produce a deterministic payload — surface
            // explicitly rather than write an unsigned (or worse,
            // non-reproducibly-signed) entry.
            throw new IllegalStateException(
                    "Failed to canonicalise capability/provenance for HMAC payload: "
                            + e.getMessage(), e);
        }
    }

    private static String encodeNullable(String s) {
        return s == null ? "-" : encodeLen(s);
    }

    private static String encodeLen(String s) {
        return s.length() + ":" + s;
    }

    /**
     * Reconstructs schema version from a parsed JSON node — used by the
     * read path to detect v1 entries written before {@link #schemaVersion}
     * existed as a field. v1 entries on disk lack the {@code schemaVersion}
     * key entirely; the read path treats absence as {@link #SCHEMA_V1}.
     */
    static int schemaVersionOf(JsonNode node) {
        if (node == null) return SCHEMA_V1;
        var sv = node.get("schemaVersion");
        if (sv == null || sv.isNull()) return SCHEMA_V1;
        return sv.asInt(SCHEMA_V1);
    }
}

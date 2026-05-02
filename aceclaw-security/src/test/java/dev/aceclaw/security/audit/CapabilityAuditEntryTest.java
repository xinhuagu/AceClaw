package dev.aceclaw.security.audit;

import dev.aceclaw.security.PermissionLevel;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link CapabilityAuditEntry#signablePayload()} — the
 * canonical HMAC input. Sentinel coverage for the encoding-collision
 * class that the v1 PR's tab-joined scheme allowed (Codex P2 finding):
 *
 * <ul>
 *   <li>null vs. literal {@code "null"} for the same field MUST sign
 *       to different payloads;</li>
 *   <li>a separator-character ({@code |}) inside a user-supplied field
 *       MUST NOT bleed into the next field;</li>
 *   <li>shifting characters between two adjacent variable-length
 *       fields MUST produce a distinct payload (length-prefix
 *       guarantees this).</li>
 * </ul>
 *
 * <p>If any of these regress, an attacker who controls user-supplied
 * fields (sessionId / toolName / reason) can craft two semantically
 * distinct entries that share an HMAC — exactly the tamper-evidence
 * break the audit log exists to prevent.
 */
final class CapabilityAuditEntryTest {

    private static final Instant FIXED_TS = Instant.parse("2026-05-01T00:00:00Z");

    private static CapabilityAuditEntry entry(String sessionId, String toolName, String reason) {
        return new CapabilityAuditEntry(
                FIXED_TS, sessionId, toolName, PermissionLevel.EXECUTE,
                "APPROVED", reason, "unsigned");
    }

    @Test
    void nullSessionIdDoesNotCollideWithLiteralNullString() {
        // The pre-fix encoding wrote both as the bare string "null"
        // separated by tabs, so an attacker editing JSON to swap
        // sessionId between (real) null and the string "null" would
        // not invalidate the signature. Length-prefix encoding with
        // a dedicated null marker ("-") makes them distinguishable.
        var withRealNull = entry(null, "bash", null);
        var withStringNull = entry("null", "bash", "null");

        assertThat(withRealNull.signablePayload())
                .isNotEqualTo(withStringNull.signablePayload());
    }

    @Test
    void separatorCharInUserFieldDoesNotCollideWithFieldBoundary() {
        // sessionId="A|B" must not be signature-equivalent to two
        // adjacent fields that happen to read "A" and "B". Without
        // length prefixes, '|' inside a value would have aliased
        // with the field separator.
        var withPipeInside = entry("A|B", "bash", null);
        var withDifferentSplit = entry("A", "B", null);

        assertThat(withPipeInside.signablePayload())
                .isNotEqualTo(withDifferentSplit.signablePayload());
    }

    @Test
    void shiftingCharactersBetweenAdjacentNullableFieldsProducesDifferentPayload() {
        // sessionId="AB", reason="" vs sessionId="A", reason="B".
        // With raw concatenation these would alias; with length
        // prefixes (2:AB vs 1:A...1:B) they don't.
        var packedLeft = entry("AB", "bash", "");
        var packedRight = entry("A", "bash", "B");

        assertThat(packedLeft.signablePayload())
                .isNotEqualTo(packedRight.signablePayload());
    }

    @Test
    void identicalEntriesProduceIdenticalPayload() {
        // Sanity baseline — without this the other tests prove
        // nothing (everything could be different by accident).
        var a = entry("sess-1", "bash", "blocked");
        var b = entry("sess-1", "bash", "blocked");

        assertThat(a.signablePayload()).isEqualTo(b.signablePayload());
    }
}

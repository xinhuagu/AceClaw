package dev.aceclaw.security.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link AuditLogSigner} — HMAC-SHA256 signing for capability
 * audit entries (#465 Layer 8 v1).
 *
 * <p>Mirror coverage of the existing {@code MemorySignerTest} pattern.
 * The audit log's tamper-evidence story rests entirely on this class:
 * if signature roundtrip breaks, every entry past that release is
 * indistinguishable from a forgery, so these are sentinel tests.
 */
final class AuditLogSignerTest {

    @Test
    void signAndVerifyRoundtrip() {
        var signer = new AuditLogSigner("test-key-bytes".getBytes());
        String payload = "tool=bash\tlevel=EXECUTE\tdecision=APPROVED";
        String sig = signer.sign(payload);
        assertThat(signer.verify(payload, sig)).isTrue();
    }

    @Test
    void verifyRejectsTamperedPayload() {
        var signer = new AuditLogSigner("test-key-bytes".getBytes());
        String original = "tool=bash\tlevel=EXECUTE\tdecision=DENIED";
        String tampered = "tool=bash\tlevel=EXECUTE\tdecision=APPROVED";
        String sig = signer.sign(original);
        assertThat(signer.verify(tampered, sig))
                .as("flipping DENIED→APPROVED must invalidate the signature")
                .isFalse();
    }

    @Test
    void verifyRejectsWrongSignature() {
        var signer = new AuditLogSigner("test-key-bytes".getBytes());
        String payload = "anything";
        assertThat(signer.verify(payload, "deadbeef")).isFalse();
    }

    @Test
    void verifyHandlesNullSignatureWithoutThrowing() {
        var signer = new AuditLogSigner("test-key-bytes".getBytes());
        // A malformed line that's missing the signature field
        // shouldn't crash the verifier — readVerified() relies on
        // verify() being null-safe to drop those entries cleanly.
        assertThat(signer.verify("payload", null)).isFalse();
    }

    @Test
    void differentKeysProduceDifferentSignatures() {
        var signerA = new AuditLogSigner("key-A-bytes".getBytes());
        var signerB = new AuditLogSigner("key-B-bytes".getBytes());
        String payload = "shared-payload";
        // Each installation has its own key, so an entry signed by
        // installation A must not verify under installation B's
        // signer — confirming key isolation.
        assertThat(signerA.sign(payload)).isNotEqualTo(signerB.sign(payload));
        assertThat(signerB.verify(payload, signerA.sign(payload))).isFalse();
    }

    @Test
    void constructorRejectsEmptySecret() {
        assertThatThrownBy(() -> new AuditLogSigner(new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditLogSigner(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void loadOrCreateGeneratesFreshKeyWhenMissing(@TempDir Path tmp) throws IOException {
        Path keyFile = tmp.resolve("audit.key");
        assertThat(Files.exists(keyFile)).isFalse();

        AuditLogSigner signer = AuditLogSigner.loadOrCreate(keyFile);

        assertThat(Files.exists(keyFile)).isTrue();
        // 32 bytes = HMAC-SHA256's native key size; we'd rather trip
        // a test than silently downgrade the key strength.
        assertThat(Files.size(keyFile)).isEqualTo(32);
        // Smoke check the returned signer is usable.
        String payload = "hello";
        assertThat(signer.verify(payload, signer.sign(payload))).isTrue();
    }

    @Test
    void loadOrCreateReusesExistingKey(@TempDir Path tmp) throws IOException {
        Path keyFile = tmp.resolve("audit.key");
        AuditLogSigner first = AuditLogSigner.loadOrCreate(keyFile);
        String firstSig = first.sign("payload");

        // Second load must reuse the on-disk key (otherwise a daemon
        // restart would invalidate every prior audit entry).
        AuditLogSigner second = AuditLogSigner.loadOrCreate(keyFile);
        assertThat(second.sign("payload")).isEqualTo(firstSig);
        assertThat(second.verify("payload", firstSig)).isTrue();
    }

    @Test
    void loadOrCreateSetsPosixPermissionsOnFreshKey(@TempDir Path tmp) throws IOException {
        Path keyFile = tmp.resolve("audit.key");
        AuditLogSigner.loadOrCreate(keyFile);

        // The key contains the only thing protecting the audit log
        // from forgery — POSIX 600 keeps it out of reach of other
        // local users on shared workstations.
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(keyFile);
            assertThat(perms)
                    .containsExactlyInAnyOrder(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE);
        } catch (UnsupportedOperationException nonPosix) {
            // Windows test runner — the implementation logs a warning
            // instead of failing; nothing to assert on that path.
        }
    }

    @Test
    void loadOrCreateRejectsZeroByteKeyFile(@TempDir Path tmp) throws IOException {
        // A truncated-to-zero key file would silently work with an
        // empty SecretKeySpec under some JDK versions and produce
        // garbage signatures. Better to fail loudly so the operator
        // knows the audit chain is broken.
        Path keyFile = tmp.resolve("audit.key");
        Files.createFile(keyFile);

        assertThatThrownBy(() -> AuditLogSigner.loadOrCreate(keyFile))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("empty");
    }
}

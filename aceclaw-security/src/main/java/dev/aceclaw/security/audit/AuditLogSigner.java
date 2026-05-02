package dev.aceclaw.security.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Set;

/**
 * HMAC-SHA256 signer for capability audit entries (#465 Layer 8 v1).
 *
 * <p>Mirrors {@code MemorySigner}'s pattern but with its own per-
 * installation key file at {@code ~/.aceclaw/audit/audit.key}. Two
 * independent keys (one for memory, one for audit) means a leak of
 * either doesn't compromise the other, and they can be rotated on
 * different cadences. POSIX 600 on the key file by default; on
 * non-POSIX filesystems we fall back to whatever default the JVM
 * gives us and log a warning.
 *
 * <p>Verification uses constant-time comparison
 * ({@link MessageDigest#isEqual}) to prevent timing side-channels —
 * same protection {@code MemorySigner} provides.
 */
public final class AuditLogSigner {

    private static final Logger log = LoggerFactory.getLogger(AuditLogSigner.class);
    private static final String ALGORITHM = "HmacSHA256";
    /** 32 bytes = 256 bits, matches HMAC-SHA256 native key size. */
    private static final int KEY_BYTES = 32;

    private final SecretKeySpec keySpec;

    public AuditLogSigner(byte[] secret) {
        if (secret == null || secret.length == 0) {
            throw new IllegalArgumentException("secret must not be null or empty");
        }
        this.keySpec = new SecretKeySpec(secret, ALGORITHM);
    }

    /**
     * Loads the signing key from {@code keyPath}, generating + persisting
     * a fresh 32-byte random key if the file doesn't exist. The parent
     * directory is created if needed; the key file is set to POSIX 600
     * on filesystems that support it.
     */
    public static AuditLogSigner loadOrCreate(Path keyPath) throws IOException {
        if (Files.exists(keyPath)) {
            byte[] secret = Files.readAllBytes(keyPath);
            if (secret.length == 0) {
                throw new IOException("Audit key file is empty: " + keyPath);
            }
            return new AuditLogSigner(secret);
        }
        Files.createDirectories(keyPath.getParent());
        byte[] freshKey = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(freshKey);
        Files.write(keyPath, freshKey);
        try {
            Files.setPosixFilePermissions(keyPath, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException e) {
            // Non-POSIX filesystem (Windows). Log so operators know the
            // key file inherits whatever ACL the parent directory had.
            log.warn("Audit key file at {} is not POSIX; inherited filesystem ACL applies.", keyPath);
        }
        return new AuditLogSigner(freshKey);
    }

    /** Computes the HMAC-SHA256 hex digest for {@code payload}. */
    public String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(keySpec);
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("HMAC-SHA256 signing failed", e);
        }
    }

    /**
     * Constant-time HMAC verification. {@code expectedHmac} is treated
     * as the suspect value; {@code payload} is rehashed under our key
     * and the two are compared byte-by-byte without short-circuiting.
     */
    public boolean verify(String payload, String expectedHmac) {
        if (expectedHmac == null) return false;
        String actual = sign(payload);
        return MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.UTF_8),
                expectedHmac.getBytes(StandardCharsets.UTF_8));
    }
}

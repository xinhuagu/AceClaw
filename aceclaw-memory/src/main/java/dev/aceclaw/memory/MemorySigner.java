package dev.aceclaw.memory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * HMAC-SHA256 signer for memory entries.
 *
 * <p>Each memory entry is signed with a per-installation secret key.
 * On load, the signature is verified to detect tampering — if a memory
 * file is hand-edited or corrupted, the bad entries are skipped.
 */
public final class MemorySigner {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec keySpec;

    /**
     * Creates a signer with the given secret key bytes.
     *
     * @param secret the secret key (typically loaded from ~/.aceclaw/memory.key)
     */
    public MemorySigner(byte[] secret) {
        this.keySpec = new SecretKeySpec(secret, ALGORITHM);
    }

    /**
     * Computes the HMAC-SHA256 hex digest for the given payload.
     */
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
     * Verifies the HMAC-SHA256 signature against the expected digest.
     */
    public boolean verify(String payload, String expectedHmac) {
        String actual = sign(payload);
        return actual.equals(expectedHmac);
    }
}

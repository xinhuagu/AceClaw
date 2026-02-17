package dev.aceclaw.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;

class MemorySignerTest {

    private MemorySigner signer;

    @BeforeEach
    void setUp() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        signer = new MemorySigner(key);
    }

    @Test
    void signAndVerify() {
        String payload = "test-id|MISTAKE|always use utf-8|java,encoding|2025-01-01T00:00:00Z|session:abc";
        String hmac = signer.sign(payload);

        assertThat(hmac).isNotNull().isNotEmpty();
        assertThat(signer.verify(payload, hmac)).isTrue();
    }

    @Test
    void verifyRejectsTamperedPayload() {
        String payload = "test-id|MISTAKE|original content|java|2025-01-01T00:00:00Z|session:abc";
        String hmac = signer.sign(payload);

        String tampered = "test-id|MISTAKE|modified content|java|2025-01-01T00:00:00Z|session:abc";
        assertThat(signer.verify(tampered, hmac)).isFalse();
    }

    @Test
    void verifyRejectsTamperedHmac() {
        String payload = "test-id|MISTAKE|content|java|2025-01-01T00:00:00Z|session:abc";
        String hmac = signer.sign(payload);

        // Flip one character in the HMAC
        char[] chars = hmac.toCharArray();
        chars[0] = chars[0] == 'a' ? 'b' : 'a';
        String tamperedHmac = new String(chars);

        assertThat(signer.verify(payload, tamperedHmac)).isFalse();
    }

    @Test
    void differentKeysProduceDifferentSignatures() {
        byte[] key2 = new byte[32];
        new SecureRandom().nextBytes(key2);
        var signer2 = new MemorySigner(key2);

        String payload = "test-id|PATTERN|some pattern|java|2025-01-01T00:00:00Z|session:xyz";
        String hmac1 = signer.sign(payload);
        String hmac2 = signer2.sign(payload);

        assertThat(hmac1).isNotEqualTo(hmac2);
    }

    @Test
    void signProducesDeterministicOutput() {
        String payload = "deterministic-test|STRATEGY|approach|tag|2025-01-01T00:00:00Z|source";
        String hmac1 = signer.sign(payload);
        String hmac2 = signer.sign(payload);

        assertThat(hmac1).isEqualTo(hmac2);
    }
}

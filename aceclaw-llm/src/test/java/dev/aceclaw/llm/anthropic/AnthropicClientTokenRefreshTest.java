package dev.aceclaw.llm.anthropic;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for OAuth token refresh logic in AnthropicClient.
 * Uses the package-private constructor with pluggable credential supplier.
 */
class AnthropicClientTokenRefreshTest {

    private static final String OAUTH_TOKEN = "sk-ant-oat01-test-access";
    private static final String API_KEY = "sk-ant-api03-test-key";
    private static final String REFRESH_TOKEN = "sk-ant-ort01-test-refresh";
    private static final String BASE_URL = "https://api.anthropic.com";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    // -- Constructor tests --

    @Test
    void constructor_oauthToken_detectsOAuthMode() {
        var client = createClient(OAUTH_TOKEN, REFRESH_TOKEN, () -> null);
        assertThat(client.provider()).isEqualTo("anthropic");
    }

    @Test
    void constructor_blankAccessToken_throws() {
        assertThatThrownBy(() -> new AnthropicClient(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_nullAccessToken_throws() {
        assertThatThrownBy(() -> new AnthropicClient(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -- ensureRefreshToken: with existing refresh token --

    @Test
    void ensureRefreshToken_withExistingRefreshToken_stillChecksKeychain() {
        var callCount = new AtomicInteger();
        var client = createClient(OAUTH_TOKEN, REFRESH_TOKEN, () -> {
            callCount.incrementAndGet();
            return null; // Keychain returns nothing
        });

        boolean result = client.ensureRefreshToken();

        assertThat(result).isTrue();
        assertThat(callCount.get()).isEqualTo(1); // Keychain WAS consulted (no early return)
    }

    @Test
    void ensureRefreshToken_keychainHasFresherTokens_updatesInMemory() {
        var freshCred = new KeychainCredentialReader.Credential(
                "sk-ant-oat01-fresh-access", "sk-ant-ort01-fresh-refresh",
                System.currentTimeMillis() + 3600_000L); // 1h from now

        var client = createClient(OAUTH_TOKEN, REFRESH_TOKEN, () -> freshCred);

        boolean result = client.ensureRefreshToken();

        assertThat(result).isTrue();
        assertThat(client.accessTokenForTest()).isEqualTo("sk-ant-oat01-fresh-access");
        assertThat(client.refreshTokenForTest()).isEqualTo("sk-ant-ort01-fresh-refresh");
    }

    // -- ensureRefreshToken: without refresh token (null) --

    @Test
    void ensureRefreshToken_nullRefreshToken_keychainHasBoth_loadsTokens() {
        var cred = new KeychainCredentialReader.Credential(
                "sk-ant-oat01-keychain-access", "sk-ant-ort01-keychain-refresh",
                System.currentTimeMillis() + 3600_000L);

        var client = createClient(OAUTH_TOKEN, null, () -> cred);

        boolean result = client.ensureRefreshToken();

        assertThat(result).isTrue();
        assertThat(client.accessTokenForTest()).isEqualTo("sk-ant-oat01-keychain-access");
        assertThat(client.refreshTokenForTest()).isEqualTo("sk-ant-ort01-keychain-refresh");
    }

    @Test
    void ensureRefreshToken_nullRefreshToken_keychainReturnsNull_returnsFalse() {
        var client = createClient(OAUTH_TOKEN, null, () -> null);

        boolean result = client.ensureRefreshToken();

        assertThat(result).isFalse();
        assertThat(client.refreshTokenForTest()).isNull();
    }

    // -- Partial token updates --

    @Test
    void ensureRefreshToken_keychainHasAccessOnly_noRefreshToken_updatesAccessOnly() {
        var cred = new KeychainCredentialReader.Credential(
                "sk-ant-oat01-new-access", null,
                System.currentTimeMillis() + 3600_000L);

        var client = createClient(OAUTH_TOKEN, null, () -> cred);

        boolean result = client.ensureRefreshToken();

        assertThat(result).isFalse(); // No refresh token available
        assertThat(client.accessTokenForTest()).isEqualTo("sk-ant-oat01-new-access"); // But access was updated
        assertThat(client.refreshTokenForTest()).isNull();
    }

    @Test
    void ensureRefreshToken_keychainHasRefreshOnly_expiredAccess_updatesRefreshOnly() {
        var cred = new KeychainCredentialReader.Credential(
                "sk-ant-oat01-expired", "sk-ant-ort01-good-refresh",
                1000L); // expired

        var client = createClient(OAUTH_TOKEN, null, () -> cred);

        boolean result = client.ensureRefreshToken();

        assertThat(result).isTrue(); // Refresh token available
        assertThat(client.accessTokenForTest()).isEqualTo(OAUTH_TOKEN); // NOT updated (expired)
        assertThat(client.refreshTokenForTest()).isEqualTo("sk-ant-ort01-good-refresh");
    }

    // -- Keychain failure --

    @Test
    void ensureRefreshToken_keychainThrows_returnsFalseGracefully() {
        var client = createClient(OAUTH_TOKEN, null, () -> {
            throw new RuntimeException("Keychain locked");
        });

        boolean result = client.ensureRefreshToken();

        assertThat(result).isFalse(); // No refresh token, exception caught gracefully
        assertThat(client.accessTokenForTest()).isEqualTo(OAUTH_TOKEN); // Unchanged
    }

    // -- Concurrent calls --

    @Test
    void ensureRefreshToken_concurrentCalls_noRaceCondition() throws Exception {
        var callCount = new AtomicInteger();
        var cred = new KeychainCredentialReader.Credential(
                "sk-ant-oat01-concurrent", "sk-ant-ort01-concurrent",
                System.currentTimeMillis() + 3600_000L);

        var client = createClient(OAUTH_TOKEN, null, () -> {
            callCount.incrementAndGet();
            return cred;
        });

        int threads = 8;
        var latch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    client.ensureRefreshToken();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        latch.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        // All threads should have called the supplier
        assertThat(callCount.get()).isEqualTo(threads);
        // Final state should be consistent (last write wins for volatile fields)
        assertThat(client.accessTokenForTest()).isEqualTo("sk-ant-oat01-concurrent");
        assertThat(client.refreshTokenForTest()).isEqualTo("sk-ant-ort01-concurrent");
    }

    // -- Called twice (no caching) --

    @Test
    void ensureRefreshToken_calledTwice_checksKeychainBothTimes() {
        var callCount = new AtomicInteger();
        var client = createClient(OAUTH_TOKEN, REFRESH_TOKEN, () -> {
            callCount.incrementAndGet();
            return null;
        });

        client.ensureRefreshToken();
        client.ensureRefreshToken();

        assertThat(callCount.get()).isEqualTo(2); // No caching — both calls checked Keychain
    }

    // -- Helper --

    private static AnthropicClient createClient(
            String accessToken, String refreshToken,
            java.util.function.Supplier<KeychainCredentialReader.Credential> credSupplier) {
        return new AnthropicClient(accessToken, refreshToken, BASE_URL, TIMEOUT, false, null, credSupplier);
    }
}

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
 * Tests for OAuth token refresh and 401 recovery logic in AnthropicClient.
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
    void constructor_blankAccessToken_throws() {
        assertThatThrownBy(() -> new AnthropicClient(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_nullAccessToken_throws() {
        assertThatThrownBy(() -> new AnthropicClient(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -- recoverCredentials: ACCESS_TOKEN_UPDATED --

    @Test
    void recoverCredentials_keychainHasFreshAccessToken_returnsAccessTokenUpdated() {
        var freshCred = new KeychainCredentialReader.Credential(
                "sk-ant-oat01-fresh", null,
                System.currentTimeMillis() + 3600_000L);

        var client = createClient(OAUTH_TOKEN, null, () -> freshCred);
        var result = client.recoverCredentials();

        assertThat(result).isEqualTo(AnthropicClient.CredentialRecovery.ACCESS_TOKEN_UPDATED);
        assertThat(client.accessTokenForTest()).isEqualTo("sk-ant-oat01-fresh");
    }

    @Test
    void recoverCredentials_keychainHasFreshAccessAndRefresh_returnsAccessTokenUpdated() {
        // Access token changed = ACCESS_TOKEN_UPDATED takes priority
        var cred = new KeychainCredentialReader.Credential(
                "sk-ant-oat01-new", "sk-ant-ort01-new",
                System.currentTimeMillis() + 3600_000L);

        var client = createClient(OAUTH_TOKEN, REFRESH_TOKEN, () -> cred);
        var result = client.recoverCredentials();

        assertThat(result).isEqualTo(AnthropicClient.CredentialRecovery.ACCESS_TOKEN_UPDATED);
        assertThat(client.accessTokenForTest()).isEqualTo("sk-ant-oat01-new");
        assertThat(client.refreshTokenForTest()).isEqualTo("sk-ant-ort01-new");
    }

    // -- recoverCredentials: REFRESH_AVAILABLE --

    @Test
    void recoverCredentials_sameAccessToken_hasRefreshToken_returnsRefreshAvailable() {
        // Keychain returns same access token but has refresh token
        var cred = new KeychainCredentialReader.Credential(
                OAUTH_TOKEN, "sk-ant-ort01-loaded",
                System.currentTimeMillis() + 3600_000L);

        var client = createClient(OAUTH_TOKEN, null, () -> cred);
        var result = client.recoverCredentials();

        assertThat(result).isEqualTo(AnthropicClient.CredentialRecovery.REFRESH_AVAILABLE);
        assertThat(client.refreshTokenForTest()).isEqualTo("sk-ant-ort01-loaded");
    }

    @Test
    void recoverCredentials_existingRefreshToken_keychainReturnsNull_returnsRefreshAvailable() {
        var client = createClient(OAUTH_TOKEN, REFRESH_TOKEN, () -> null);
        var result = client.recoverCredentials();

        assertThat(result).isEqualTo(AnthropicClient.CredentialRecovery.REFRESH_AVAILABLE);
    }

    // -- recoverCredentials: NO_RECOVERY --

    @Test
    void recoverCredentials_noRefreshToken_keychainReturnsNull_returnsNoRecovery() {
        var client = createClient(OAUTH_TOKEN, null, () -> null);
        var result = client.recoverCredentials();

        assertThat(result).isEqualTo(AnthropicClient.CredentialRecovery.NO_RECOVERY);
    }

    @Test
    void recoverCredentials_keychainHasExpiredAccessOnly_returnsNoRecovery() {
        var expiredCred = new KeychainCredentialReader.Credential(
                "sk-ant-oat01-expired", null, 1000L); // expired

        var client = createClient(OAUTH_TOKEN, null, () -> expiredCred);
        var result = client.recoverCredentials();

        assertThat(result).isEqualTo(AnthropicClient.CredentialRecovery.NO_RECOVERY);
        assertThat(client.accessTokenForTest()).isEqualTo(OAUTH_TOKEN); // NOT updated
    }

    // -- Keychain failure --

    @Test
    void recoverCredentials_keychainThrows_returnsNoRecoveryGracefully() {
        var client = createClient(OAUTH_TOKEN, null, () -> {
            throw new RuntimeException("Keychain locked");
        });
        var result = client.recoverCredentials();

        assertThat(result).isEqualTo(AnthropicClient.CredentialRecovery.NO_RECOVERY);
        assertThat(client.accessTokenForTest()).isEqualTo(OAUTH_TOKEN); // Unchanged
    }

    @Test
    void recoverCredentials_keychainThrows_withExistingRefreshToken_returnsRefreshAvailable() {
        var client = createClient(OAUTH_TOKEN, REFRESH_TOKEN, () -> {
            throw new RuntimeException("Keychain locked");
        });
        var result = client.recoverCredentials();

        assertThat(result).isEqualTo(AnthropicClient.CredentialRecovery.REFRESH_AVAILABLE);
    }

    // -- Partial updates --

    @Test
    void recoverCredentials_keychainHasRefreshOnly_expiredAccess_loadsRefreshKeepsAccess() {
        var cred = new KeychainCredentialReader.Credential(
                "sk-ant-oat01-expired", "sk-ant-ort01-good",
                1000L); // expired

        var client = createClient(OAUTH_TOKEN, null, () -> cred);
        var result = client.recoverCredentials();

        assertThat(result).isEqualTo(AnthropicClient.CredentialRecovery.REFRESH_AVAILABLE);
        assertThat(client.accessTokenForTest()).isEqualTo(OAUTH_TOKEN); // NOT updated (expired)
        assertThat(client.refreshTokenForTest()).isEqualTo("sk-ant-ort01-good");
    }

    // -- Concurrent calls --

    @Test
    void recoverCredentials_concurrentCalls_consistentState() throws Exception {
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
                    client.recoverCredentials();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        latch.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        // +1 for the constructor reading initial expiry from credential store
        assertThat(callCount.get()).isEqualTo(threads + 1);
        assertThat(client.accessTokenForTest()).isEqualTo("sk-ant-oat01-concurrent");
        assertThat(client.refreshTokenForTest()).isEqualTo("sk-ant-ort01-concurrent");
    }

    // -- No caching --

    @Test
    void recoverCredentials_calledTwice_checksKeychainBothTimes() {
        var callCount = new AtomicInteger();
        var client = createClient(OAUTH_TOKEN, REFRESH_TOKEN, () -> {
            callCount.incrementAndGet();
            return null;
        });

        client.recoverCredentials();
        client.recoverCredentials();

        // +1 for the constructor reading initial expiry from credential store
        assertThat(callCount.get()).isEqualTo(3);
    }

    // -- Helper --

    private static AnthropicClient createClient(
            String accessToken, String refreshToken,
            java.util.function.Supplier<KeychainCredentialReader.Credential> credSupplier) {
        return new AnthropicClient(accessToken, refreshToken, BASE_URL, TIMEOUT, false, null, credSupplier);
    }
}

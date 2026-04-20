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

    // -- Proactive refresh: tokenExpiresAt initialization --

    @Test
    void constructor_oauthWithKeychain_loadsExpiresAt() {
        long futureExpiry = System.currentTimeMillis() + 3600_000L;
        var cred = new KeychainCredentialReader.Credential(
                OAUTH_TOKEN, REFRESH_TOKEN, futureExpiry);

        var client = createClient(OAUTH_TOKEN, REFRESH_TOKEN, () -> cred);

        assertThat(client.tokenExpiresAtForTest()).isEqualTo(futureExpiry);
    }

    @Test
    void constructor_oauthWithKeychainNoExpiry_expiresAtZero() {
        var cred = new KeychainCredentialReader.Credential(OAUTH_TOKEN, REFRESH_TOKEN, 0);
        var client = createClient(OAUTH_TOKEN, REFRESH_TOKEN, () -> cred);

        assertThat(client.tokenExpiresAtForTest()).isEqualTo(0);
    }

    @Test
    void constructor_oauthKeychainThrows_expiresAtZero() {
        var client = createClient(OAUTH_TOKEN, REFRESH_TOKEN, () -> {
            throw new RuntimeException("Keychain locked");
        });

        assertThat(client.tokenExpiresAtForTest()).isEqualTo(0);
    }

    @Test
    void constructor_apiKey_doesNotReadKeychain() {
        var callCount = new AtomicInteger();
        var client = createClient(API_KEY, null, () -> {
            callCount.incrementAndGet();
            return null;
        });

        // API key mode should not read credential store for expiry
        assertThat(callCount.get()).isEqualTo(0);
        assertThat(client.tokenExpiresAtForTest()).isEqualTo(0);
    }

    // -- Proactive refresh: refreshProactivelyIfNeeded --

    @Test
    void proactiveRefresh_tokenNotExpired_doesNothing() {
        var callCount = new AtomicInteger();
        long futureExpiry = System.currentTimeMillis() + 3600_000L;
        var cred = new KeychainCredentialReader.Credential(
                OAUTH_TOKEN, REFRESH_TOKEN, futureExpiry);

        var client = createClient(OAUTH_TOKEN, REFRESH_TOKEN, () -> {
            callCount.incrementAndGet();
            return cred;
        });
        int constructorCalls = callCount.get();

        client.refreshProactivelyIfNeededForTest();

        // No additional credential reads — token is still valid
        assertThat(callCount.get()).isEqualTo(constructorCalls);
        assertThat(client.accessTokenForTest()).isEqualTo(OAUTH_TOKEN);
    }

    @Test
    void proactiveRefresh_unknownExpiry_doesNothing() {
        var callCount = new AtomicInteger();
        var client = createClient(OAUTH_TOKEN, REFRESH_TOKEN, () -> {
            callCount.incrementAndGet();
            return new KeychainCredentialReader.Credential(OAUTH_TOKEN, REFRESH_TOKEN, 0);
        });
        int constructorCalls = callCount.get();

        client.refreshProactivelyIfNeededForTest();

        // tokenExpiresAt == 0 means unknown — rely on 401 path
        assertThat(callCount.get()).isEqualTo(constructorCalls);
    }

    @Test
    void proactiveRefresh_apiKey_doesNothing() {
        var client = createClient(API_KEY, null, () -> null);
        // Should not throw or attempt any refresh
        client.refreshProactivelyIfNeededForTest();
        assertThat(client.accessTokenForTest()).isEqualTo(API_KEY);
    }

    @Test
    void proactiveRefresh_expired_noRefreshToken_triesKeychainRecovery() {
        var callCount = new AtomicInteger();
        var freshCred = new KeychainCredentialReader.Credential(
                "sk-ant-oat01-recovered", null,
                System.currentTimeMillis() + 3600_000L);

        var client = createClient(OAUTH_TOKEN, null, () -> {
            callCount.incrementAndGet();
            return freshCred;
        });

        // Simulate expired token
        client.setTokenExpiresAtForTest(System.currentTimeMillis() - 1000L);
        int callsBefore = callCount.get();

        client.refreshProactivelyIfNeededForTest();

        // Should have tried Keychain recovery (recoverCredentials calls supplier)
        assertThat(callCount.get()).isGreaterThan(callsBefore);
        // Should have picked up fresh access token from Keychain
        assertThat(client.accessTokenForTest()).isEqualTo("sk-ant-oat01-recovered");
    }

    @Test
    void proactiveRefresh_expired_noRefreshToken_keychainEmpty_fallsThrough() {
        var client = createClient(OAUTH_TOKEN, null, () -> null);

        // Simulate expired token
        client.setTokenExpiresAtForTest(System.currentTimeMillis() - 1000L);

        // Should not throw — falls back silently to 401 path
        client.refreshProactivelyIfNeededForTest();
        assertThat(client.accessTokenForTest()).isEqualTo(OAUTH_TOKEN);
    }

    @Test
    void proactiveRefresh_concurrentCalls_onlyOneRefreshAttempt() throws Exception {
        var recoveryCallCount = new AtomicInteger();
        var cred = new KeychainCredentialReader.Credential(
                "sk-ant-oat01-concurrent-refresh", null,
                System.currentTimeMillis() + 3600_000L);

        var client = createClient(OAUTH_TOKEN, null, () -> {
            recoveryCallCount.incrementAndGet();
            return cred;
        });

        // Simulate expired token
        client.setTokenExpiresAtForTest(System.currentTimeMillis() - 1000L);
        int callsBefore = recoveryCallCount.get();

        int threads = 8;
        var latch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    client.refreshProactivelyIfNeededForTest();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        latch.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        // synchronized means threads serialize; first one recovers and updates tokenExpiresAt,
        // subsequent threads see valid token and skip. Recovery should happen exactly once
        // (recoverCredentials is called once by the first thread that enters).
        int recoveryCalls = recoveryCallCount.get() - callsBefore;
        assertThat(recoveryCalls).isEqualTo(1);
        assertThat(client.accessTokenForTest()).isEqualTo("sk-ant-oat01-concurrent-refresh");
    }

    // -- recoverCredentials picks up expiresAt --

    @Test
    void recoverCredentials_updatesTokenExpiresAt() {
        long futureExpiry = System.currentTimeMillis() + 7200_000L;
        var cred = new KeychainCredentialReader.Credential(
                "sk-ant-oat01-fresh", REFRESH_TOKEN, futureExpiry);

        var client = createClient(OAUTH_TOKEN, null, () -> cred);
        client.setTokenExpiresAtForTest(0); // reset

        client.recoverCredentials();

        assertThat(client.tokenExpiresAtForTest()).isEqualTo(futureExpiry);
    }

    // -- Helper --

    // -- Isolation: allowKeychainFallback=false --

    @Test
    void isolatedClient_recoverCredentials_doesNotReadSupplier_returnsNoRecovery() {
        // A profile-supplied credential must never pick up fresher tokens from
        // the Claude CLI shared store, even if the supplier has them — that is
        // exactly the cross-account leak the isolation flag prevents.
        var crossAccountCred = new KeychainCredentialReader.Credential(
                "sk-ant-oat01-other-account", "sk-ant-ort01-other-account",
                System.currentTimeMillis() + 3600_000L);
        var supplierCalled = new AtomicInteger(0);

        var client = createIsolatedClient(OAUTH_TOKEN, REFRESH_TOKEN, () -> {
            supplierCalled.incrementAndGet();
            return crossAccountCred;
        });

        var result = client.recoverCredentials();

        // No foreign credentials were picked up, so only the profile's own
        // refresh token (still loaded) is available for the OAuth HTTP refresh.
        assertThat(result).isEqualTo(AnthropicClient.CredentialRecovery.REFRESH_AVAILABLE);
        assertThat(client.accessTokenForTest()).isEqualTo(OAUTH_TOKEN);
        assertThat(client.refreshTokenForTest()).isEqualTo(REFRESH_TOKEN);
        // The isolation shield replaces the real supplier with () -> null, so
        // the caller's supplier must never be invoked.
        assertThat(supplierCalled.get()).isZero();
    }

    @Test
    void isolatedClient_noRefreshToken_recoverCredentials_returnsNoRecovery() {
        var client = createIsolatedClient(OAUTH_TOKEN, null, () -> {
            throw new AssertionError("supplier must not be invoked when isolated");
        });

        var result = client.recoverCredentials();

        assertThat(result).isEqualTo(AnthropicClient.CredentialRecovery.NO_RECOVERY);
        assertThat(client.accessTokenForTest()).isEqualTo(OAUTH_TOKEN);
    }

    private static AnthropicClient createClient(
            String accessToken, String refreshToken,
            java.util.function.Supplier<KeychainCredentialReader.Credential> credSupplier) {
        return new AnthropicClient(accessToken, refreshToken, BASE_URL, TIMEOUT, false, null, credSupplier);
    }

    /** Builds a client with {@code allowKeychainFallback=false} so tests can
     *  verify that the Claude CLI shared store is neither read nor written. */
    private static AnthropicClient createIsolatedClient(
            String accessToken, String refreshToken,
            java.util.function.Supplier<KeychainCredentialReader.Credential> credSupplier) {
        return new AnthropicClient(accessToken, refreshToken, BASE_URL, TIMEOUT, false, null,
                credSupplier, false);
    }
}

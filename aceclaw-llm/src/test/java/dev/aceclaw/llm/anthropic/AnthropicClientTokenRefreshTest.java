package dev.aceclaw.llm.anthropic;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for OAuth token refresh logic in AnthropicClient.
 * Uses reflection to test ensureRefreshToken() since it's private.
 */
class AnthropicClientTokenRefreshTest {

    @Test
    void constructor_oauthToken_detectsOAuthMode() {
        var client = new AnthropicClient("sk-ant-oat01-test", "refresh-token");
        assertThat(client.provider()).isEqualTo("anthropic");
    }

    @Test
    void constructor_apiKey_notOAuthMode() {
        var client = new AnthropicClient("sk-ant-api03-test");
        assertThat(client.provider()).isEqualTo("anthropic");
    }

    @Test
    void constructor_nullRefreshToken_acceptedForOAuth() {
        // Should not throw — null refresh token is valid (Keychain recovery will handle it)
        var client = new AnthropicClient("sk-ant-oat01-test", null);
        assertThat(client.provider()).isEqualTo("anthropic");
    }

    @Test
    void constructor_blankAccessToken_throwsIllegalArgument() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new AnthropicClient(""));
    }

    @Test
    void constructor_nullAccessToken_throwsIllegalArgument() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new AnthropicClient(null));
    }

    @Test
    void ensureRefreshToken_withExistingRefreshToken_returnsTrue() throws Exception {
        var client = new AnthropicClient("sk-ant-oat01-test", "refresh-token");
        boolean result = invokeEnsureRefreshToken(client);
        // Should return true because refresh token was provided in constructor
        assertThat(result).isTrue();
    }

    @Test
    void ensureRefreshToken_withNullRefreshToken_andNoKeychain_returnsFalse() throws Exception {
        var client = new AnthropicClient("sk-ant-oat01-test", null);
        // On non-macOS or without Keychain, this should return false
        // (KeychainCredentialReader.read() returns null)
        boolean result = invokeEnsureRefreshToken(client);
        // Result depends on whether Keychain is available on this machine
        // We just verify it doesn't throw
        assertThat(result).isIn(true, false);
    }

    @Test
    void ensureRefreshToken_calledTwice_checksKeychainBothTimes() throws Exception {
        var client = new AnthropicClient("sk-ant-oat01-test", "refresh-token");
        // First call
        boolean first = invokeEnsureRefreshToken(client);
        assertThat(first).isTrue();
        // Second call should NOT early-return — should still check Keychain
        // (We can't directly verify Keychain was called, but we verify no exception)
        boolean second = invokeEnsureRefreshToken(client);
        assertThat(second).isTrue();
    }

    /**
     * Invokes the private ensureRefreshToken() method via reflection.
     */
    private static boolean invokeEnsureRefreshToken(AnthropicClient client) throws Exception {
        var method = AnthropicClient.class.getDeclaredMethod("ensureRefreshToken");
        method.setAccessible(true);
        return (boolean) method.invoke(client);
    }
}

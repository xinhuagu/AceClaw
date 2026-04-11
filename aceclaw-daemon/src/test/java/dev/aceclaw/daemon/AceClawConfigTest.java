package dev.aceclaw.daemon;

import dev.aceclaw.llm.anthropic.KeychainCredentialReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AceClawConfigTest {

    @Test
    void loadAppliesProviderOverrideWhenSupplied() {
        var config = AceClawConfig.load(null, "copilot");

        assertThat(config.provider()).isEqualTo("copilot");
    }

    @Test
    void loadNormalizesProviderOverrideToLowerCase() {
        var config = AceClawConfig.load(null, "OpenAI-Codex");

        assertThat(config.provider()).isEqualTo("openai-codex");
    }

    // -- applyKeychainCredential tests --

    /**
     * Creates a config with a dummy provider so that load() does NOT trigger
     * real Keychain credential discovery. We test applyKeychainCredential() in isolation.
     */
    private static AceClawConfig blankConfig() {
        return AceClawConfig.load(null, "test-dummy");
    }

    private static KeychainCredentialReader.Credential freshCredential(
            String accessToken, String refreshToken) {
        // expiresAt far in the future → not expired
        return new KeychainCredentialReader.Credential(
                accessToken, refreshToken, System.currentTimeMillis() + 3_600_000L);
    }

    private static KeychainCredentialReader.Credential expiredCredential(
            String accessToken, String refreshToken) {
        // expiresAt in the past → expired
        return new KeychainCredentialReader.Credential(accessToken, refreshToken, 1_000L);
    }

    @Test
    void applyKeychainCredential_freshToken_setsApiKey() {
        var config = blankConfig();
        var cred = freshCredential("sk-ant-oat01-fresh", "sk-ant-ort01-refresh");

        config.applyKeychainCredential(cred);

        assertThat(config.apiKey()).isEqualTo("sk-ant-oat01-fresh");
        assertThat(config.refreshToken()).isEqualTo("sk-ant-ort01-refresh");
    }

    @Test
    void applyKeychainCredential_expiredToken_stillSetsApiKey() {
        var config = blankConfig();
        var cred = expiredCredential("sk-ant-oat01-expired", "sk-ant-ort01-refresh");

        config.applyKeychainCredential(cred);

        // This is the key regression test: apiKey must be set even when expired,
        // so AnthropicClient can be constructed and will refresh before first request.
        assertThat(config.apiKey()).isEqualTo("sk-ant-oat01-expired");
        assertThat(config.refreshToken()).isEqualTo("sk-ant-ort01-refresh");
    }

    @Test
    void applyKeychainCredential_noRefreshToken_onlySetsApiKey() {
        var config = blankConfig();
        var cred = freshCredential("sk-ant-oat01-token", null);

        config.applyKeychainCredential(cred);

        assertThat(config.apiKey()).isEqualTo("sk-ant-oat01-token");
        assertThat(config.refreshToken()).isNull();
    }

    @Test
    void applyKeychainCredential_configHasNonOAuthApiKey_doesNotOverwrite() {
        var config = blankConfig();
        // Simulate config.json already having a regular API key
        config.applyKeychainCredential(freshCredential("sk-ant-api03-existing", null));
        // Now the config has a non-OAuth key set; clear refresh token to isolate
        String existingKey = config.apiKey();

        // Apply a Keychain credential — should NOT overwrite a non-OAuth key
        var keychainCred = freshCredential("sk-ant-oat01-keychain", "sk-ant-ort01-refresh");
        config.applyKeychainCredential(keychainCred);

        // The non-OAuth key doesn't start with "sk-ant-oat", and is not null/blank,
        // so the condition (apiKey == null || isBlank || configHasOAuth) is false.
        assertThat(config.apiKey()).isEqualTo(existingKey);
        // But refresh token is still loaded
        assertThat(config.refreshToken()).isEqualTo("sk-ant-ort01-refresh");
    }

    @Test
    void applyKeychainCredential_configHasStaleOAuthToken_overwritesWithKeychain() {
        var config = blankConfig();
        // Simulate config.json having a stale OAuth token
        config.applyKeychainCredential(expiredCredential("sk-ant-oat01-stale-from-config", null));

        // Now apply a fresh Keychain credential — should overwrite the stale OAuth token
        var freshCred = freshCredential("sk-ant-oat01-fresh-keychain", "sk-ant-ort01-refresh");
        config.applyKeychainCredential(freshCred);

        assertThat(config.apiKey()).isEqualTo("sk-ant-oat01-fresh-keychain");
        assertThat(config.refreshToken()).isEqualTo("sk-ant-ort01-refresh");
    }
}

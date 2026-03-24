package dev.aceclaw.llm.anthropic;

import dev.aceclaw.core.llm.ProviderCapabilities;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for AnthropicClient.capabilities() and configuredModel interaction.
 * Verifies that context window is correctly reported based on model and auth mode.
 */
class AnthropicClientCapabilitiesTest {

    private static final String OAUTH_TOKEN = "sk-ant-oat01-test";
    private static final String API_KEY = "sk-ant-api03-test";
    private static final String BASE_URL = "https://api.anthropic.com";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    // -- 4.6 models: always 1M regardless of auth or context1m --

    @Test
    void capabilities_opus46_oauth_returns1M() {
        var client = createOAuthClient(false);
        client.setConfiguredModel("claude-opus-4-6");
        assertThat(client.capabilities()).isEqualTo(ProviderCapabilities.ANTHROPIC_1M);
        assertThat(client.capabilities().contextWindowTokens()).isEqualTo(1_000_000);
    }

    @Test
    void capabilities_sonnet46_oauth_returns1M() {
        var client = createOAuthClient(false);
        client.setConfiguredModel("claude-sonnet-4-6");
        assertThat(client.capabilities()).isEqualTo(ProviderCapabilities.ANTHROPIC_1M);
    }

    @Test
    void capabilities_opus46_apiKey_returns1M() {
        var client = createApiKeyClient(false);
        client.setConfiguredModel("claude-opus-4-6");
        assertThat(client.capabilities()).isEqualTo(ProviderCapabilities.ANTHROPIC_1M);
    }

    @Test
    void capabilities_opus46_withContext1mFalse_still1M() {
        // 4.6 models are natively 1M, context1m flag doesn't matter
        var client = createOAuthClient(false);
        client.setConfiguredModel("claude-opus-4-6");
        assertThat(client.capabilities()).isEqualTo(ProviderCapabilities.ANTHROPIC_1M);
    }

    // -- Older models with API key + context1m --

    @Test
    void capabilities_sonnet45_apiKey_context1mTrue_returns1M() {
        var client = createApiKeyClient(true);
        client.setConfiguredModel("claude-sonnet-4-5-20250929");
        assertThat(client.capabilities()).isEqualTo(ProviderCapabilities.ANTHROPIC_1M);
    }

    @Test
    void capabilities_opus45_apiKey_context1mTrue_returns1M() {
        var client = createApiKeyClient(true);
        client.setConfiguredModel("claude-opus-4-5-20250918");
        assertThat(client.capabilities()).isEqualTo(ProviderCapabilities.ANTHROPIC_1M);
    }

    // -- Older models with OAuth: context1m suppressed --

    @Test
    void capabilities_sonnet45_oauth_context1mTrue_returns200K() {
        // OAuth mode suppresses context-1m beta for older models
        var client = createOAuthClient(true);
        client.setConfiguredModel("claude-sonnet-4-5-20250929");
        assertThat(client.capabilities()).isEqualTo(ProviderCapabilities.ANTHROPIC);
        assertThat(client.capabilities().contextWindowTokens()).isEqualTo(200_000);
    }

    // -- No configured model: falls back to default (sonnet-4-5) --

    @Test
    void capabilities_noConfiguredModel_returns200K() {
        var client = createOAuthClient(false);
        // No setConfiguredModel call — defaults to sonnet-4-5
        assertThat(client.capabilities()).isEqualTo(ProviderCapabilities.ANTHROPIC);
    }

    @Test
    void capabilities_noConfiguredModel_context1mTrue_apiKey_returns1M() {
        // Default model is sonnet-4-5 which is context1m-eligible
        var client = createApiKeyClient(true);
        assertThat(client.capabilities()).isEqualTo(ProviderCapabilities.ANTHROPIC_1M);
    }

    // -- defaultModel() reflects configured model --

    @Test
    void defaultModel_noConfig_returnsHardcodedDefault() {
        var client = createOAuthClient(false);
        assertThat(client.defaultModel()).isEqualTo("claude-sonnet-4-5-20250929");
    }

    @Test
    void defaultModel_withConfig_returnsConfigured() {
        var client = createOAuthClient(false);
        client.setConfiguredModel("claude-opus-4-6");
        assertThat(client.defaultModel()).isEqualTo("claude-opus-4-6");
    }

    // -- Helpers --

    private static AnthropicClient createOAuthClient(boolean context1m) {
        return new AnthropicClient(OAUTH_TOKEN, null, BASE_URL, TIMEOUT, context1m, null, () -> null);
    }

    private static AnthropicClient createApiKeyClient(boolean context1m) {
        return new AnthropicClient(API_KEY, null, BASE_URL, TIMEOUT, context1m, null, () -> null);
    }
}

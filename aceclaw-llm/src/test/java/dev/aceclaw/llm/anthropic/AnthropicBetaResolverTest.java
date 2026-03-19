package dev.aceclaw.llm.anthropic;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicBetaResolverTest {

    @Test
    void apiKeyMode_includesBaseBetasOnly() {
        String betas = AnthropicBetaResolver.resolve(false);
        assertThat(betas).isEqualTo(
                "fine-grained-tool-streaming-2025-05-14,interleaved-thinking-2025-05-14");
        assertThat(betas).doesNotContain("claude-code-20250219");
        assertThat(betas).doesNotContain("oauth-2025-04-20");
    }

    @Test
    void oauthMode_includesOauthAndBaseBetas() {
        String betas = AnthropicBetaResolver.resolve(true);
        assertThat(betas).isEqualTo(
                "claude-code-20250219,oauth-2025-04-20,"
                        + "fine-grained-tool-streaming-2025-05-14,interleaved-thinking-2025-05-14");
    }

    @Test
    void context1m_addedForEligibleModel_apiKeyMode() {
        String betas = AnthropicBetaResolver.resolve(false, "claude-opus-4-20250929", true, null);
        assertThat(betas).contains(AnthropicBetaResolver.CONTEXT_1M_BETA);
    }

    @Test
    void context1m_filteredForOAuth() {
        String betas = AnthropicBetaResolver.resolve(true, "claude-opus-4-20250929", true, null);
        assertThat(betas).doesNotContain(AnthropicBetaResolver.CONTEXT_1M_BETA);
    }

    @Test
    void context1m_ignoredForIneligibleModel() {
        String betas = AnthropicBetaResolver.resolve(false, "claude-haiku-4-5-20251001", true, null);
        assertThat(betas).doesNotContain(AnthropicBetaResolver.CONTEXT_1M_BETA);
    }

    @Test
    void extraBetas_areMerged() {
        String betas = AnthropicBetaResolver.resolve(false, "test-model", false,
                List.of("custom-beta-2025-01-01"));
        assertThat(betas).contains("custom-beta-2025-01-01");
        assertThat(betas).contains("fine-grained-tool-streaming-2025-05-14");
    }

    @Test
    void extraBetas_context1mFilteredInOAuth() {
        String betas = AnthropicBetaResolver.resolve(true, "claude-opus-4-20250929", false,
                List.of(AnthropicBetaResolver.CONTEXT_1M_BETA, "other-beta"));
        assertThat(betas).doesNotContain(AnthropicBetaResolver.CONTEXT_1M_BETA);
        assertThat(betas).contains("other-beta");
    }

    @Test
    void extraBetas_deduplication() {
        String betas = AnthropicBetaResolver.resolve(false, "test", false,
                List.of("interleaved-thinking-2025-05-14", "new-beta"));
        // Should not have duplicates
        long count = betas.chars().filter(c -> c == ',').count() + 1;
        assertThat(count).isEqualTo(3); // base(2) + new-beta(1), no duplicate
    }

    @Test
    void extraBetas_nullAndBlankFiltered() {
        String betas = AnthropicBetaResolver.resolve(false, "test", false,
                List.of("", " ", "valid-beta"));
        assertThat(betas).contains("valid-beta");
        assertThat(betas).doesNotContain(",,");
    }

    @Test
    void isContext1mEligible_opusModel() {
        assertThat(AnthropicBetaResolver.isContext1mEligible("claude-opus-4-20250929")).isTrue();
    }

    @Test
    void isContext1mEligible_sonnetModel() {
        assertThat(AnthropicBetaResolver.isContext1mEligible("claude-sonnet-4-5-20250929")).isTrue();
    }

    @Test
    void isContext1mEligible_haikuModel() {
        assertThat(AnthropicBetaResolver.isContext1mEligible("claude-haiku-4-5-20251001")).isFalse();
    }

    @Test
    void isContext1mEligible_nullAndBlank() {
        assertThat(AnthropicBetaResolver.isContext1mEligible(null)).isFalse();
        assertThat(AnthropicBetaResolver.isContext1mEligible("")).isFalse();
        assertThat(AnthropicBetaResolver.isContext1mEligible("  ")).isFalse();
    }
}

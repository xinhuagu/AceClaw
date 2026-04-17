package dev.aceclaw.daemon;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CopilotRequestMultipliersTest {

    @Test
    void returnsNullForNonCopilotProvider() {
        assertThat(CopilotRequestMultipliers.forProviderAndModel("anthropic", "claude-opus-4-6"))
                .isNull();
        assertThat(CopilotRequestMultipliers.forProviderAndModel("openai", "gpt-4o"))
                .isNull();
        assertThat(CopilotRequestMultipliers.forProviderAndModel(null, "claude-opus-4-6"))
                .isNull();
    }

    @Test
    void returnsKnownMultipliersForCopilotCanonicalModelIds() {
        // Copilot uses dot notation for Claude — matches the form LlmClientFactory's
        // ANTHROPIC_TO_COPILOT_MODEL translator emits. Values reflect the 2026-04 rate card.
        assertThat(CopilotRequestMultipliers.forProviderAndModel("copilot", "claude-opus-4.6"))
                .isEqualTo(3.0);
        assertThat(CopilotRequestMultipliers.forProviderAndModel("copilot", "claude-sonnet-4.5"))
                .isEqualTo(1.0);
        assertThat(CopilotRequestMultipliers.forProviderAndModel("copilot", "claude-haiku-4.5"))
                .isEqualTo(0.33);
        assertThat(CopilotRequestMultipliers.forProviderAndModel("copilot", "gpt-4o"))
                .isEqualTo(0.0);
        assertThat(CopilotRequestMultipliers.forProviderAndModel("copilot", "o3-mini"))
                .isEqualTo(0.33);
        assertThat(CopilotRequestMultipliers.forProviderAndModel("copilot", "grok-code-fast-1"))
                .isEqualTo(0.25);
    }

    @Test
    void hyphenatedClaudeIdNormalizesToDotNotation() {
        // Regression guard for the reviewer-found dot-notation bug (#424): Anthropic-style
        // hyphenated Claude ids must map to the Copilot dot form before table lookup, so
        // a caller passing pre-translation names still gets the right multiplier.
        assertThat(CopilotRequestMultipliers.forProviderAndModel("copilot", "claude-opus-4-6"))
                .isEqualTo(3.0);
        assertThat(CopilotRequestMultipliers.forProviderAndModel("copilot", "claude-sonnet-4-5"))
                .isEqualTo(1.0);
        assertThat(CopilotRequestMultipliers.forProviderAndModel("copilot", "claude-haiku-4-5"))
                .isEqualTo(0.33);
    }

    @Test
    void dateSuffixStrippedBeforeHyphenToDotNormalization() {
        // "claude-opus-4-6-20250929" must normalize first to "claude-opus-4-6" (date strip)
        // and then to "claude-opus-4.6" (hyphen→dot) to hit the table.
        assertThat(CopilotRequestMultipliers.forProviderAndModel("copilot", "claude-opus-4-6-20250929"))
                .isEqualTo(3.0);
        assertThat(CopilotRequestMultipliers.forProviderAndModel("copilot", "claude-sonnet-4-5-20260101"))
                .isEqualTo(1.0);
    }

    @Test
    void unknownCopilotModelFallsBackToDefault() {
        assertThat(CopilotRequestMultipliers.forProviderAndModel("copilot", "future-model-9000"))
                .isEqualTo(CopilotRequestMultipliers.DEFAULT_COPILOT_MULTIPLIER);
    }

    @Test
    void caseInsensitiveProviderAndModel() {
        assertThat(CopilotRequestMultipliers.forProviderAndModel("COPILOT", "CLAUDE-OPUS-4.6"))
                .isEqualTo(3.0);
        assertThat(CopilotRequestMultipliers.forProviderAndModel("Copilot", "Claude-Sonnet-4.5"))
                .isEqualTo(1.0);
    }

    @Test
    void blankOrNullModelYieldsDefault() {
        assertThat(CopilotRequestMultipliers.forProviderAndModel("copilot", null))
                .isEqualTo(CopilotRequestMultipliers.DEFAULT_COPILOT_MULTIPLIER);
        assertThat(CopilotRequestMultipliers.forProviderAndModel("copilot", ""))
                .isEqualTo(CopilotRequestMultipliers.DEFAULT_COPILOT_MULTIPLIER);
        assertThat(CopilotRequestMultipliers.forProviderAndModel("copilot", "   "))
                .isEqualTo(CopilotRequestMultipliers.DEFAULT_COPILOT_MULTIPLIER);
    }
}

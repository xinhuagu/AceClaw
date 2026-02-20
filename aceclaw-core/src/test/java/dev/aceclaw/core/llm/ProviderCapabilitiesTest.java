package dev.aceclaw.core.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProviderCapabilitiesTest {

    @Test
    void anthropicConstantHasCorrectContextWindow() {
        assertEquals(200_000, ProviderCapabilities.ANTHROPIC.contextWindowTokens());
        assertTrue(ProviderCapabilities.ANTHROPIC.supportsExtendedThinking());
        assertTrue(ProviderCapabilities.ANTHROPIC.supportsPromptCaching());
    }

    @Test
    void openaiConstantHasCorrectContextWindow() {
        assertEquals(128_000, ProviderCapabilities.OPENAI.contextWindowTokens());
        assertTrue(ProviderCapabilities.OPENAI.supportsImageInput());
        assertFalse(ProviderCapabilities.OPENAI.supportsExtendedThinking());
    }

    @Test
    void openaiCompatConstantHasCorrectContextWindow() {
        assertEquals(128_000, ProviderCapabilities.OPENAI_COMPAT.contextWindowTokens());
        assertFalse(ProviderCapabilities.OPENAI_COMPAT.supportsImageInput());
    }

    @Test
    void codexConstantHasCorrectContextWindow() {
        assertEquals(400_000, ProviderCapabilities.CODEX.contextWindowTokens());
        assertFalse(ProviderCapabilities.CODEX.supportsExtendedThinking());
        assertFalse(ProviderCapabilities.CODEX.supportsPromptCaching());
    }

    @Test
    void customConstructionWorks() {
        var custom = new ProviderCapabilities(false, false, true, 10, 64_000);
        assertEquals(64_000, custom.contextWindowTokens());
        assertEquals(10, custom.maxToolCallsPerResponse());
        assertTrue(custom.supportsImageInput());
    }
}

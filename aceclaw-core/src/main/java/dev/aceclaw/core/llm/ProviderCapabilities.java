package dev.aceclaw.core.llm;

/**
 * Describes what features a given LLM provider supports.
 *
 * <p>Used by the agent loop to conditionally enable features like extended thinking
 * or prompt caching based on the provider's capabilities.
 *
 * @param supportsExtendedThinking whether the provider supports extended thinking / chain-of-thought budgets
 * @param supportsPromptCaching    whether the provider supports server-side prompt caching
 * @param supportsImageInput       whether the provider supports image content blocks
 * @param maxToolCallsPerResponse  maximum tool calls the provider can return in a single response (0 = unlimited)
 */
public record ProviderCapabilities(
        boolean supportsExtendedThinking,
        boolean supportsPromptCaching,
        boolean supportsImageInput,
        int maxToolCallsPerResponse
) {

    /** Anthropic Claude: full feature support. */
    public static final ProviderCapabilities ANTHROPIC =
            new ProviderCapabilities(true, true, true, 0);

    /** OpenAI (GPT-4o, o1, etc.): image support, no extended thinking or prompt caching. */
    public static final ProviderCapabilities OPENAI =
            new ProviderCapabilities(false, false, true, 0);

    /** Generic OpenAI-compatible providers (Groq, Together, Ollama, etc.): minimal feature set. */
    public static final ProviderCapabilities OPENAI_COMPAT =
            new ProviderCapabilities(false, false, false, 0);

    /** GitHub Copilot Codex models (Responses API): no extended thinking, no caching, no images. */
    public static final ProviderCapabilities CODEX =
            new ProviderCapabilities(false, false, false, 0);
}

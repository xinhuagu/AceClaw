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
 * @param contextWindowTokens      default context window size in tokens for this provider
 */
public record ProviderCapabilities(
        boolean supportsExtendedThinking,
        boolean supportsPromptCaching,
        boolean supportsImageInput,
        int maxToolCallsPerResponse,
        int contextWindowTokens
) {

    /** Anthropic Claude: full feature support, 200K context. */
    public static final ProviderCapabilities ANTHROPIC =
            new ProviderCapabilities(true, true, true, 0, 200_000);

    /** OpenAI (GPT-4o, o1, etc.): image support, no extended thinking or prompt caching, 128K context. */
    public static final ProviderCapabilities OPENAI =
            new ProviderCapabilities(false, false, true, 0, 128_000);

    /** Generic OpenAI-compatible providers (Groq, Together, Ollama, etc.): minimal feature set, 128K context. */
    public static final ProviderCapabilities OPENAI_COMPAT =
            new ProviderCapabilities(false, false, false, 0, 128_000);

    /** GitHub Copilot Codex models (Responses API): no extended thinking, no caching, no images, 400K context. */
    public static final ProviderCapabilities CODEX =
            new ProviderCapabilities(false, false, false, 0, 400_000);
}

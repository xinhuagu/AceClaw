package dev.aceclaw.core.llm;

/**
 * Token usage statistics from an LLM response.
 *
 * @param inputTokens          number of tokens in the request
 * @param outputTokens         number of tokens in the response
 * @param cacheCreationInputTokens tokens written into the prompt cache (Anthropic-specific, 0 if N/A)
 * @param cacheReadInputTokens     tokens read from the prompt cache (Anthropic-specific, 0 if N/A)
 */
public record Usage(
        int inputTokens,
        int outputTokens,
        int cacheCreationInputTokens,
        int cacheReadInputTokens
) {

    /**
     * Convenience constructor for providers that do not support prompt caching.
     */
    public Usage(int inputTokens, int outputTokens) {
        this(inputTokens, outputTokens, 0, 0);
    }

    /**
     * Total tokens consumed (input + output).
     */
    public int totalTokens() {
        return inputTokens + outputTokens;
    }
}

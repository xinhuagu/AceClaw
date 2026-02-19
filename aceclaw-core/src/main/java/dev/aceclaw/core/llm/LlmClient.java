package dev.aceclaw.core.llm;

/**
 * Provider-agnostic interface for language model interaction.
 *
 * <p>Each LLM provider (Anthropic, OpenAI, Ollama, etc.) implements this interface.
 */
public interface LlmClient {

    /**
     * Lists available models from this provider.
     *
     * <p>Not all providers support this. The default implementation returns
     * an empty list, indicating that model listing is not available.
     *
     * @return list of model identifiers, or empty if not supported
     */
    default java.util.List<String> listModels() {
        return java.util.List.of();
    }

    /**
     * Sends a non-streaming request and blocks until the full response is available.
     *
     * @param request the LLM request
     * @return the complete response
     * @throws LlmException if the request fails
     */
    LlmResponse sendMessage(LlmRequest request) throws LlmException;

    /**
     * Sends a streaming request and returns a session for event-based consumption.
     *
     * @param request the LLM request
     * @return a stream session to register handlers on
     * @throws LlmException if the request setup fails
     */
    StreamSession streamMessage(LlmRequest request) throws LlmException;

    /**
     * Returns the provider name (e.g. "anthropic", "openai").
     */
    String provider();

    /**
     * Returns the default model identifier for this provider.
     */
    String defaultModel();

    /**
     * Returns the capabilities of this provider.
     * Defaults to generic OpenAI-compatible capabilities.
     */
    default ProviderCapabilities capabilities() {
        return ProviderCapabilities.OPENAI_COMPAT;
    }
}

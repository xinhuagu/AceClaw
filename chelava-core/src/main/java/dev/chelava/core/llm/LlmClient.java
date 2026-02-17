package dev.chelava.core.llm;

/**
 * Provider-agnostic interface for language model interaction.
 *
 * <p>Each LLM provider (Anthropic, OpenAI, Ollama, etc.) implements this interface.
 */
public interface LlmClient {

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
}

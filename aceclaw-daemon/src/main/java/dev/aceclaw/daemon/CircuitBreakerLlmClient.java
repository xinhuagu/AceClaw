package dev.aceclaw.daemon;

import dev.aceclaw.core.llm.*;
import dev.aceclaw.infra.health.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * LlmClient decorator that wraps calls with a {@link CircuitBreaker}.
 *
 * <p>The underlying client (e.g. AnthropicClient) already has internal retries.
 * This circuit breaker opens only when the backend is truly down — after the
 * inner client's retries are exhausted. When open, calls fail fast with a
 * {@link LlmException} (status 503, retryable=false).
 */
final class CircuitBreakerLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerLlmClient.class);

    private final LlmClient delegate;
    private final CircuitBreaker circuitBreaker;

    CircuitBreakerLlmClient(LlmClient delegate, CircuitBreaker circuitBreaker) {
        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public List<String> listModels() {
        return delegate.listModels();
    }

    @Override
    public LlmResponse sendMessage(LlmRequest request) throws LlmException {
        try {
            return circuitBreaker.executeChecked(() -> delegate.sendMessage(request));
        } catch (CircuitBreaker.CircuitOpenException e) {
            throw new LlmException(
                    "LLM circuit breaker is open: " + e.getMessage(), 503);
        }
    }

    @Override
    public StreamSession streamMessage(LlmRequest request) throws LlmException {
        try {
            return circuitBreaker.executeChecked(() -> delegate.streamMessage(request));
        } catch (CircuitBreaker.CircuitOpenException e) {
            throw new LlmException(
                    "LLM circuit breaker is open: " + e.getMessage(), 503);
        }
    }

    @Override
    public String provider() {
        return delegate.provider();
    }

    @Override
    public String defaultModel() {
        return delegate.defaultModel();
    }

    @Override
    public ProviderCapabilities capabilities() {
        return delegate.capabilities();
    }

    /**
     * Returns the underlying (unwrapped) LlmClient.
     */
    LlmClient delegate() {
        return delegate;
    }

    /**
     * Returns the circuit breaker protecting this client.
     */
    CircuitBreaker circuitBreaker() {
        return circuitBreaker;
    }
}

package dev.chelava.core.llm;

/**
 * Exception thrown when an LLM operation fails.
 *
 * <p>Wraps provider-specific errors into a uniform exception type
 * for the agent loop to handle.
 */
public class LlmException extends Exception {

    private final int statusCode;

    public LlmException(String message) {
        super(message);
        this.statusCode = -1;
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
    }

    public LlmException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public LlmException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /**
     * Returns the HTTP status code from the provider, or {@code -1} if not applicable.
     */
    public int statusCode() {
        return statusCode;
    }

    /**
     * Whether this error is retryable (e.g. rate-limit or transient server error).
     */
    public boolean isRetryable() {
        return statusCode == 429 || statusCode == 529 || (statusCode >= 500 && statusCode < 600);
    }
}

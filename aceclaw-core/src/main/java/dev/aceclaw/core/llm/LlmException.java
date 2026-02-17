package dev.aceclaw.core.llm;

/**
 * Exception thrown when an LLM operation fails.
 *
 * <p>Wraps provider-specific errors into a uniform exception type
 * for the agent loop to handle.
 */
public class LlmException extends Exception {

    private final int statusCode;
    private final long retryAfterSeconds;

    public LlmException(String message) {
        super(message);
        this.statusCode = -1;
        this.retryAfterSeconds = -1;
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
        this.retryAfterSeconds = -1;
    }

    public LlmException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
        this.retryAfterSeconds = -1;
    }

    public LlmException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.retryAfterSeconds = -1;
    }

    public LlmException(String message, int statusCode, long retryAfterSeconds) {
        super(message);
        this.statusCode = statusCode;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /**
     * Returns the HTTP status code from the provider, or {@code -1} if not applicable.
     */
    public int statusCode() {
        return statusCode;
    }

    /**
     * Returns the server-suggested retry delay in seconds, or {@code -1} if not specified.
     * Populated from the HTTP {@code Retry-After} header on 429 responses.
     */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    /**
     * Whether this error is retryable (e.g. rate-limit or transient server error).
     */
    public boolean isRetryable() {
        return statusCode == 429 || statusCode == 529 || (statusCode >= 500 && statusCode < 600);
    }
}

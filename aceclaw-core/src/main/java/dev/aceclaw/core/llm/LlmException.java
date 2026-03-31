package dev.aceclaw.core.llm;

/**
 * Exception thrown when an LLM operation fails.
 *
 * <p>Wraps provider-specific errors into a uniform exception type
 * for the agent loop to handle.
 */
public class LlmException extends Exception {

    private final int statusCode;
    private final long retryAfterMs;
    private final Boolean serverShouldRetry;

    public LlmException(String message) {
        super(message);
        this.statusCode = -1;
        this.retryAfterMs = -1;
        this.serverShouldRetry = null;
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
        this.retryAfterMs = -1;
        this.serverShouldRetry = null;
    }

    public LlmException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
        this.retryAfterMs = -1;
        this.serverShouldRetry = null;
    }

    public LlmException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.retryAfterMs = -1;
        this.serverShouldRetry = null;
    }

    public LlmException(String message, int statusCode, long retryAfterSeconds) {
        super(message);
        this.statusCode = statusCode;
        this.retryAfterMs = retryAfterSeconds > 0 ? retryAfterSeconds * 1000L : -1;
        this.serverShouldRetry = null;
    }

    /**
     * Full constructor with millisecond-precision retry delay and server-side retry hint.
     *
     * @param message           error message
     * @param statusCode        HTTP status code (-1 if not applicable)
     * @param retryAfterMs      server-suggested delay in milliseconds (-1 if not specified)
     * @param serverShouldRetry value of {@code x-should-retry} header (null if absent)
     */
    public LlmException(String message, int statusCode, long retryAfterMs, Boolean serverShouldRetry) {
        super(message);
        this.statusCode = statusCode;
        this.retryAfterMs = retryAfterMs;
        this.serverShouldRetry = serverShouldRetry;
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
        return retryAfterMs > 0 ? retryAfterMs / 1000L : -1;
    }

    /**
     * Returns the server-suggested retry delay in milliseconds, or {@code -1} if not specified.
     * Populated from {@code retry-after-ms} (preferred) or {@code Retry-After} headers.
     */
    public long retryAfterMs() {
        return retryAfterMs;
    }

    /**
     * Whether this error is retryable (e.g. rate-limit or transient server error).
     * Respects the {@code x-should-retry} header when present.
     */
    public boolean isRetryable() {
        if (serverShouldRetry != null) {
            return serverShouldRetry;
        }
        return statusCode == 429 || statusCode == 529 || (statusCode >= 500 && statusCode < 600);
    }
}

package dev.aceclaw.llm.anthropic;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.llm.LlmClient;
import dev.aceclaw.core.llm.LlmException;
import dev.aceclaw.core.llm.LlmRequest;
import dev.aceclaw.core.llm.LlmResponse;
import dev.aceclaw.core.llm.ProviderCapabilities;
import dev.aceclaw.core.llm.StreamSession;
import dev.aceclaw.core.util.WaitSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * Anthropic Claude API client using {@link HttpClient}.
 *
 * <p>Implements both synchronous ({@link #sendMessage}) and streaming
 * ({@link #streamMessage}) interactions with the Anthropic Messages API.
 *
 * <p>Supports two authentication modes:
 * <ul>
 *   <li>Standard API key ({@code sk-ant-api03-*}): sent via {@code x-api-key} header</li>
 *   <li>OAuth token ({@code sk-ant-oat01-*}): sent via {@code Authorization: Bearer} header,
 *       with automatic token refresh when expired</li>
 * </ul>
 *
 * <p>Beta header construction is delegated to {@link AnthropicBetaResolver} which
 * dynamically composes betas based on auth mode, model, and configuration.
 */
public final class AnthropicClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicClient.class);

    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    private static final String MESSAGES_PATH = "/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String DEFAULT_MODEL = "claude-sonnet-4-5-20250929";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    /** OAuth token refresh endpoint (platform.claude.com, same as Claude CLI / OpenClaw). */
    private static final String TOKEN_REFRESH_URL = "https://platform.claude.com/v1/oauth/token";

    /** Claude Code's OAuth client ID. */
    private static final String OAUTH_CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e";

    /** OAuth scopes required for token refresh. */
    private static final String OAUTH_SCOPES =
            "org:create_api_key user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload";

    private volatile String accessToken;
    private volatile String refreshToken;
    private final boolean isOAuth;
    private final String baseUrl;
    private final HttpClient httpClient;
    private final AnthropicMapper mapper;
    private final ObjectMapper jsonMapper;
    private final Duration requestTimeout;

    // Dynamic beta configuration
    private final boolean context1m;
    private final List<String> extraBetas;

    /** Pluggable credential reader for testability. */
    private final java.util.function.Supplier<KeychainCredentialReader.Credential> credentialSupplier;

    /**
     * Creates a client with a standard API key using default settings.
     *
     * @param apiKey the Anthropic API key
     */
    public AnthropicClient(String apiKey) {
        this(apiKey, null, DEFAULT_BASE_URL, DEFAULT_TIMEOUT, false, null);
    }

    /**
     * Creates a client with an OAuth access token and optional refresh token.
     *
     * @param accessToken  the access token (API key or OAuth token)
     * @param refreshToken the OAuth refresh token (null for standard API keys)
     */
    public AnthropicClient(String accessToken, String refreshToken) {
        this(accessToken, refreshToken, DEFAULT_BASE_URL, DEFAULT_TIMEOUT, false, null);
    }

    /**
     * Creates a client with full configuration (backward compatible).
     *
     * @param accessToken    the access token (API key or OAuth token)
     * @param refreshToken   the OAuth refresh token (null for standard API keys)
     * @param baseUrl        API base URL (without trailing slash)
     * @param requestTimeout HTTP request timeout
     */
    public AnthropicClient(String accessToken, String refreshToken, String baseUrl, Duration requestTimeout) {
        this(accessToken, refreshToken, baseUrl, requestTimeout, false, null);
    }

    /**
     * Creates a client with full configuration including beta options.
     *
     * @param accessToken    the access token (API key or OAuth token)
     * @param refreshToken   the OAuth refresh token (null for standard API keys)
     * @param baseUrl        API base URL (without trailing slash)
     * @param requestTimeout HTTP request timeout
     * @param context1m      whether to enable 1M context window beta
     * @param extraBetas     additional beta flags from config (may be null)
     */
    public AnthropicClient(String accessToken, String refreshToken, String baseUrl,
                           Duration requestTimeout, boolean context1m, List<String> extraBetas) {
        this(accessToken, refreshToken, baseUrl, requestTimeout, context1m, extraBetas,
                KeychainCredentialReader::read);
    }

    /**
     * Package-private constructor for testing with a pluggable credential supplier.
     */
    AnthropicClient(String accessToken, String refreshToken, String baseUrl,
                    Duration requestTimeout, boolean context1m, List<String> extraBetas,
                    java.util.function.Supplier<KeychainCredentialReader.Credential> credentialSupplier) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("API key / access token must not be null or blank");
        }
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.isOAuth = accessToken.startsWith("sk-ant-oat");
        this.baseUrl = baseUrl;
        this.requestTimeout = requestTimeout;
        this.context1m = context1m;
        this.extraBetas = extraBetas != null ? List.copyOf(extraBetas) : List.of();
        this.credentialSupplier = credentialSupplier != null ? credentialSupplier : KeychainCredentialReader::read;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        this.jsonMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.mapper = new AnthropicMapper(jsonMapper, isOAuth);

        if (isOAuth) {
            log.info("Using OAuth authentication (token refresh {})",
                    refreshToken != null ? "enabled" : "disabled");
        }
    }

    /** Maximum number of retry attempts for transient failures. */
    private static final int MAX_RETRIES = 3;

    /** Maximum backoff delay in milliseconds (cap for Retry-After). */
    private static final long MAX_BACKOFF_MS = 60_000L;

    @Override
    public LlmResponse sendMessage(LlmRequest request) throws LlmException {
        String model = request.model();
        String requestBody = mapper.toRequestJson(request, false);
        log.debug("Sending non-streaming request to Anthropic: model={}", model);

        return executeWithRetry(() -> {
            try {
                HttpRequest httpRequest = buildRequest(requestBody, model);
                HttpResponse<String> httpResponse = httpClient.send(
                        httpRequest, HttpResponse.BodyHandlers.ofString());

                int statusCode = httpResponse.statusCode();
                String responseBody = httpResponse.body();

                // Retry once with refreshed token on 401
                if (statusCode == 401 && isOAuth) {
                    if (ensureRefreshToken()) {
                        log.info("OAuth token expired, attempting refresh...");
                        if (refreshAccessToken()) {
                            httpRequest = buildRequest(requestBody, model);
                            httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                            statusCode = httpResponse.statusCode();
                            responseBody = httpResponse.body();
                        }
                    } else {
                        log.warn("OAuth token expired (401) but no refresh token available; "
                                + "restart daemon or re-run /login to refresh credentials");
                    }
                }

                if (statusCode != 200) {
                    log.error("Anthropic API error: status={}, body={}", statusCode, responseBody);
                    long retryAfter = parseRetryAfter(httpResponse);
                    throw new LlmException(
                            "Anthropic API returned HTTP " + statusCode + ": " + responseBody,
                            statusCode, retryAfter);
                }

                LlmResponse response = mapper.toResponse(responseBody);
                log.debug("Received response: id={}, stopReason={}, usage={}in/{}out",
                        response.id(), response.stopReason(),
                        response.usage().inputTokens(), response.usage().outputTokens());
                return response;
            } catch (LlmException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException("Request interrupted", e);
            } catch (Exception e) {
                throw new LlmException("Failed to send message to Anthropic", e);
            }
        });
    }

    @Override
    public StreamSession streamMessage(LlmRequest request) throws LlmException {
        String model = request.model();
        String requestBody = mapper.toRequestJson(request, true);
        log.debug("Sending streaming request to Anthropic: model={}", model);

        return executeWithRetry(() -> {
            try {
                HttpRequest httpRequest = buildRequest(requestBody, model);
                HttpResponse<Stream<String>> httpResponse = httpClient.send(
                        httpRequest, HttpResponse.BodyHandlers.ofLines());

                int statusCode = httpResponse.statusCode();

                // Retry once with refreshed token on 401
                if (statusCode == 401 && isOAuth) {
                    if (ensureRefreshToken()) {
                        log.info("OAuth token expired, attempting refresh...");
                        if (refreshAccessToken()) {
                            httpRequest = buildRequest(requestBody, model);
                            httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines());
                            statusCode = httpResponse.statusCode();
                        }
                    } else {
                        log.warn("OAuth token expired (401) but no refresh token available; "
                                + "restart daemon or re-run /login to refresh credentials");
                    }
                }

                if (statusCode != 200) {
                    String errorBody = httpResponse.body()
                            .reduce("", (a, b) -> a + b);
                    log.error("Anthropic API stream error: status={}, body={}", statusCode, errorBody);
                    long retryAfter = parseRetryAfter(httpResponse);
                    throw new LlmException(
                            "Anthropic API returned HTTP " + statusCode + ": " + errorBody,
                            statusCode, retryAfter);
                }

                return new AnthropicStreamSession(httpResponse, mapper);
            } catch (LlmException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException("Stream request interrupted", e);
            } catch (Exception e) {
                throw new LlmException("Failed to start streaming from Anthropic", e);
            }
        });
    }

    /**
     * Executes an action with retry on transient failures.
     * Retries up to {@link #MAX_RETRIES} times with exponential backoff and jitter.
     */
    private <T> T executeWithRetry(RetryableAction<T> action) throws LlmException {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                return action.execute();
            } catch (LlmException e) {
                if (!e.isRetryable() || attempt == MAX_RETRIES) {
                    throw e;
                }
                long backoffMs = calculateBackoff(attempt, e);
                log.warn("Retrying request (attempt {}/{}, backoff {}ms): HTTP {}",
                        attempt + 1, MAX_RETRIES, backoffMs, e.statusCode());
                try {
                    WaitSupport.sleepInterruptibly(Duration.ofMillis(backoffMs));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new LlmException("Retry interrupted", ie);
                }
            }
        }
        throw new AssertionError("unreachable");
    }

    /**
     * Calculates backoff delay for a retry attempt.
     * Uses exponential backoff (1s, 2s, 4s) with +/-20% jitter.
     * Respects the Retry-After header if present (capped at 60s).
     */
    private static long calculateBackoff(int attempt, LlmException e) {
        if (e.retryAfterSeconds() > 0) {
            return Math.min(e.retryAfterSeconds() * 1000L, MAX_BACKOFF_MS);
        }
        long baseMs = 1000L * (1L << attempt); // 1s, 2s, 4s
        double jitterFactor = 1.0 + 0.2 * (ThreadLocalRandom.current().nextDouble() * 2 - 1);
        return Math.min((long) (baseMs * jitterFactor), MAX_BACKOFF_MS);
    }

    /**
     * Parses the Retry-After header from an HTTP response.
     * Returns the delay in seconds, or -1 if not present or unparseable.
     */
    private static long parseRetryAfter(HttpResponse<?> response) {
        var header = response.headers().firstValue("retry-after").orElse(null);
        if (header == null) return -1;
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @FunctionalInterface
    private interface RetryableAction<T> {
        T execute() throws LlmException;
    }

    @Override
    public String provider() {
        return "anthropic";
    }

    @Override
    public String defaultModel() {
        return DEFAULT_MODEL;
    }

    @Override
    public ProviderCapabilities capabilities() {
        return ProviderCapabilities.ANTHROPIC;
    }

    private HttpRequest buildRequest(String body, String modelId) {
        String betaHeader = AnthropicBetaResolver.resolve(isOAuth, modelId, context1m, extraBetas);

        var builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + MESSAGES_PATH))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("anthropic-beta", betaHeader);

        if (isOAuth) {
            builder.header("Authorization", "Bearer " + accessToken)
                    .header("user-agent", "claude-cli/2.1.50 (external, cli)")
                    .header("x-app", "cli");
        } else {
            builder.header("x-api-key", accessToken);
        }

        return builder
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    /**
     * Refreshes the OAuth access token using the refresh token.
     * On success, writes the new token back to the credential source (Keychain or file).
     *
     * @return true if refresh succeeded
     */
    private boolean refreshAccessToken() {
        try {
            var bodyNode = jsonMapper.createObjectNode();
            bodyNode.put("grant_type", "refresh_token");
            bodyNode.put("client_id", OAUTH_CLIENT_ID);
            bodyNode.put("refresh_token", refreshToken);
            bodyNode.put("scope", OAUTH_SCOPES);

            HttpRequest refreshRequest = HttpRequest.newBuilder()
                    .uri(URI.create(TOKEN_REFRESH_URL))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(bodyNode)))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    refreshRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode tokenResponse = jsonMapper.readTree(response.body());
                String newAccessToken = tokenResponse.path("access_token").asText(null);
                if (newAccessToken != null && !newAccessToken.isBlank()) {
                    this.accessToken = newAccessToken;

                    // Capture new refresh_token if provided (Anthropic may rotate it)
                    String newRefreshToken = tokenResponse.path("refresh_token").asText(null);
                    if (newRefreshToken != null && !newRefreshToken.isBlank()) {
                        this.refreshToken = newRefreshToken;
                    }

                    // Calculate expiry: expires_in seconds minus 5 minute grace period
                    long expiresInMs = 3600_000L; // 1 hour default
                    JsonNode expiresIn = tokenResponse.path("expires_in");
                    if (!expiresIn.isMissingNode() && expiresIn.isNumber()) {
                        expiresInMs = expiresIn.asLong() * 1000L;
                    }
                    long newExpiresAt = System.currentTimeMillis() + expiresInMs - 300_000L; // 5min grace

                    log.info("OAuth token refreshed successfully");

                    // Write back to credential source so daemon restart picks up the fresh token
                    writeBackCredentials(this.accessToken, this.refreshToken, newExpiresAt);

                    return true;
                }
            }

            log.warn("OAuth token refresh failed: status={}, body={}",
                    response.statusCode(), response.body());
            return false;
        } catch (Exception e) {
            log.error("OAuth token refresh error: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Ensures a refresh token is available, re-reading from Keychain/file if needed.
     * This handles the case where the daemon started without a refresh token
     * (e.g., config.json has apiKey but no refreshToken, and Keychain wasn't read).
     *
     * @return true if a refresh token is available
     */
    boolean ensureRefreshToken() {
        // Always consult Keychain — it may have fresher tokens than what's in memory,
        // even if we already have a refresh token (e.g., Claude CLI refreshed externally).
        try {
            var cred = credentialSupplier.get();
            if (cred != null) {
                // Pick up fresher access token independently of refresh token availability
                if (cred.accessToken() != null && !cred.isExpired()) {
                    this.accessToken = cred.accessToken();
                    log.debug("Updated access token from credential store");
                }
                // Pick up refresh token if available
                if (cred.refreshToken() != null) {
                    this.refreshToken = cred.refreshToken();
                    log.info("Loaded refresh token from credential store on 401 recovery");
                }
            }
        } catch (Exception e) {
            log.warn("Failed to read credentials on 401 recovery: {}", e.getMessage());
        }
        return this.refreshToken != null;
    }

    /** Package-private: current access token for testing. */
    String accessTokenForTest() { return accessToken; }

    /** Package-private: current refresh token for testing. */
    String refreshTokenForTest() { return refreshToken; }

    /**
     * Writes refreshed credentials back to the original source (Keychain or file).
     */
    private void writeBackCredentials(String newAccessToken, String newRefreshToken, long newExpiresAt) {
        try {
            boolean written = KeychainCredentialReader.writeToKeychain(
                    newAccessToken, newRefreshToken, newExpiresAt);
            if (!written) {
                KeychainCredentialReader.writeToFile(
                        newAccessToken, newRefreshToken, newExpiresAt);
            }
        } catch (Exception e) {
            log.debug("Could not write back refreshed credentials: {}", e.getMessage());
        }
    }
}

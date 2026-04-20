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
import java.util.concurrent.locks.ReentrantLock;
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

    /**
     * OAuth scopes for token refresh. Omitted from the request body because
     * the platform derives scopes from the original authorization grant.
     * Sending explicit scopes causes {@code invalid_scope} errors when the
     * server-side scope set changes.
     */

    private volatile String accessToken;
    private volatile String refreshToken;
    private volatile long tokenExpiresAt; // epoch millis; 0 = unknown
    private final boolean isOAuth;
    private volatile String configuredModel; // model from config (may be null → use DEFAULT_MODEL)
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
     * When false, the client is isolated from Claude CLI's shared credential
     * store: 401 recovery does not read Keychain/credentials.json, and
     * successful OAuth refresh does not write the new token back to it.
     * Set to false when credentials came from an explicit profile/env so one
     * account's daemon cannot corrupt another account's CLI login.
     */
    private final boolean allowKeychainFallback;

    /** Guards OAuth token mutations without blocking concurrent API calls. */
    private final ReentrantLock tokenLock = new ReentrantLock();

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
        this(accessToken, refreshToken, baseUrl, requestTimeout, context1m, extraBetas, true);
    }

    /**
     * Creates a client with explicit control over Claude CLI credential sharing.
     *
     * @param allowKeychainFallback when false, 401 recovery will not read from
     *     Claude CLI's Keychain/credentials.json, and OAuth refresh will not
     *     write the refreshed token back to that shared store.
     */
    public AnthropicClient(String accessToken, String refreshToken, String baseUrl,
                           Duration requestTimeout, boolean context1m, List<String> extraBetas,
                           boolean allowKeychainFallback) {
        this(accessToken, refreshToken, baseUrl, requestTimeout, context1m, extraBetas,
                KeychainCredentialReader::read, allowKeychainFallback);
    }

    /**
     * Package-private constructor for testing with a pluggable credential supplier.
     */
    AnthropicClient(String accessToken, String refreshToken, String baseUrl,
                    Duration requestTimeout, boolean context1m, List<String> extraBetas,
                    java.util.function.Supplier<KeychainCredentialReader.Credential> credentialSupplier) {
        this(accessToken, refreshToken, baseUrl, requestTimeout, context1m, extraBetas,
                credentialSupplier, true);
    }

    /**
     * Full package-private constructor for tests that need to exercise the
     * isolation flag together with a pluggable credential supplier.
     */
    AnthropicClient(String accessToken, String refreshToken, String baseUrl,
                    Duration requestTimeout, boolean context1m, List<String> extraBetas,
                    java.util.function.Supplier<KeychainCredentialReader.Credential> credentialSupplier,
                    boolean allowKeychainFallback) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("API key / access token must not be null or blank");
        }
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.isOAuth = accessToken.startsWith("sk-ant-oat");
        this.baseUrl = baseUrl;
        this.requestTimeout = requestTimeout;
        this.context1m = context1m;
        this.configuredModel = null; // set via setConfiguredModel() after construction
        this.extraBetas = extraBetas != null ? List.copyOf(extraBetas) : List.of();
        this.allowKeychainFallback = allowKeychainFallback;
        this.credentialSupplier = credentialSupplier != null ? credentialSupplier : KeychainCredentialReader::read;

        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        this.jsonMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.mapper = new AnthropicMapper(jsonMapper, isOAuth);

        if (isOAuth) {
            // Load initial token expiry from the Claude CLI shared store only
            // when fallback is allowed. Isolated clients must not consult that
            // store — otherwise another account's expiry would drive this
            // client's proactive refresh.
            if (!allowKeychainFallback) {
                log.info("Using OAuth authentication (isolated from Claude CLI store, token refresh {}, expiry unknown)",
                        refreshToken != null ? "enabled" : "disabled");
            } else {
                try {
                    var cred = this.credentialSupplier.get();
                    if (cred != null && cred.expiresAt() > 0) {
                        this.tokenExpiresAt = cred.expiresAt();
                        log.info("Using OAuth authentication (token refresh {}, expires at {})",
                                refreshToken != null ? "enabled" : "disabled",
                                java.time.Instant.ofEpochMilli(cred.expiresAt()));
                    } else {
                        log.info("Using OAuth authentication (token refresh {}, expiry unknown)",
                                refreshToken != null ? "enabled" : "disabled");
                    }
                } catch (Exception e) {
                    log.info("Using OAuth authentication (token refresh {}, could not read expiry: {})",
                            refreshToken != null ? "enabled" : "disabled", e.getMessage());
                }
            }
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
                refreshProactivelyIfNeeded();
                HttpRequest httpRequest = buildRequest(requestBody, model);
                HttpResponse<String> httpResponse = httpClient.send(
                        httpRequest, HttpResponse.BodyHandlers.ofString());

                int statusCode = httpResponse.statusCode();
                String responseBody = httpResponse.body();

                // Retry once with refreshed token on 401
                if (statusCode == 401 && isOAuth) {
                    var recovery = recoverCredentials();
                    switch (recovery) {
                        case ACCESS_TOKEN_UPDATED -> {
                            // Keychain had a fresh token — retry with it directly
                            log.info("Access token updated from credential store, retrying...");
                            httpRequest = buildRequest(requestBody, model);
                            httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                            statusCode = httpResponse.statusCode();
                            responseBody = httpResponse.body();
                        }
                        case REFRESH_AVAILABLE -> {
                            log.info("OAuth token expired, attempting refresh...");
                            if (refreshAccessToken(true)) {
                                httpRequest = buildRequest(requestBody, model);
                                httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                                statusCode = httpResponse.statusCode();
                                responseBody = httpResponse.body();
                            }
                        }
                        case NO_RECOVERY -> log.warn("OAuth token expired (401) but no recovery available; "
                                + "restart daemon or re-run /login to refresh credentials");
                    }
                }

                if (statusCode != 200) {
                    log.error("Anthropic API error: status={}, body={}", statusCode, responseBody);
                    long retryAfterMs = parseRetryAfterMs(httpResponse);
                    Boolean shouldRetry = parseShouldRetry(httpResponse);
                    throw new LlmException(
                            "Anthropic API returned HTTP " + statusCode + ": " + responseBody,
                            statusCode, retryAfterMs, shouldRetry);
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
                refreshProactivelyIfNeeded();
                HttpRequest httpRequest = buildRequest(requestBody, model);
                HttpResponse<Stream<String>> httpResponse = httpClient.send(
                        httpRequest, HttpResponse.BodyHandlers.ofLines());

                int statusCode = httpResponse.statusCode();

                // Retry once with refreshed token on 401
                if (statusCode == 401 && isOAuth) {
                    var recovery = recoverCredentials();
                    switch (recovery) {
                        case ACCESS_TOKEN_UPDATED -> {
                            log.info("Access token updated from credential store, retrying...");
                            httpRequest = buildRequest(requestBody, model);
                            httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines());
                            statusCode = httpResponse.statusCode();
                        }
                        case REFRESH_AVAILABLE -> {
                            log.info("OAuth token expired, attempting refresh...");
                            if (refreshAccessToken(true)) {
                                httpRequest = buildRequest(requestBody, model);
                                httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines());
                                statusCode = httpResponse.statusCode();
                            }
                        }
                        case NO_RECOVERY -> log.warn("OAuth token expired (401) but no recovery available; "
                                + "restart daemon or re-run /login to refresh credentials");
                    }
                }

                if (statusCode != 200) {
                    String errorBody = httpResponse.body()
                            .reduce("", (a, b) -> a + b);
                    log.error("Anthropic API stream error: status={}, body={}", statusCode, errorBody);
                    long retryAfterMs = parseRetryAfterMs(httpResponse);
                    Boolean shouldRetry = parseShouldRetry(httpResponse);
                    throw new LlmException(
                            "Anthropic API returned HTTP " + statusCode + ": " + errorBody,
                            statusCode, retryAfterMs, shouldRetry);
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
     * Uses exponential backoff (0.5s, 1s, 2s, 4s, 8s) with ±25% jitter.
     * Respects the retry-after-ms / Retry-After headers if present (capped at 60s).
     */
    private static long calculateBackoff(int attempt, LlmException e) {
        if (e.retryAfterMs() > 0) {
            return Math.min(e.retryAfterMs(), MAX_BACKOFF_MS);
        }
        long baseMs = 500L * (1L << attempt); // 0.5s, 1s, 2s, 4s, 8s
        long jitter = (long) (baseMs * 0.25 * Math.random());
        return Math.min(baseMs + jitter, MAX_BACKOFF_MS);
    }

    /**
     * Parses retry delay from HTTP response headers.
     * Checks {@code retry-after-ms} first (millisecond precision),
     * then falls back to {@code Retry-After} (second precision).
     *
     * @return delay in milliseconds, or -1 if not present
     */
    private static long parseRetryAfterMs(HttpResponse<?> response) {
        // Prefer retry-after-ms for sub-second precision (Anthropic extension)
        var msHeader = response.headers().firstValue("retry-after-ms").orElse(null);
        if (msHeader != null) {
            try {
                long ms = Long.parseLong(msHeader.trim());
                if (ms > 0) return ms;
            } catch (NumberFormatException ignored) {}
        }
        // Fall back to standard Retry-After (seconds)
        var header = response.headers().firstValue("retry-after").orElse(null);
        if (header == null) return -1;
        try {
            return Long.parseLong(header.trim()) * 1000L;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Parses the {@code x-should-retry} header from an HTTP response.
     *
     * @return true/false if header is present, null if absent
     */
    private static Boolean parseShouldRetry(HttpResponse<?> response) {
        var header = response.headers().firstValue("x-should-retry").orElse(null);
        if (header == null) return null;
        return "true".equalsIgnoreCase(header.trim());
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
        return configuredModel != null ? configuredModel : DEFAULT_MODEL;
    }

    @Override
    public ProviderCapabilities capabilities() {
        String model = defaultModel();
        // 4.6 models natively support 1M context (no beta needed, works with OAuth)
        if (AnthropicBetaResolver.isAdaptiveThinkingModel(model)) {
            return ProviderCapabilities.ANTHROPIC_1M;
        }
        // Older models need context-1m beta, which is rejected in OAuth mode.
        // Only report 1M when the beta will actually be sent.
        if (context1m && !isOAuth && AnthropicBetaResolver.isContext1mEligible(model)) {
            return ProviderCapabilities.ANTHROPIC_1M;
        }
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
     * Proactively refreshes the OAuth access token if it is known to be expired or
     * about to expire. Called before each API request so the daemon never sends a
     * request with a stale token. Falls back silently if no refresh token is
     * available — the 401 reactive path will handle it.
     */
    private void refreshProactivelyIfNeeded() {
        // Fast-path: no lock needed for non-OAuth or when token is still valid.
        if (!isOAuth) return;
        if (tokenExpiresAt <= 0) return; // unknown expiry — rely on 401 path
        if (System.currentTimeMillis() < tokenExpiresAt) return; // still valid

        // Token appears expired — try to acquire the lock without blocking.
        // If another thread is already refreshing, skip: worst case we get a 401
        // and the reactive recovery path handles it.
        if (!tokenLock.tryLock()) {
            log.debug("Proactive refresh skipped — another thread is refreshing");
            return;
        }
        try {
            // Double-check after acquiring lock (token may have been refreshed already).
            if (tokenExpiresAt > 0 && System.currentTimeMillis() < tokenExpiresAt) return;

            log.info("OAuth access token expired, refreshing proactively...");
            if (refreshToken == null) {
                var recovery = recoverCredentials0();
                if (recovery == CredentialRecovery.ACCESS_TOKEN_UPDATED) {
                    log.info("Proactive refresh: got fresh access token from credential store");
                    return;
                }
                if (recovery == CredentialRecovery.NO_RECOVERY) {
                    log.warn("Proactive refresh: no refresh token available, will rely on 401 path");
                    return;
                }
            }
            if (refreshToken != null) {
                refreshAccessToken();
            }
        } finally {
            tokenLock.unlock();
        }
    }

    /**
     * Refreshes the OAuth access token using the refresh token.
     * On success, writes the new token back to the credential source (Keychain or file).
     *
     * @return true if refresh succeeded
     */
    private boolean refreshAccessToken() {
        return refreshAccessToken(false);
    }

    /**
     * @param force if true, skip the local expiry check and always contact the token
     *              endpoint. Used by the 401 reactive path where the server has already
     *              rejected the current token (e.g. early revocation).
     */
    private boolean refreshAccessToken(boolean force) {
        // Acquire the token lock (reentrant — safe when called from proactive refresh
        // which already holds the lock).  This serializes all token mutations so the
        // 401 reactive path cannot race with the proactive path.
        tokenLock.lock();
        try {
            // Double-check: another thread may have refreshed while we waited.
            // Skip this check when forced (401 path — server already rejected the token).
            if (!force && tokenExpiresAt > 0 && System.currentTimeMillis() < tokenExpiresAt) {
                log.debug("Token already refreshed by another thread, skipping");
                return true;
            }

            var bodyNode = jsonMapper.createObjectNode();
            bodyNode.put("grant_type", "refresh_token");
            bodyNode.put("client_id", OAUTH_CLIENT_ID);
            bodyNode.put("refresh_token", refreshToken);

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
                    this.tokenExpiresAt = newExpiresAt;

                    log.info("OAuth token refreshed successfully, expires at {}",
                            java.time.Instant.ofEpochMilli(newExpiresAt));

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
        } finally {
            tokenLock.unlock();
        }
    }

    /**
     * Ensures a refresh token is available, re-reading from Keychain/file if needed.
     * This handles the case where the daemon started without a refresh token
     * (e.g., config.json has apiKey but no refreshToken, and Keychain wasn't read).
     *
     * @return true if a refresh token is available
     */
    /**
     * Result of credential recovery attempt on 401.
     */
    enum CredentialRecovery {
        /** New access token loaded — retry request immediately without full refresh. */
        ACCESS_TOKEN_UPDATED,
        /** Refresh token available — proceed with OAuth token refresh flow. */
        REFRESH_AVAILABLE,
        /** No recovery possible — no fresh credentials found. */
        NO_RECOVERY
    }

    /**
     * Attempts to recover credentials from Keychain/file after a 401.
     * Always checks the credential store for fresher tokens.
     */
    CredentialRecovery recoverCredentials() {
        tokenLock.lock();
        try {
            return recoverCredentials0();
        } finally {
            tokenLock.unlock();
        }
    }

    /** Internal credential recovery — caller must hold {@link #tokenLock}. */
    private CredentialRecovery recoverCredentials0() {
        String previousAccessToken = this.accessToken;
        // Skip the credential store entirely for isolated clients so a
        // profile-scoped 401 cannot silently adopt another account's token.
        if (allowKeychainFallback) {
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
                    // Pick up expiry if available
                    if (cred.expiresAt() > 0) {
                        this.tokenExpiresAt = cred.expiresAt();
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to read credentials on 401 recovery: {}", e.getMessage());
            }
        }

        // If access token changed, we can retry immediately without full refresh
        if (!previousAccessToken.equals(this.accessToken)) {
            return CredentialRecovery.ACCESS_TOKEN_UPDATED;
        }
        // Otherwise, if we have a refresh token, we can do a full OAuth refresh
        if (this.refreshToken != null) {
            return CredentialRecovery.REFRESH_AVAILABLE;
        }
        return CredentialRecovery.NO_RECOVERY;
    }

    /**
     * Sets the configured model name (from daemon config).
     * This allows capabilities() to detect 4.6 models and return 1M context.
     */
    public void setConfiguredModel(String model) {
        this.configuredModel = model;
    }

    /** Package-private: current access token for testing. */
    String accessTokenForTest() { return accessToken; }

    /** Package-private: current refresh token for testing. */
    String refreshTokenForTest() { return refreshToken; }

    /** Package-private: current token expiry for testing. */
    long tokenExpiresAtForTest() { return tokenExpiresAt; }

    /** Package-private: set token expiry for testing. */
    void setTokenExpiresAtForTest(long expiresAt) { this.tokenExpiresAt = expiresAt; }

    /** Package-private: expose proactive refresh for direct testing. */
    void refreshProactivelyIfNeededForTest() { refreshProactivelyIfNeeded(); }

    /**
     * Writes refreshed credentials back to the original source (Keychain or file).
     * No-op when the client is isolated from Claude CLI's shared credential store,
     * so a profile-scoped OAuth refresh cannot overwrite another account's login.
     */
    private void writeBackCredentials(String newAccessToken, String newRefreshToken, long newExpiresAt) {
        if (!allowKeychainFallback) {
            log.debug("Skipping credential write-back: client is isolated from Claude CLI store");
            return;
        }
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

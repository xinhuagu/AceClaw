package dev.aceclaw.llm.anthropic;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.llm.LlmClient;
import dev.aceclaw.core.llm.LlmException;
import dev.aceclaw.core.llm.LlmRequest;
import dev.aceclaw.core.llm.LlmResponse;
import dev.aceclaw.core.llm.StreamSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
 */
public final class AnthropicClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicClient.class);

    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    private static final String MESSAGES_PATH = "/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String DEFAULT_MODEL = "claude-sonnet-4-5-20250929";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    /** OAuth token refresh endpoint. */
    private static final String TOKEN_REFRESH_URL = "https://console.anthropic.com/api/oauth/token";

    /** Claude Code's OAuth client ID. */
    private static final String OAUTH_CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e";

    private volatile String accessToken;
    private final String refreshToken;
    private final boolean isOAuth;
    private final String baseUrl;
    private final HttpClient httpClient;
    private final AnthropicMapper mapper;
    private final ObjectMapper jsonMapper;
    private final Duration requestTimeout;

    /**
     * Creates a client with a standard API key using default settings.
     *
     * @param apiKey the Anthropic API key
     */
    public AnthropicClient(String apiKey) {
        this(apiKey, null, DEFAULT_BASE_URL, DEFAULT_TIMEOUT);
    }

    /**
     * Creates a client with an OAuth access token and optional refresh token.
     *
     * @param accessToken  the access token (API key or OAuth token)
     * @param refreshToken the OAuth refresh token (null for standard API keys)
     */
    public AnthropicClient(String accessToken, String refreshToken) {
        this(accessToken, refreshToken, DEFAULT_BASE_URL, DEFAULT_TIMEOUT);
    }

    /**
     * Creates a client with full configuration.
     *
     * @param accessToken    the access token (API key or OAuth token)
     * @param refreshToken   the OAuth refresh token (null for standard API keys)
     * @param baseUrl        API base URL (without trailing slash)
     * @param requestTimeout HTTP request timeout
     */
    public AnthropicClient(String accessToken, String refreshToken, String baseUrl, Duration requestTimeout) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("API key / access token must not be null or blank");
        }
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.isOAuth = accessToken.startsWith("sk-ant-oat");
        this.baseUrl = baseUrl;
        this.requestTimeout = requestTimeout;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        this.jsonMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.mapper = new AnthropicMapper(jsonMapper);

        if (isOAuth) {
            log.info("Using OAuth authentication (token refresh {})",
                    refreshToken != null ? "enabled" : "disabled");
        }
    }

    @Override
    public LlmResponse sendMessage(LlmRequest request) throws LlmException {
        String requestBody = mapper.toRequestJson(request, false);
        log.debug("Sending non-streaming request to Anthropic: model={}", request.model());

        try {
            HttpRequest httpRequest = buildRequest(requestBody);
            HttpResponse<String> httpResponse = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofString());

            int statusCode = httpResponse.statusCode();
            String responseBody = httpResponse.body();

            // Retry once with refreshed token on 401
            if (statusCode == 401 && isOAuth && refreshToken != null) {
                log.info("OAuth token expired, attempting refresh...");
                if (refreshAccessToken()) {
                    httpRequest = buildRequest(requestBody);
                    httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                    statusCode = httpResponse.statusCode();
                    responseBody = httpResponse.body();
                }
            }

            if (statusCode != 200) {
                log.error("Anthropic API error: status={}, body={}", statusCode, responseBody);
                throw new LlmException(
                        "Anthropic API returned HTTP " + statusCode + ": " + responseBody,
                        statusCode);
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
    }

    @Override
    public StreamSession streamMessage(LlmRequest request) throws LlmException {
        String requestBody = mapper.toRequestJson(request, true);
        log.debug("Sending streaming request to Anthropic: model={}", request.model());

        try {
            HttpRequest httpRequest = buildRequest(requestBody);
            HttpResponse<Stream<String>> httpResponse = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofLines());

            int statusCode = httpResponse.statusCode();

            // Retry once with refreshed token on 401
            if (statusCode == 401 && isOAuth && refreshToken != null) {
                log.info("OAuth token expired, attempting refresh...");
                if (refreshAccessToken()) {
                    httpRequest = buildRequest(requestBody);
                    httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines());
                    statusCode = httpResponse.statusCode();
                }
            }

            if (statusCode != 200) {
                String errorBody = httpResponse.body()
                        .reduce("", (a, b) -> a + b);
                log.error("Anthropic API stream error: status={}, body={}", statusCode, errorBody);
                throw new LlmException(
                        "Anthropic API returned HTTP " + statusCode + ": " + errorBody,
                        statusCode);
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
    }

    @Override
    public String provider() {
        return "anthropic";
    }

    @Override
    public String defaultModel() {
        return DEFAULT_MODEL;
    }

    private HttpRequest buildRequest(String body) {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + MESSAGES_PATH))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("anthropic-version", ANTHROPIC_VERSION);

        if (isOAuth) {
            // OAuth tokens require Bearer auth + beta flags to be accepted
            builder.header("Authorization", "Bearer " + accessToken)
                    .header("anthropic-beta",
                            "claude-code-20250219,oauth-2025-04-20,fine-grained-tool-streaming-2025-05-14,interleaved-thinking-2025-05-14")
                    .header("user-agent", "claude-cli/2.1.2 (external, cli)")
                    .header("x-app", "cli");
        } else {
            builder.header("x-api-key", accessToken)
                    .header("anthropic-beta", "interleaved-thinking-2025-05-14");
        }

        return builder
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    /**
     * Refreshes the OAuth access token using the refresh token.
     *
     * @return true if refresh succeeded
     */
    private boolean refreshAccessToken() {
        try {
            var bodyNode = jsonMapper.createObjectNode();
            bodyNode.put("grant_type", "refresh_token");
            bodyNode.put("refresh_token", refreshToken);
            bodyNode.put("client_id", OAUTH_CLIENT_ID);

            HttpRequest refreshRequest = HttpRequest.newBuilder()
                    .uri(URI.create(TOKEN_REFRESH_URL))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(bodyNode)))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    refreshRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode tokenResponse = jsonMapper.readTree(response.body());
                String newToken = tokenResponse.path("access_token").asText(null);
                if (newToken != null && !newToken.isBlank()) {
                    this.accessToken = newToken;
                    log.info("OAuth token refreshed successfully");
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
}

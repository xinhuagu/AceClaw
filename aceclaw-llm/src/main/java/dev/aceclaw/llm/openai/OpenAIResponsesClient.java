package dev.aceclaw.llm.openai;

import com.fasterxml.jackson.databind.DeserializationFeature;
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
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * LLM client for the OpenAI Responses API ({@code /responses} endpoint).
 *
 * <p>Used for models that only support the Responses API, such as GitHub Copilot's
 * Codex models ({@code gpt-5.2-codex}, {@code gpt-5.1-codex}, etc.).
 *
 * <p>Follows the same retry and HTTP patterns as {@link OpenAICompatClient} but
 * uses {@link OpenAIResponsesMapper} and {@link OpenAIResponsesStreamSession}
 * for the different wire format.
 */
public final class OpenAIResponsesClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAIResponsesClient.class);

    private static final String DEFAULT_RESPONSES_PATH = "/responses";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);
    private static final int MAX_RETRIES = 3;
    private static final long MAX_BACKOFF_MS = 60_000L;

    private final Supplier<String> tokenSupplier;
    private final String baseUrl;
    private final String responsesPath;
    private final String providerName;
    private final String defaultModel;
    private final ProviderCapabilities providerCapabilities;
    private final Map<String, String> extraHeaders;
    private final HttpClient httpClient;
    private final OpenAIResponsesMapper mapper;
    private final Duration requestTimeout;

    /**
     * Creates a Responses API client with a dynamic token supplier.
     */
    public OpenAIResponsesClient(Supplier<String> tokenSupplier, String baseUrl, String responsesPath,
                                  String providerName, String defaultModel,
                                  ProviderCapabilities capabilities, Map<String, String> extraHeaders) {
        this(tokenSupplier, baseUrl, responsesPath, providerName, defaultModel,
                DEFAULT_TIMEOUT, capabilities, extraHeaders);
    }

    /**
     * Creates a Responses API client with full configuration.
     */
    public OpenAIResponsesClient(Supplier<String> tokenSupplier, String baseUrl, String responsesPath,
                                  String providerName, String defaultModel, Duration requestTimeout,
                                  ProviderCapabilities capabilities, Map<String, String> extraHeaders) {
        this.tokenSupplier = tokenSupplier;
        this.baseUrl = baseUrl != null ? baseUrl.replaceAll("/+$", "") : "";
        this.responsesPath = responsesPath != null ? responsesPath : DEFAULT_RESPONSES_PATH;
        this.providerName = providerName;
        this.defaultModel = defaultModel;
        this.requestTimeout = requestTimeout;
        this.providerCapabilities = capabilities;
        this.extraHeaders = extraHeaders != null ? extraHeaders : Map.of();

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        var jsonMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.mapper = new OpenAIResponsesMapper(jsonMapper);

        log.info("OpenAI Responses API client created: provider={}, baseUrl={}, path={}, defaultModel={}",
                providerName, this.baseUrl, this.responsesPath, defaultModel);
    }

    @Override
    public LlmResponse sendMessage(LlmRequest request) throws LlmException {
        String requestBody = mapper.toRequestJson(
                request,
                false,
                shouldIncludeMaxOutputTokens(),
                shouldIncludeTemperature(),
                shouldIncludeStoreFalse());
        log.debug("Sending non-streaming Responses API request to {}: model={}", providerName, request.model());

        return executeWithRetry(() -> {
            try {
                HttpRequest httpRequest = buildRequest(requestBody);
                HttpResponse<String> httpResponse = httpClient.send(
                        httpRequest, HttpResponse.BodyHandlers.ofString());

                int statusCode = httpResponse.statusCode();
                String responseBody = httpResponse.body();

                if (statusCode != 200) {
                    log.error("{} Responses API error: status={}, body={}", providerName, statusCode, responseBody);
                    long retryAfter = parseRetryAfter(httpResponse);
                    throw new LlmException(
                            providerName + " Responses API returned HTTP " + statusCode + ": " + responseBody,
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
                throw new LlmException("Failed to send message to " + providerName, e);
            }
        });
    }

    @Override
    public StreamSession streamMessage(LlmRequest request) throws LlmException {
        String requestBody = mapper.toRequestJson(
                request,
                true,
                shouldIncludeMaxOutputTokens(),
                shouldIncludeTemperature(),
                shouldIncludeStoreFalse());
        log.debug("Sending streaming Responses API request to {}: model={}", providerName, request.model());

        return executeWithRetry(() -> {
            try {
                HttpRequest httpRequest = buildRequest(requestBody);
                HttpResponse<Stream<String>> httpResponse = httpClient.send(
                        httpRequest, HttpResponse.BodyHandlers.ofLines());

                int statusCode = httpResponse.statusCode();

                if (statusCode != 200) {
                    String errorBody = httpResponse.body().limit(1000).reduce("", (a, b) -> a + b);
                    log.error("{} Responses API stream error: status={}, body={}", providerName, statusCode, errorBody);
                    long retryAfter = parseRetryAfter(httpResponse);
                    throw new LlmException(
                            providerName + " Responses API returned HTTP " + statusCode + ": " + errorBody,
                            statusCode, retryAfter);
                }

                return new OpenAIResponsesStreamSession(httpResponse, mapper);
            } catch (LlmException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException("Stream request interrupted", e);
            } catch (Exception e) {
                throw new LlmException("Failed to start streaming from " + providerName, e);
            }
        });
    }

    @Override
    public String provider() {
        return providerName;
    }

    @Override
    public String defaultModel() {
        return defaultModel;
    }

    @Override
    public ProviderCapabilities capabilities() {
        return providerCapabilities;
    }

    private HttpRequest buildRequest(String body) {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + responsesPath))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json");

        String token = tokenSupplier != null ? tokenSupplier.get() : null;
        if (token != null && !token.isBlank() && !"not-configured".equals(token)) {
            builder.header("Authorization", "Bearer " + token);
        }

        for (var entry : extraHeaders.entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }

        return builder
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private boolean shouldIncludeMaxOutputTokens() {
        return !"openai-codex".equals(providerName);
    }

    private boolean shouldIncludeTemperature() {
        return !"openai-codex".equals(providerName);
    }

    private boolean shouldIncludeStoreFalse() {
        return "openai-codex".equals(providerName);
    }

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

    private static long calculateBackoff(int attempt, LlmException e) {
        if (e.retryAfterSeconds() > 0) {
            return Math.min(e.retryAfterSeconds() * 1000L, MAX_BACKOFF_MS);
        }
        long baseMs = 1000L * (1L << attempt);
        double jitterFactor = 1.0 + 0.2 * (ThreadLocalRandom.current().nextDouble() * 2 - 1);
        return Math.min((long) (baseMs * jitterFactor), MAX_BACKOFF_MS);
    }

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
}

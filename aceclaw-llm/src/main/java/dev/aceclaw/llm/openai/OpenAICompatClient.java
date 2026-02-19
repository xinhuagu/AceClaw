package dev.aceclaw.llm.openai;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.llm.LlmClient;
import dev.aceclaw.core.llm.LlmException;
import dev.aceclaw.core.llm.LlmRequest;
import dev.aceclaw.core.llm.LlmResponse;
import dev.aceclaw.core.llm.ProviderCapabilities;
import dev.aceclaw.core.llm.StreamSession;
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
 * OpenAI-compatible LLM client.
 *
 * <p>Works with any provider that implements the OpenAI Chat Completions API:
 * OpenAI, GitHub Copilot, Groq, Together, Mistral, Ollama, etc.
 *
 * <p>Supports both synchronous ({@link #sendMessage}) and streaming
 * ({@link #streamMessage}) interactions. Includes retry logic with exponential
 * backoff for transient failures (429, 5xx).
 */
public final class OpenAICompatClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAICompatClient.class);

    private static final String DEFAULT_COMPLETIONS_PATH = "/v1/chat/completions";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    /** Maximum number of retry attempts for transient failures. */
    private static final int MAX_RETRIES = 3;

    /** Maximum backoff delay in milliseconds. */
    private static final long MAX_BACKOFF_MS = 60_000L;

    private final Supplier<String> tokenSupplier;
    private final String baseUrl;
    private final String completionsPath;
    private final String providerName;
    private final String defaultModel;
    private final ProviderCapabilities providerCapabilities;
    private final Map<String, String> extraHeaders;
    private final HttpClient httpClient;
    private final OpenAIMapper mapper;
    private final Duration requestTimeout;

    /**
     * Creates an OpenAI-compatible client with a static API key.
     *
     * @param apiKey       the API key (sent as Bearer token)
     * @param baseUrl      API base URL without trailing slash (e.g. "https://api.openai.com")
     * @param providerName provider name for identification (e.g. "openai", "groq")
     * @param defaultModel default model identifier
     */
    public OpenAICompatClient(String apiKey, String baseUrl, String providerName, String defaultModel) {
        this(() -> apiKey, baseUrl, DEFAULT_COMPLETIONS_PATH, providerName, defaultModel,
                DEFAULT_TIMEOUT, ProviderCapabilities.OPENAI_COMPAT, Map.of());
    }

    /**
     * Creates an OpenAI-compatible client with a static API key and custom capabilities.
     *
     * @param apiKey       the API key (sent as Bearer token)
     * @param baseUrl      API base URL without trailing slash
     * @param providerName provider name for identification
     * @param defaultModel default model identifier
     * @param capabilities provider-specific capabilities
     */
    public OpenAICompatClient(String apiKey, String baseUrl, String providerName,
                              String defaultModel, ProviderCapabilities capabilities) {
        this(() -> apiKey, baseUrl, DEFAULT_COMPLETIONS_PATH, providerName, defaultModel,
                DEFAULT_TIMEOUT, capabilities, Map.of());
    }

    /**
     * Creates an OpenAI-compatible client with a dynamic token supplier.
     * Used for providers that require token exchange (e.g. GitHub Copilot).
     *
     * @param tokenSupplier  supplier that returns a valid Bearer token on each call
     * @param baseUrl        API base URL without trailing slash
     * @param completionsPath path to the chat completions endpoint
     * @param providerName   provider name for identification
     * @param defaultModel   default model identifier
     * @param capabilities   provider-specific capabilities
     * @param extraHeaders   additional headers to include in every request
     */
    public OpenAICompatClient(Supplier<String> tokenSupplier, String baseUrl, String completionsPath,
                              String providerName, String defaultModel,
                              ProviderCapabilities capabilities, Map<String, String> extraHeaders) {
        this(tokenSupplier, baseUrl, completionsPath, providerName, defaultModel,
                DEFAULT_TIMEOUT, capabilities, extraHeaders);
    }

    /**
     * Creates an OpenAI-compatible client with full configuration.
     */
    public OpenAICompatClient(Supplier<String> tokenSupplier, String baseUrl, String completionsPath,
                              String providerName, String defaultModel, Duration requestTimeout,
                              ProviderCapabilities capabilities, Map<String, String> extraHeaders) {
        this.tokenSupplier = tokenSupplier;
        this.baseUrl = baseUrl != null ? baseUrl.replaceAll("/+$", "") : "";
        this.completionsPath = completionsPath != null ? completionsPath : DEFAULT_COMPLETIONS_PATH;
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
        this.mapper = new OpenAIMapper(jsonMapper);

        log.info("OpenAI-compatible client created: provider={}, baseUrl={}, path={}, defaultModel={}",
                providerName, this.baseUrl, this.completionsPath, defaultModel);
    }

    @Override
    public LlmResponse sendMessage(LlmRequest request) throws LlmException {
        String requestBody = mapper.toRequestJson(request, false);
        log.debug("Sending non-streaming request to {}: model={}", providerName, request.model());

        return executeWithRetry(() -> {
            try {
                HttpRequest httpRequest = buildRequest(requestBody);
                HttpResponse<String> httpResponse = httpClient.send(
                        httpRequest, HttpResponse.BodyHandlers.ofString());

                int statusCode = httpResponse.statusCode();
                String responseBody = httpResponse.body();

                if (statusCode != 200) {
                    log.error("{} API error: status={}, body={}", providerName, statusCode, responseBody);
                    long retryAfter = parseRetryAfter(httpResponse);
                    throw new LlmException(
                            providerName + " API returned HTTP " + statusCode + ": " + responseBody,
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
        String requestBody = mapper.toRequestJson(request, true);
        log.debug("Sending streaming request to {}: model={}", providerName, request.model());

        return executeWithRetry(() -> {
            try {
                HttpRequest httpRequest = buildRequest(requestBody);
                HttpResponse<Stream<String>> httpResponse = httpClient.send(
                        httpRequest, HttpResponse.BodyHandlers.ofLines());

                int statusCode = httpResponse.statusCode();

                if (statusCode != 200) {
                    String errorBody = httpResponse.body()
                            .reduce("", (a, b) -> a + b);
                    log.error("{} API stream error: status={}, body={}", providerName, statusCode, errorBody);
                    long retryAfter = parseRetryAfter(httpResponse);
                    throw new LlmException(
                            providerName + " API returned HTTP " + statusCode + ": " + errorBody,
                            statusCode, retryAfter);
                }

                return new OpenAIStreamSession(httpResponse, mapper);
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
    public java.util.List<String> listModels() {
        try {
            var builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/models"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .GET();

            String token = tokenSupplier != null ? tokenSupplier.get() : null;
            if (token != null && !token.isBlank() && !"not-configured".equals(token)) {
                builder.header("Authorization", "Bearer " + token);
            }
            for (var entry : extraHeaders.entrySet()) {
                builder.header(entry.getKey(), entry.getValue());
            }

            HttpResponse<String> response = httpClient.send(
                    builder.build(), HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.debug("Failed to list models from {}: HTTP {}", providerName, response.statusCode());
                return java.util.List.of();
            }

            var jsonMapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var root = jsonMapper.readTree(response.body());
            var data = root.get("data");
            if (data == null || !data.isArray()) {
                return java.util.List.of();
            }

            var models = new java.util.ArrayList<String>();
            for (var item : data) {
                var id = item.path("id").asText(null);
                if (id != null && !id.isBlank()) {
                    models.add(id);
                }
            }
            java.util.Collections.sort(models);
            return models;
        } catch (Exception e) {
            log.debug("Failed to list models from {}: {}", providerName, e.getMessage());
            return java.util.List.of();
        }
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
                .uri(URI.create(baseUrl + completionsPath))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json");

        // Resolve token dynamically (supports static keys and token exchange providers)
        String token = tokenSupplier != null ? tokenSupplier.get() : null;
        if (token != null && !token.isBlank() && !"not-configured".equals(token)) {
            builder.header("Authorization", "Bearer " + token);
        }

        // Apply provider-specific extra headers (e.g. Copilot integration headers)
        for (var entry : extraHeaders.entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }

        return builder
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    // -- Retry logic (same pattern as AnthropicClient) --

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
                    Thread.sleep(backoffMs);
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
        long baseMs = 1000L * (1L << attempt); // 1s, 2s, 4s
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

package dev.aceclaw.llm;

import dev.aceclaw.core.llm.LlmClient;
import dev.aceclaw.core.llm.ProviderCapabilities;
import dev.aceclaw.llm.anthropic.AnthropicClient;
import dev.aceclaw.llm.openai.CopilotRoutingClient;
import dev.aceclaw.llm.openai.CopilotTokenProvider;
import dev.aceclaw.llm.openai.OpenAiCodexTokenProvider;
import dev.aceclaw.llm.openai.OpenAICompatClient;
import dev.aceclaw.llm.openai.OpenAIRoutingClient;
import dev.aceclaw.llm.openai.OpenAIResponsesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Factory for creating {@link LlmClient} instances based on provider name.
 *
 * <p>Supports Anthropic Claude (native) and any OpenAI-compatible API
 * (OpenAI, Groq, Together, Mistral, GitHub Copilot, Ollama, etc.).
 */
public final class LlmClientFactory {

    private static final Logger log = LoggerFactory.getLogger(LlmClientFactory.class);

    /** Default base URLs for known providers (without trailing slash). */
    private static final Map<String, String> DEFAULT_BASE_URLS = Map.of(
            "openai", "https://api.openai.com",
            "openai-codex", "https://chatgpt.com/backend-api/codex",
            "groq", "https://api.groq.com/openai",
            "together", "https://api.together.xyz",
            "mistral", "https://api.mistral.ai",
            "copilot", "https://api.githubcopilot.com",
            "ollama", "http://localhost:11434"
    );

    /** Default model identifiers for known providers. */
    private static final Map<String, String> DEFAULT_MODELS = Map.of(
            "openai", "gpt-4o",
            "openai-codex", "gpt-5-codex",
            "groq", "llama-3.3-70b-versatile",
            "together", "meta-llama/Llama-3.3-70B-Instruct-Turbo",
            "mistral", "mistral-large-latest",
            "copilot", "gpt-5.2-codex",
            "ollama", "qwen3:4b"
    );

    /** Providers that support image input. */
    private static final Map<String, ProviderCapabilities> PROVIDER_CAPABILITIES = Map.of(
            "openai", ProviderCapabilities.OPENAI,
            "openai-codex", ProviderCapabilities.CODEX,
            "groq", ProviderCapabilities.OPENAI_COMPAT,
            "together", ProviderCapabilities.OPENAI_COMPAT,
            "mistral", ProviderCapabilities.OPENAI_COMPAT,
            "copilot", ProviderCapabilities.OPENAI,
            "ollama", ProviderCapabilities.OPENAI_COMPAT
    );

    private LlmClientFactory() {}

    /**
     * Returns the hardcoded default model for a given provider.
     * Falls back to {@code "claude-sonnet-4-5-20250929"} for Anthropic and unknown providers.
     */
    public static String getDefaultModel(String provider) {
        if (provider == null || "anthropic".equals(provider)) {
            return "claude-sonnet-4-5-20250929";
        }
        return DEFAULT_MODELS.getOrDefault(provider, "gpt-4o");
    }

    /**
     * Creates an LLM client for the given provider.
     *
     * @param provider     provider name (e.g. "anthropic", "openai", "groq", "ollama")
     * @param apiKey       the API key or access token
     * @param refreshToken OAuth refresh token (Anthropic only, may be null)
     * @param baseUrl      custom base URL override (null = use provider default)
     * @param model        model to use (null = use provider default from {@link #getDefaultModel(String)})
     * @return a configured LlmClient instance
     * @throws IllegalArgumentException if the provider is unknown
     */
    public static LlmClient create(String provider, String apiKey,
                                    String refreshToken, String baseUrl,
                                    String model) {
        if (provider == null || provider.isBlank()) {
            provider = "anthropic";
        }

        log.info("Creating LLM client: provider={}, model={}, baseUrl={}", provider,
                model != null ? model : "(default)",
                baseUrl != null ? baseUrl : "(default)");

        return switch (provider) {
            case "anthropic" -> createAnthropicClient(apiKey, refreshToken, baseUrl);
            case "copilot" -> createCopilotClient(apiKey, baseUrl, model);
            case "openai" -> createOpenAiClient(apiKey, baseUrl, model);
            case "openai-codex" -> createOpenAiCodexClient(apiKey, baseUrl, model);
            case "groq", "together", "mistral", "ollama" -> {
                String resolvedBaseUrl = baseUrl != null ? baseUrl : DEFAULT_BASE_URLS.get(provider);
                String resolvedModel = model != null ? model : DEFAULT_MODELS.getOrDefault(provider, "gpt-4o");
                ProviderCapabilities caps = PROVIDER_CAPABILITIES.getOrDefault(
                        provider, ProviderCapabilities.OPENAI_COMPAT);
                yield new OpenAICompatClient(apiKey, resolvedBaseUrl, provider, resolvedModel, caps);
            }
            default -> throw new IllegalArgumentException(
                    "Unknown provider: " + provider
                            + ". Supported: anthropic, openai, openai-codex, groq, together, mistral, copilot, ollama");
        };
    }

    /** Copilot-specific headers required by the GitHub Copilot API. */
    private static final Map<String, String> COPILOT_API_HEADERS = Map.of(
            "copilot-integration-id", "vscode-chat",
            "editor-version", "vscode/1.95.0",
            "editor-plugin-version", "copilot-chat/0.26.7",
            "User-Agent", "GitHubCopilotChat/0.26.7",
            "openai-intent", "conversation-panel",
            "x-github-api-version", "2025-04-01"
    );

    /** Models known to work on GitHub Copilot. If the configured model is not in this set, fall back to default. */
    private static final java.util.Set<String> COPILOT_KNOWN_MODELS = java.util.Set.of(
            "claude-sonnet-4.5", "claude-sonnet-4", "claude-opus-4", "claude-haiku-3.5",
            "gpt-4o", "gpt-4o-mini", "gpt-4.1", "gpt-4.1-mini", "gpt-4.1-nano",
            "gpt-5.2-codex", "o4-mini", "o3-mini"
    );

    private static LlmClient createCopilotClient(String githubToken, String baseUrl, String model) {
        var tokenProvider = new CopilotTokenProvider(githubToken);
        String resolvedBaseUrl = baseUrl != null ? baseUrl : DEFAULT_BASE_URLS.get("copilot");
        String defaultModel = DEFAULT_MODELS.getOrDefault("copilot", "claude-sonnet-4.5");
        String resolvedModel;
        if (model != null && COPILOT_KNOWN_MODELS.contains(model)) {
            resolvedModel = model;
        } else {
            resolvedModel = defaultModel;
            if (model != null) {
                log.warn("Model '{}' is not supported on Copilot, using default '{}'", model, defaultModel);
            }
        }

        // Always create both clients so runtime model switching works.
        // The routing client dispatches to the correct endpoint based on model name:
        // - Codex models (e.g. gpt-5.2-codex) → Responses API (/responses)
        // - All other models → Chat Completions API (/chat/completions)
        var chatCaps = ProviderCapabilities.forCopilotModel(resolvedModel);
        var chatClient = new OpenAICompatClient(
                tokenProvider, resolvedBaseUrl, "/chat/completions",
                "copilot", resolvedModel, chatCaps,
                COPILOT_API_HEADERS);
        var responsesClient = new OpenAIResponsesClient(
                tokenProvider, resolvedBaseUrl, "/responses",
                "copilot", resolvedModel, ProviderCapabilities.CODEX,
                COPILOT_API_HEADERS);

        return new CopilotRoutingClient(chatClient, responsesClient, resolvedModel);
    }

    private static LlmClient createOpenAiClient(String apiKey, String baseUrl, String model) {
        String resolvedBaseUrl = baseUrl != null ? baseUrl : DEFAULT_BASE_URLS.get("openai");
        String resolvedModel = model != null ? model : DEFAULT_MODELS.getOrDefault("openai", "gpt-4o");

        var chatClient = new OpenAICompatClient(
                () -> apiKey, resolvedBaseUrl, "/v1/chat/completions",
                "openai", resolvedModel, ProviderCapabilities.OPENAI, Map.of());
        var responsesClient = new OpenAIResponsesClient(
                () -> apiKey, resolvedBaseUrl, "/v1/responses",
                "openai", resolvedModel, ProviderCapabilities.CODEX, Map.of());

        return new OpenAIRoutingClient(chatClient, responsesClient, resolvedModel);
    }

    private static LlmClient createOpenAiCodexClient(String apiKey, String baseUrl, String model) {
        var tokenProvider = new OpenAiCodexTokenProvider(apiKey);
        String resolvedBaseUrl = baseUrl != null ? baseUrl : DEFAULT_BASE_URLS.get("openai-codex");
        String resolvedModel = model != null ? model : DEFAULT_MODELS.getOrDefault("openai-codex", "gpt-5-codex");

        var chatClient = new OpenAICompatClient(
                tokenProvider, resolvedBaseUrl, "/chat/completions",
                "openai-codex", resolvedModel, ProviderCapabilities.OPENAI, Map.of());
        var responsesClient = new OpenAIResponsesClient(
                tokenProvider, resolvedBaseUrl, "/responses",
                "openai-codex", resolvedModel, ProviderCapabilities.CODEX, Map.of());

        return new OpenAIRoutingClient(chatClient, responsesClient, resolvedModel, "openai-codex");
    }

    /**
     * Creates an Anthropic client for the given provider with beta configuration.
     *
     * @param apiKey       the API key or OAuth access token
     * @param refreshToken OAuth refresh token (may be null)
     * @param baseUrl      custom base URL override (null = default)
     * @param context1m    whether to enable 1M context window beta
     * @param extraBetas   additional beta flags from config (may be null)
     * @return configured AnthropicClient
     */
    public static LlmClient createAnthropicClient(String apiKey, String refreshToken, String baseUrl,
                                                    boolean context1m, java.util.List<String> extraBetas) {
        String resolvedBaseUrl = baseUrl != null ? baseUrl : "https://api.anthropic.com";
        return new AnthropicClient(apiKey, refreshToken, resolvedBaseUrl,
                java.time.Duration.ofSeconds(120), context1m, extraBetas);
    }

    private static LlmClient createAnthropicClient(String apiKey, String refreshToken, String baseUrl) {
        return createAnthropicClient(apiKey, refreshToken, baseUrl, false, null);
    }
}

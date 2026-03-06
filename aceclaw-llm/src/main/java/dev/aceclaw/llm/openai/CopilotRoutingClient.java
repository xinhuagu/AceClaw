package dev.aceclaw.llm.openai;

import dev.aceclaw.core.llm.LlmClient;
import dev.aceclaw.core.llm.LlmException;
import dev.aceclaw.core.llm.LlmRequest;
import dev.aceclaw.core.llm.LlmResponse;
import dev.aceclaw.core.llm.ProviderCapabilities;
import dev.aceclaw.core.llm.StreamSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Routing client for GitHub Copilot that dispatches requests to the correct
 * underlying client based on the model name.
 *
 * <p>Codex models (containing "codex" in their name) use the Responses API
 * ({@code /responses} endpoint) via {@link OpenAIResponsesClient}, while all
 * other models use the Chat Completions API ({@code /chat/completions})
 * via {@link OpenAICompatClient}.
 *
 * <p>This wrapper solves the runtime model switching problem: when the user
 * changes the model via {@code /model}, only the model string in the request
 * changes. Without this wrapper, the underlying HTTP endpoint would remain
 * fixed to whichever client was created at daemon startup.
 */
public final class CopilotRoutingClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(CopilotRoutingClient.class);

    private final OpenAICompatClient chatClient;
    private final OpenAIResponsesClient responsesClient;
    private final String defaultModel;

    /**
     * Creates a routing client that dispatches to the appropriate endpoint.
     *
     * @param chatClient      client for Chat Completions API (non-codex models)
     * @param responsesClient client for Responses API (codex models)
     * @param defaultModel    the default model identifier
     */
    public CopilotRoutingClient(OpenAICompatClient chatClient,
                                 OpenAIResponsesClient responsesClient,
                                 String defaultModel) {
        this.chatClient = chatClient;
        this.responsesClient = responsesClient;
        this.defaultModel = defaultModel;
        log.info("Copilot routing client created: defaultModel={}, chatEndpoint=chat/completions, responsesEndpoint=/responses",
                defaultModel);
    }

    @Override
    public LlmResponse sendMessage(LlmRequest request) throws LlmException {
        return selectClient(request.model()).sendMessage(request);
    }

    @Override
    public StreamSession streamMessage(LlmRequest request) throws LlmException {
        return selectClient(request.model()).streamMessage(request);
    }

    @Override
    public List<String> listModels() {
        // Merge models from both clients, deduplicating
        var models = new LinkedHashSet<String>();
        models.addAll(chatClient.listModels());
        models.addAll(responsesClient.listModels());
        var sorted = new ArrayList<>(models);
        sorted.sort(String::compareTo);
        return sorted;
    }

    @Override
    public String provider() {
        return "copilot";
    }

    @Override
    public String defaultModel() {
        return defaultModel;
    }

    @Override
    public ProviderCapabilities capabilities() {
        return ProviderCapabilities.forCopilotModel(defaultModel);
    }

    private LlmClient selectClient(String model) {
        if (isCodexModel(model)) {
            log.debug("Routing model '{}' to Responses API", model);
            return responsesClient;
        }
        log.debug("Routing model '{}' to Chat Completions API", model);
        return chatClient;
    }

    /**
     * Returns whether a model name indicates a Codex model that requires
     * the Responses API endpoint.
     */
    static boolean isCodexModel(String model) {
        return model != null && model.toLowerCase().contains("codex");
    }
}

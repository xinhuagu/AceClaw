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
 * Routing client for OpenAI that dispatches to chat-completions or responses
 * endpoint based on model name.
 */
public final class OpenAIRoutingClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAIRoutingClient.class);

    private final OpenAICompatClient chatClient;
    private final OpenAIResponsesClient responsesClient;
    private final String defaultModel;

    public OpenAIRoutingClient(OpenAICompatClient chatClient,
                               OpenAIResponsesClient responsesClient,
                               String defaultModel) {
        this.chatClient = chatClient;
        this.responsesClient = responsesClient;
        this.defaultModel = defaultModel;
        log.info("OpenAI routing client created: defaultModel={}, chatEndpoint=/v1/chat/completions, responsesEndpoint=/v1/responses",
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
        var models = new LinkedHashSet<String>();
        models.addAll(chatClient.listModels());
        models.addAll(responsesClient.listModels());
        var sorted = new ArrayList<>(models);
        sorted.sort(String::compareTo);
        return sorted;
    }

    @Override
    public String provider() {
        return "openai";
    }

    @Override
    public String defaultModel() {
        return defaultModel;
    }

    @Override
    public ProviderCapabilities capabilities() {
        return isCodexModel(defaultModel) ? ProviderCapabilities.CODEX : ProviderCapabilities.OPENAI;
    }

    private LlmClient selectClient(String model) {
        if (isCodexModel(model)) {
            log.debug("Routing model '{}' to Responses API", model);
            return responsesClient;
        }
        log.debug("Routing model '{}' to Chat Completions API", model);
        return chatClient;
    }

    static boolean isCodexModel(String model) {
        return model != null && model.toLowerCase().contains("codex");
    }
}

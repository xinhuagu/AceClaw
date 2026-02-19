package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.llm.LlmClient;

/**
 * Shared helper for registering model-related RPC handlers.
 * Used by both {@link AceClawDaemon} and tests to keep wiring in sync.
 */
public final class ModelRpcHelper {

    private ModelRpcHelper() {}

    /**
     * Registers the {@code model.list} RPC handler.
     */
    public static void registerModelList(RequestRouter router, ObjectMapper mapper,
                                         StreamingAgentHandler handler, LlmClient llmClient,
                                         String providerName) {
        router.register("model.list", params -> {
            var result = mapper.createObjectNode();
            var sessionId = params != null && params.has("sessionId") ? params.get("sessionId").asText() : null;
            result.put("currentModel", sessionId != null
                    ? handler.getEffectiveModel(sessionId)
                    : handler.getEffectiveModel());
            result.put("defaultModel", handler.getDefaultModel());
            result.put("provider", providerName);
            var modelsArray = mapper.createArrayNode();
            for (var m : llmClient.listModels()) {
                modelsArray.add(m);
            }
            result.set("models", modelsArray);
            return result;
        });
    }

    /**
     * Registers the {@code model.switch} RPC handler.
     */
    public static void registerModelSwitch(RequestRouter router, ObjectMapper mapper,
                                           StreamingAgentHandler handler, LlmClient llmClient) {
        router.register("model.switch", params -> {
            if (params == null || !params.has("model") || params.get("model").isNull()) {
                throw new IllegalArgumentException("Missing required parameter: model");
            }
            var newModel = params.get("model").asText();

            // Validate model exists if provider supports listing
            var availableModels = llmClient.listModels();
            if (!availableModels.isEmpty() && !availableModels.contains(newModel)) {
                throw new IllegalArgumentException("Unknown model: " + newModel +
                        ". Available: " + String.join(", ", availableModels));
            }

            var sessionId = params.has("sessionId") ? params.get("sessionId").asText() : "default";
            handler.setModelOverride(sessionId, newModel);
            router.setModelName(newModel);
            var result = mapper.createObjectNode();
            result.put("model", newModel);
            result.put("switched", true);
            return result;
        });
    }

    /**
     * Registers the {@code model.switch} RPC handler without model validation.
     * Used in tests where the LLM client may not support listing.
     */
    public static void registerModelSwitch(RequestRouter router, ObjectMapper mapper,
                                           StreamingAgentHandler handler) {
        router.register("model.switch", params -> {
            if (params == null || !params.has("model") || params.get("model").isNull()) {
                throw new IllegalArgumentException("Missing required parameter: model");
            }
            var newModel = params.get("model").asText();
            var sessionId = params.has("sessionId") ? params.get("sessionId").asText() : "default";
            handler.setModelOverride(sessionId, newModel);
            router.setModelName(newModel);
            var result = mapper.createObjectNode();
            result.put("model", newModel);
            result.put("switched", true);
            return result;
        });
    }
}

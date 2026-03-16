package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Objects;
import java.util.function.IntSupplier;
/**
 * Shared helper for registering context-observability RPC handlers.
 */
public final class ContextRpcHelper {

    private ContextRpcHelper() {}

    public static void registerContextInspect(RequestRouter router, ObjectMapper mapper,
                                              StreamingAgentHandler handler,
                                              IntSupplier contextWindowSupplier) {
        Objects.requireNonNull(router, "router must not be null");
        Objects.requireNonNull(mapper, "mapper must not be null");
        Objects.requireNonNull(handler, "handler must not be null");
        Objects.requireNonNull(contextWindowSupplier, "contextWindowSupplier must not be null");
        router.register("context.inspect", params -> {
            if (params == null || !params.has("sessionId")) {
                throw new IllegalArgumentException("Missing required parameter: sessionId");
            }
            String sessionId = params.get("sessionId").asText();
            String queryHint = params.has("queryHint") ? params.get("queryHint").asText("") : "";
            String detailKey = params.has("detailKey") ? params.get("detailKey").asText("") : "";
            int contextWindowTokens = Math.max(0, contextWindowSupplier.getAsInt());

            var inspection = handler.inspectContext(sessionId, queryHint);
            var result = mapper.createObjectNode();
            result.put("sessionId", sessionId);
            result.put("totalChars", inspection.totalChars());
            result.put("estimatedTokens", inspection.estimatedTokens());
            result.put("contextWindowTokens", Math.max(0, contextWindowTokens));
            if (contextWindowTokens > 0) {
                double sharePct = (double) inspection.estimatedTokens() / contextWindowTokens * 100.0;
                result.put("systemPromptSharePct", sharePct);
            }

            var budgetNode = mapper.createObjectNode();
            budgetNode.put("maxPerTierChars", inspection.budget().maxPerTierChars());
            budgetNode.put("maxTotalChars", inspection.budget().maxTotalChars());
            budgetNode.put("budgetTokensEstimate",
                    (int) Math.ceil(inspection.budget().maxTotalChars() / 4.0));
            result.set("budget", budgetNode);

            var activePaths = mapper.createArrayNode();
            for (var path : inspection.activeFilePaths()) {
                activePaths.add(path);
            }
            result.set("activeFilePaths", activePaths);

            var injectedCandidateIds = mapper.createArrayNode();
            for (var candidateId : inspection.injectedCandidateIds()) {
                injectedCandidateIds.add(candidateId);
            }
            result.set("injectedCandidateIds", injectedCandidateIds);

            var truncated = mapper.createArrayNode();
            for (var key : inspection.truncatedSectionKeys()) {
                truncated.add(key);
            }
            result.set("truncatedSectionKeys", truncated);

            var sections = mapper.createArrayNode();
            SystemPromptLoader.ContextSection selected = null;
            for (var section : inspection.sections()) {
                var node = mapper.createObjectNode();
                node.put("key", section.key());
                node.put("sourceType", section.sourceType());
                node.put("priority", section.priority());
                node.put("protected", section.protectedSection());
                node.put("originalChars", section.originalChars());
                node.put("finalChars", section.finalChars());
                node.put("estimatedTokens", section.estimatedTokens());
                node.put("included", section.included());
                node.put("truncated", section.truncated());
                sections.add(node);
                if (!detailKey.isBlank() && detailKey.equals(section.key())) {
                    selected = section;
                }
            }
            result.set("sections", sections);

            if (selected != null) {
                var detail = mapper.createObjectNode();
                detail.put("key", selected.key());
                detail.put("priority", selected.priority());
                detail.put("protected", selected.protectedSection());
                detail.put("originalChars", selected.originalChars());
                detail.put("finalChars", selected.finalChars());
                detail.put("sourceType", selected.sourceType());
                detail.put("estimatedTokens", selected.estimatedTokens());
                detail.put("included", selected.included());
                detail.put("truncated", selected.truncated());
                detail.put("content", selected.content());
                result.set("detail", detail);
            }

            return result;
        });
    }
}

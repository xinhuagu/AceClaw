package dev.aceclaw.llm.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.aceclaw.core.llm.ContentBlock;
import dev.aceclaw.core.llm.LlmRequest;
import dev.aceclaw.core.llm.LlmResponse;
import dev.aceclaw.core.llm.Message;
import dev.aceclaw.core.llm.StopReason;
import dev.aceclaw.core.llm.ToolDefinition;
import dev.aceclaw.core.llm.Usage;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps between AceClaw LLM abstractions and the OpenAI Responses API JSON format.
 *
 * <p>The Responses API ({@code /responses}) differs from Chat Completions:
 * <ul>
 *   <li>System prompt uses {@code instructions} field instead of a system message</li>
 *   <li>Messages are sent as {@code input} array instead of {@code messages}</li>
 *   <li>Tool results use {@code type: "function_call_output"} instead of {@code role: "tool"}</li>
 *   <li>Output uses {@code output[]} with typed items instead of {@code choices[].message}</li>
 *   <li>Max tokens field is {@code max_output_tokens} instead of {@code max_completion_tokens}</li>
 * </ul>
 */
final class OpenAIResponsesMapper {

    private final ObjectMapper objectMapper;

    OpenAIResponsesMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // -- Request mapping --

    /**
     * Converts an {@link LlmRequest} to the OpenAI Responses API request JSON body.
     */
    String toRequestJson(LlmRequest request, boolean stream) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", request.model());
        root.put("max_output_tokens", request.maxTokens());

        if (request.temperature() >= 0.0) {
            root.put("temperature", request.temperature());
        }

        // System prompt as instructions field
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            root.put("instructions", request.systemPrompt());
        }

        // Build input array
        ArrayNode inputArray = root.putArray("input");
        for (Message message : request.messages()) {
            mapMessage(message, inputArray);
        }

        // Tools (flat format: name/description/parameters at top level, unlike chat completions)
        if (!request.tools().isEmpty()) {
            ArrayNode toolsArray = root.putArray("tools");
            for (ToolDefinition tool : request.tools()) {
                toolsArray.add(mapToolDefinition(tool));
            }
        }

        if (stream) {
            root.put("stream", true);
        }

        return root.toString();
    }

    private void mapMessage(Message message, ArrayNode inputArray) {
        switch (message) {
            case Message.UserMessage um -> mapUserMessage(um, inputArray);
            case Message.AssistantMessage am -> mapAssistantMessage(am, inputArray);
        }
    }

    private void mapUserMessage(Message.UserMessage um, ArrayNode inputArray) {
        var toolResults = new ArrayList<ContentBlock.ToolResult>();
        var textParts = new ArrayList<String>();

        for (ContentBlock block : um.content()) {
            switch (block) {
                case ContentBlock.ToolResult tr -> toolResults.add(tr);
                case ContentBlock.Text t -> textParts.add(t.text());
                case ContentBlock.Thinking ignored -> { }
                case ContentBlock.ToolUse ignored -> { }
            }
        }

        // Tool results become function_call_output items
        for (ContentBlock.ToolResult tr : toolResults) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("type", "function_call_output");
            item.put("call_id", tr.toolUseId());
            item.put("output", tr.content() != null ? tr.content() : "");
            inputArray.add(item);
        }

        // User text as regular input item
        if (!textParts.isEmpty()) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("role", "user");
            item.put("content", String.join("\n", textParts));
            inputArray.add(item);
        }
    }

    private void mapAssistantMessage(Message.AssistantMessage am, ArrayNode inputArray) {
        var toolUseCalls = new ArrayList<ContentBlock.ToolUse>();
        var textParts = new ArrayList<String>();

        for (ContentBlock block : am.content()) {
            switch (block) {
                case ContentBlock.ToolUse tu -> toolUseCalls.add(tu);
                case ContentBlock.Text t -> textParts.add(t.text());
                case ContentBlock.Thinking ignored -> { }
                case ContentBlock.ToolResult ignored -> { }
            }
        }

        // Assistant text as input item
        if (!textParts.isEmpty()) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("role", "assistant");
            item.put("content", String.join("\n", textParts));
            inputArray.add(item);
        }

        // Tool calls as function_call items
        for (ContentBlock.ToolUse tu : toolUseCalls) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("type", "function_call");
            item.put("call_id", tu.id());
            item.put("name", tu.name());
            String args = tu.inputJson();
            item.put("arguments", (args != null && !args.isBlank()) ? args : "{}");
            inputArray.add(item);
        }
    }

    private ObjectNode mapToolDefinition(ToolDefinition tool) {
        // Responses API uses a flat tool object (name/description/parameters at top level),
        // NOT the nested { type: "function", function: { name, ... } } format from Chat Completions.
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "function");
        node.put("name", tool.name());
        node.put("description", tool.description());
        node.set("parameters", tool.inputSchema());
        return node;
    }

    // -- Response mapping --

    /**
     * Parses the OpenAI Responses API response JSON into an {@link LlmResponse}.
     */
    LlmResponse toResponse(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        return toResponse(root);
    }

    /**
     * Parses the OpenAI Responses API response JSON node into an {@link LlmResponse}.
     */
    LlmResponse toResponse(JsonNode root) {
        String id = root.path("id").asText("");
        String model = root.path("model").asText("");

        JsonNode output = root.path("output");
        if (!output.isArray() || output.isEmpty()) {
            return new LlmResponse(id, model, List.of(), mapStatus(root.path("status").asText(null)), parseUsage(root.path("usage")));
        }

        List<ContentBlock> content = new ArrayList<>();

        for (JsonNode item : output) {
            String type = item.path("type").asText("");
            switch (type) {
                case "message" -> {
                    JsonNode contentArray = item.path("content");
                    if (contentArray.isArray()) {
                        for (JsonNode part : contentArray) {
                            String partType = part.path("type").asText("");
                            if ("output_text".equals(partType)) {
                                String text = part.path("text").asText("");
                                if (!text.isEmpty()) {
                                    content.add(new ContentBlock.Text(text));
                                }
                            }
                        }
                    }
                }
                case "function_call" -> {
                    String callId = item.path("call_id").asText("");
                    String name = item.path("name").asText("");
                    String arguments = item.path("arguments").asText("{}");
                    content.add(new ContentBlock.ToolUse(callId, name, arguments));
                }
                default -> { }
            }
        }

        StopReason stopReason = mapStatus(root.path("status").asText(null));
        // If there are tool calls, override to TOOL_USE
        if (content.stream().anyMatch(b -> b instanceof ContentBlock.ToolUse)) {
            stopReason = StopReason.TOOL_USE;
        }

        Usage usage = parseUsage(root.path("usage"));
        return new LlmResponse(id, model, content, stopReason, usage);
    }

    StopReason mapStatus(String status) {
        if (status == null) return StopReason.ERROR;
        return switch (status) {
            case "completed" -> StopReason.END_TURN;
            case "incomplete" -> StopReason.MAX_TOKENS;
            case "failed", "cancelled" -> StopReason.ERROR;
            default -> StopReason.ERROR;
        };
    }

    Usage parseUsage(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return new Usage(0, 0);
        }
        return new Usage(
                node.path("input_tokens").asInt(0),
                node.path("output_tokens").asInt(0)
        );
    }

    ObjectMapper objectMapper() {
        return objectMapper;
    }
}

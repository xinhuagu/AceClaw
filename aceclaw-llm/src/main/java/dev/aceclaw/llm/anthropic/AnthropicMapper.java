package dev.aceclaw.llm.anthropic;

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
 * Maps between AceClaw LLM abstractions and the Anthropic Messages API JSON format.
 */
final class AnthropicMapper {

    private final ObjectMapper objectMapper;

    AnthropicMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // -- Request mapping --

    /**
     * Converts an {@link LlmRequest} to the Anthropic API request JSON body.
     *
     * @param request the LLM request
     * @param stream  whether to enable streaming
     * @return JSON string for the HTTP body
     */
    String toRequestJson(LlmRequest request, boolean stream) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", request.model());
        root.put("max_tokens", request.maxTokens());

        // Extended thinking: when enabled, temperature must be 1.0 or omitted
        if (request.thinkingBudget() > 0) {
            ObjectNode thinking = objectMapper.createObjectNode();
            thinking.put("type", "enabled");
            thinking.put("budget_tokens", request.thinkingBudget());
            root.set("thinking", thinking);
            // Temperature must be 1.0 when thinking is enabled; omit it to use default
        } else if (request.temperature() > 0.0) {
            root.put("temperature", request.temperature());
        }

        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            root.put("system", request.systemPrompt());
        }

        if (stream) {
            root.put("stream", true);
        }

        // Messages
        ArrayNode messagesArray = root.putArray("messages");
        for (Message message : request.messages()) {
            messagesArray.add(mapMessage(message));
        }

        // Tools
        if (!request.tools().isEmpty()) {
            ArrayNode toolsArray = root.putArray("tools");
            for (ToolDefinition tool : request.tools()) {
                toolsArray.add(mapToolDefinition(tool));
            }
            ObjectNode toolChoice = objectMapper.createObjectNode();
            toolChoice.put("type", "auto");
            root.set("tool_choice", toolChoice);
        }

        return root.toString();
    }

    private ObjectNode mapMessage(Message message) {
        ObjectNode node = objectMapper.createObjectNode();
        switch (message) {
            case Message.UserMessage um -> {
                node.put("role", "user");
                node.set("content", mapContentBlocks(um.content()));
            }
            case Message.AssistantMessage am -> {
                node.put("role", "assistant");
                node.set("content", mapContentBlocks(am.content()));
            }
        }
        return node;
    }

    private ArrayNode mapContentBlocks(List<ContentBlock> blocks) {
        ArrayNode array = objectMapper.createArrayNode();
        for (ContentBlock block : blocks) {
            // Skip thinking blocks in outgoing messages — the model generates
            // fresh thinking each turn and signatures are not preserved in streaming
            if (block instanceof ContentBlock.Thinking) {
                continue;
            }
            array.add(mapContentBlock(block));
        }
        return array;
    }

    private ObjectNode mapContentBlock(ContentBlock block) {
        ObjectNode node = objectMapper.createObjectNode();
        switch (block) {
            case ContentBlock.Text t -> {
                node.put("type", "text");
                node.put("text", t.text());
            }
            case ContentBlock.Thinking t -> {
                node.put("type", "thinking");
                node.put("thinking", t.text());
            }
            case ContentBlock.ToolUse tu -> {
                node.put("type", "tool_use");
                node.put("id", tu.id());
                node.put("name", tu.name());
                try {
                    node.set("input", objectMapper.readTree(tu.inputJson()));
                } catch (Exception e) {
                    node.putObject("input");
                }
            }
            case ContentBlock.ToolResult tr -> {
                node.put("type", "tool_result");
                node.put("tool_use_id", tr.toolUseId());
                node.put("content", tr.content());
                if (tr.isError()) {
                    node.put("is_error", true);
                }
            }
        }
        return node;
    }

    private ObjectNode mapToolDefinition(ToolDefinition tool) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("name", tool.name());
        node.put("description", tool.description());
        node.set("input_schema", tool.inputSchema());
        return node;
    }

    // -- Response mapping --

    /**
     * Parses the Anthropic API response JSON into an {@link LlmResponse}.
     */
    LlmResponse toResponse(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        return toResponse(root);
    }

    /**
     * Parses the Anthropic API response JSON node into an {@link LlmResponse}.
     */
    LlmResponse toResponse(JsonNode root) {
        String id = root.path("id").asText("");
        String model = root.path("model").asText("");
        StopReason stopReason = StopReason.fromString(root.path("stop_reason").asText(null));
        Usage usage = parseUsage(root.path("usage"));

        List<ContentBlock> content = new ArrayList<>();
        JsonNode contentArray = root.path("content");
        if (contentArray.isArray()) {
            for (JsonNode blockNode : contentArray) {
                ContentBlock block = parseContentBlock(blockNode);
                if (block != null) {
                    content.add(block);
                }
            }
        }

        return new LlmResponse(id, model, content, stopReason, usage);
    }

    /**
     * Parses a single content block from JSON.
     */
    ContentBlock parseContentBlock(JsonNode node) {
        String type = node.path("type").asText("");
        return switch (type) {
            case "text" -> new ContentBlock.Text(node.path("text").asText(""));
            case "thinking" -> new ContentBlock.Thinking(node.path("thinking").asText(""));
            case "tool_use" -> new ContentBlock.ToolUse(
                    node.path("id").asText(""),
                    node.path("name").asText(""),
                    node.path("input").toString()
            );
            default -> null;
        };
    }

    /**
     * Parses usage info from a JSON node.
     */
    Usage parseUsage(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return new Usage(0, 0, 0, 0);
        }
        return new Usage(
                node.path("input_tokens").asInt(0),
                node.path("output_tokens").asInt(0),
                node.path("cache_creation_input_tokens").asInt(0),
                node.path("cache_read_input_tokens").asInt(0)
        );
    }

    ObjectMapper objectMapper() {
        return objectMapper;
    }
}

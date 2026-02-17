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
import java.util.regex.Pattern;

/**
 * Maps between AceClaw LLM abstractions and the OpenAI Chat Completions API JSON format.
 *
 * <p>Handles the key differences from Anthropic's format:
 * <ul>
 *   <li>System prompt is a regular message with {@code role: "system"}</li>
 *   <li>Tool results are separate messages with {@code role: "tool"}</li>
 *   <li>Tool calls use {@code function.arguments} (stringified JSON), not {@code input}</li>
 *   <li>Thinking blocks from {@code reasoning}/{@code reasoning_content} fields
 *       and {@code <think>} tags are extracted into {@link ContentBlock.Thinking}</li>
 * </ul>
 */
final class OpenAIMapper {

    private final ObjectMapper objectMapper;

    OpenAIMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // -- Request mapping --

    /**
     * Converts an {@link LlmRequest} to the OpenAI Chat Completions API request JSON body.
     *
     * @param request the LLM request
     * @param stream  whether to enable streaming
     * @return JSON string for the HTTP body
     */
    String toRequestJson(LlmRequest request, boolean stream) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", request.model());
        root.put("max_completion_tokens", request.maxTokens());

        if (request.temperature() >= 0.0) {
            root.put("temperature", request.temperature());
        }

        // Build messages array
        ArrayNode messagesArray = root.putArray("messages");

        // System prompt as first message
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            ObjectNode systemMsg = objectMapper.createObjectNode();
            systemMsg.put("role", "system");
            systemMsg.put("content", request.systemPrompt());
            messagesArray.add(systemMsg);
        }

        // Conversation messages
        for (Message message : request.messages()) {
            mapMessage(message, messagesArray);
        }

        // Tools
        if (!request.tools().isEmpty()) {
            ArrayNode toolsArray = root.putArray("tools");
            for (ToolDefinition tool : request.tools()) {
                toolsArray.add(mapToolDefinition(tool));
            }
            root.put("tool_choice", "auto");
        }

        // Streaming
        if (stream) {
            root.put("stream", true);
            ObjectNode streamOptions = objectMapper.createObjectNode();
            streamOptions.put("include_usage", true);
            root.set("stream_options", streamOptions);
        }

        // Extended thinking is silently ignored (not supported by OpenAI)

        return root.toString();
    }

    /**
     * Maps a single AceClaw message to one or more OpenAI messages.
     *
     * <p>Key difference from Anthropic: OpenAI requires tool results as separate
     * {@code role: "tool"} messages, while Anthropic bundles them in a user message.
     * Also, assistant messages with tool calls must use the {@code tool_calls} array
     * instead of embedding tool use blocks in content.
     */
    private void mapMessage(Message message, ArrayNode messagesArray) {
        switch (message) {
            case Message.UserMessage um -> mapUserMessage(um, messagesArray);
            case Message.AssistantMessage am -> mapAssistantMessage(am, messagesArray);
        }
    }

    private void mapUserMessage(Message.UserMessage um, ArrayNode messagesArray) {
        // Separate tool results from text content
        var toolResults = new ArrayList<ContentBlock.ToolResult>();
        var textParts = new ArrayList<String>();

        for (ContentBlock block : um.content()) {
            switch (block) {
                case ContentBlock.ToolResult tr -> toolResults.add(tr);
                case ContentBlock.Text t -> textParts.add(t.text());
                case ContentBlock.Thinking ignored -> { /* strip thinking blocks */ }
                case ContentBlock.ToolUse ignored -> { /* should not appear in user messages */ }
            }
        }

        // Emit tool result messages first (they must follow the assistant's tool_calls)
        for (ContentBlock.ToolResult tr : toolResults) {
            ObjectNode toolMsg = objectMapper.createObjectNode();
            toolMsg.put("role", "tool");
            toolMsg.put("tool_call_id", tr.toolUseId());
            toolMsg.put("content", tr.content() != null ? tr.content() : "");
            messagesArray.add(toolMsg);
        }

        // Then emit user text if present
        if (!textParts.isEmpty()) {
            String combinedText = String.join("\n", textParts);
            ObjectNode userMsg = objectMapper.createObjectNode();
            userMsg.put("role", "user");
            userMsg.put("content", combinedText);
            messagesArray.add(userMsg);
        }
    }

    private void mapAssistantMessage(Message.AssistantMessage am, ArrayNode messagesArray) {
        var toolUseCalls = new ArrayList<ContentBlock.ToolUse>();
        var textParts = new ArrayList<String>();

        for (ContentBlock block : am.content()) {
            switch (block) {
                case ContentBlock.ToolUse tu -> toolUseCalls.add(tu);
                case ContentBlock.Text t -> textParts.add(t.text());
                case ContentBlock.Thinking ignored -> { /* strip thinking blocks */ }
                case ContentBlock.ToolResult ignored -> { /* should not appear in assistant messages */ }
            }
        }

        ObjectNode assistantMsg = objectMapper.createObjectNode();
        assistantMsg.put("role", "assistant");

        // Text content
        if (!textParts.isEmpty()) {
            assistantMsg.put("content", String.join("\n", textParts));
        } else if (toolUseCalls.isEmpty()) {
            assistantMsg.put("content", "");
        }

        // Tool calls
        if (!toolUseCalls.isEmpty()) {
            ArrayNode toolCallsArray = assistantMsg.putArray("tool_calls");
            for (ContentBlock.ToolUse tu : toolUseCalls) {
                ObjectNode toolCall = objectMapper.createObjectNode();
                toolCall.put("id", tu.id());
                toolCall.put("type", "function");
                ObjectNode function = objectMapper.createObjectNode();
                function.put("name", tu.name());
                function.put("arguments", tu.inputJson() != null ? tu.inputJson() : "{}");
                toolCall.set("function", function);
                toolCallsArray.add(toolCall);
            }
        }

        messagesArray.add(assistantMsg);
    }

    private ObjectNode mapToolDefinition(ToolDefinition tool) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "function");
        ObjectNode function = objectMapper.createObjectNode();
        function.put("name", tool.name());
        function.put("description", tool.description());
        function.set("parameters", tool.inputSchema());
        node.set("function", function);
        return node;
    }

    // -- Response mapping --

    /**
     * Parses the OpenAI Chat Completions API response JSON into an {@link LlmResponse}.
     */
    LlmResponse toResponse(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        return toResponse(root);
    }

    /**
     * Parses the OpenAI Chat Completions API response JSON node into an {@link LlmResponse}.
     */
    LlmResponse toResponse(JsonNode root) {
        String id = root.path("id").asText("");
        String model = root.path("model").asText("");

        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return new LlmResponse(id, model, List.of(), StopReason.ERROR, parseUsage(root.path("usage")));
        }

        JsonNode firstChoice = choices.get(0);
        StopReason stopReason = StopReason.fromString(firstChoice.path("finish_reason").asText(null));
        JsonNode messageNode = firstChoice.path("message");

        List<ContentBlock> content = new ArrayList<>();

        // Reasoning content (qwen3 uses "reasoning", DeepSeek uses "reasoning_content")
        String reasoning = messageNode.path("reasoning").asText(null);
        if (reasoning == null) {
            reasoning = messageNode.path("reasoning_content").asText(null);
        }
        if (reasoning != null && !reasoning.isEmpty()) {
            content.add(new ContentBlock.Thinking(reasoning));
        }

        // Text content
        String text = messageNode.path("content").asText(null);

        // Detect <think> tags in text content (DeepSeek-R1, QwQ, etc.)
        if (text != null && text.contains("<think>")) {
            var matcher = Pattern.compile("<think>(.*?)</think>", Pattern.DOTALL).matcher(text);
            if (matcher.find()) {
                String thinkingText = matcher.group(1).trim();
                if (!thinkingText.isEmpty()) {
                    content.add(new ContentBlock.Thinking(thinkingText));
                }
                text = matcher.replaceAll("").trim();
            }
        }

        if (text != null && !text.isEmpty()) {
            content.add(new ContentBlock.Text(text));
        }

        // Tool calls
        JsonNode toolCalls = messageNode.path("tool_calls");
        if (toolCalls.isArray()) {
            for (JsonNode tc : toolCalls) {
                String tcId = tc.path("id").asText("");
                JsonNode function = tc.path("function");
                String name = function.path("name").asText("");
                String arguments = function.path("arguments").asText("{}");
                content.add(new ContentBlock.ToolUse(tcId, name, arguments));
            }
        }

        Usage usage = parseUsage(root.path("usage"));
        return new LlmResponse(id, model, content, stopReason, usage);
    }

    /**
     * Parses usage info from an OpenAI response.
     */
    Usage parseUsage(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return new Usage(0, 0);
        }
        return new Usage(
                node.path("prompt_tokens").asInt(0),
                node.path("completion_tokens").asInt(0)
        );
    }

    ObjectMapper objectMapper() {
        return objectMapper;
    }
}

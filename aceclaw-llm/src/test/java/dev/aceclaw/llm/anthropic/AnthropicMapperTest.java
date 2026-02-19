package dev.aceclaw.llm.anthropic;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.aceclaw.core.llm.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicMapperTest {

    private ObjectMapper objectMapper;
    private AnthropicMapper mapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper = new AnthropicMapper(objectMapper);
    }

    private LlmRequest.Builder baseRequest() {
        return LlmRequest.builder()
                .model("claude-sonnet-4-5-20250929")
                .addMessage(Message.user("Hello"));
    }

    // --- toRequestJson tests ---

    @Test
    void toRequestJson_producesValidStructure() throws Exception {
        var schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        var tool = new ToolDefinition("read_file", "Read a file", schema);

        var request = baseRequest().addTool(tool).build();
        String json = mapper.toRequestJson(request, false);
        JsonNode root = objectMapper.readTree(json);

        assertThat(root.get("model").asText()).isEqualTo("claude-sonnet-4-5-20250929");
        assertThat(root.get("max_tokens").asInt()).isGreaterThan(0);
        assertThat(root.get("messages").isArray()).isTrue();
        assertThat(root.get("messages")).hasSize(1);
        assertThat(root.get("tools").isArray()).isTrue();
        assertThat(root.get("tools")).hasSize(1);
        assertThat(root.has("stream")).isFalse();
    }

    @Test
    void systemPrompt_getsCacheControlEphemeral() throws Exception {
        var request = baseRequest().systemPrompt("You are helpful").build();
        JsonNode root = objectMapper.readTree(mapper.toRequestJson(request, false));

        JsonNode system = root.get("system");
        assertThat(system.isArray()).isTrue();
        assertThat(system).hasSize(1);
        assertThat(system.get(0).get("type").asText()).isEqualTo("text");
        assertThat(system.get(0).get("text").asText()).isEqualTo("You are helpful");
        assertThat(system.get(0).path("cache_control").path("type").asText()).isEqualTo("ephemeral");
    }

    @Test
    void lastUserMessage_getsCacheControlOnLastBlock() throws Exception {
        var request = LlmRequest.builder()
                .model("test")
                .addMessage(Message.user("First"))
                .addMessage(Message.assistant("Reply"))
                .addMessage(Message.user("Second"))
                .build();
        JsonNode root = objectMapper.readTree(mapper.toRequestJson(request, false));

        JsonNode messages = root.get("messages");
        // First user message (index 0) should NOT have cache_control
        JsonNode firstUserContent = messages.get(0).get("content");
        assertThat(firstUserContent.get(firstUserContent.size() - 1).has("cache_control")).isFalse();

        // Last user message (index 2) should have cache_control
        JsonNode lastUserContent = messages.get(2).get("content");
        assertThat(lastUserContent.get(lastUserContent.size() - 1)
                .path("cache_control").path("type").asText()).isEqualTo("ephemeral");
    }

    @Test
    void lastToolDefinition_getsCacheControl() throws Exception {
        var schema = objectMapper.createObjectNode().put("type", "object");
        var request = baseRequest()
                .addTool(new ToolDefinition("tool1", "desc1", schema))
                .addTool(new ToolDefinition("tool2", "desc2", schema))
                .build();
        JsonNode root = objectMapper.readTree(mapper.toRequestJson(request, false));

        JsonNode tools = root.get("tools");
        assertThat(tools.get(0).has("cache_control")).isFalse();
        assertThat(tools.get(1).path("cache_control").path("type").asText()).isEqualTo("ephemeral");
    }

    @Test
    void streamFlag_setsStreamTrue() throws Exception {
        var request = baseRequest().build();
        JsonNode root = objectMapper.readTree(mapper.toRequestJson(request, true));
        assertThat(root.get("stream").asBoolean()).isTrue();
    }

    @Test
    void extendedThinking_addsThinkingAndOmitsTemperature() throws Exception {
        var request = baseRequest().thinkingBudget(5000).build();
        JsonNode root = objectMapper.readTree(mapper.toRequestJson(request, false));

        assertThat(root.has("thinking")).isTrue();
        assertThat(root.get("thinking").get("type").asText()).isEqualTo("enabled");
        assertThat(root.get("thinking").get("budget_tokens").asInt()).isEqualTo(5000);
        assertThat(root.has("temperature")).isFalse();
    }

    @Test
    void toolUseBlock_serializesInputAsJsonObject() throws Exception {
        var toolUse = new ContentBlock.ToolUse("id1", "read_file", "{\"path\":\"/tmp/x\"}");
        var request = LlmRequest.builder()
                .model("test")
                .addMessage(new Message.AssistantMessage(List.of(toolUse)))
                .build();
        JsonNode root = objectMapper.readTree(mapper.toRequestJson(request, false));

        JsonNode input = root.get("messages").get(0).get("content").get(0).get("input");
        assertThat(input.isObject()).isTrue();
        assertThat(input.get("path").asText()).isEqualTo("/tmp/x");
    }

    @Test
    void thinkingBlocks_areSkippedInOutgoingMessages() throws Exception {
        var request = LlmRequest.builder()
                .model("test")
                .addMessage(new Message.AssistantMessage(List.of(
                        new ContentBlock.Thinking("internal thought"),
                        new ContentBlock.Text("visible text")
                )))
                .build();
        JsonNode root = objectMapper.readTree(mapper.toRequestJson(request, false));

        JsonNode content = root.get("messages").get(0).get("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0).get("type").asText()).isEqualTo("text");
    }

    // --- toResponse tests ---

    @Test
    void toResponse_parsesTextAndToolUseBlocks() throws Exception {
        String json = """
                {
                  "id": "msg_123",
                  "model": "claude-sonnet-4-5-20250929",
                  "stop_reason": "tool_use",
                  "usage": {"input_tokens": 100, "output_tokens": 50},
                  "content": [
                    {"type": "text", "text": "Let me read that file."},
                    {"type": "tool_use", "id": "tu_1", "name": "read_file", "input": {"path": "/tmp/x"}}
                  ]
                }
                """;
        LlmResponse response = mapper.toResponse(json);

        assertThat(response.id()).isEqualTo("msg_123");
        assertThat(response.model()).isEqualTo("claude-sonnet-4-5-20250929");
        assertThat(response.stopReason()).isEqualTo(StopReason.TOOL_USE);
        assertThat(response.content()).hasSize(2);
        assertThat(response.content().get(0)).isInstanceOf(ContentBlock.Text.class);
        assertThat(((ContentBlock.Text) response.content().get(0)).text()).isEqualTo("Let me read that file.");
        assertThat(response.content().get(1)).isInstanceOf(ContentBlock.ToolUse.class);
        assertThat(((ContentBlock.ToolUse) response.content().get(1)).name()).isEqualTo("read_file");
    }

    @Test
    void toResponse_parsesStopReasons() throws Exception {
        for (var entry : List.of(
                new String[]{"end_turn", "END_TURN"},
                new String[]{"tool_use", "TOOL_USE"},
                new String[]{"max_tokens", "MAX_TOKENS"}
        )) {
            String json = """
                    {"id":"x","model":"m","stop_reason":"%s","usage":{},"content":[]}
                    """.formatted(entry[0]);
            LlmResponse response = mapper.toResponse(json);
            assertThat(response.stopReason().name()).isEqualTo(entry[1]);
        }
    }

    @Test
    void parseUsage_handlesMissingAndZeroValues() {
        // Missing node
        Usage usage1 = mapper.parseUsage(objectMapper.missingNode());
        assertThat(usage1.inputTokens()).isZero();
        assertThat(usage1.outputTokens()).isZero();

        // Empty object
        Usage usage2 = mapper.parseUsage(objectMapper.createObjectNode());
        assertThat(usage2.inputTokens()).isZero();
        assertThat(usage2.outputTokens()).isZero();

        // Null
        Usage usage3 = mapper.parseUsage(null);
        assertThat(usage3.inputTokens()).isZero();
    }

    @Test
    void parseContentBlock_returnsNullForUnknownTypes() throws Exception {
        JsonNode node = objectMapper.readTree("{\"type\": \"unknown_type\"}");
        assertThat(mapper.parseContentBlock(node)).isNull();
    }

    @Test
    void toolUse_withNullOrMalformedInput_fallsBackToEmptyObject() throws Exception {
        // null inputJson
        var toolUse1 = new ContentBlock.ToolUse("id1", "tool", null);
        var request1 = LlmRequest.builder().model("m")
                .addMessage(new Message.AssistantMessage(List.of(toolUse1))).build();
        JsonNode root1 = objectMapper.readTree(mapper.toRequestJson(request1, false));
        JsonNode input1 = root1.get("messages").get(0).get("content").get(0).get("input");
        assertThat(input1.isObject()).isTrue();
        assertThat(input1.isEmpty()).isTrue();

        // empty string
        var toolUse2 = new ContentBlock.ToolUse("id2", "tool", "");
        var request2 = LlmRequest.builder().model("m")
                .addMessage(new Message.AssistantMessage(List.of(toolUse2))).build();
        JsonNode root2 = objectMapper.readTree(mapper.toRequestJson(request2, false));
        JsonNode input2 = root2.get("messages").get(0).get("content").get(0).get("input");
        assertThat(input2.isObject()).isTrue();

        // malformed JSON
        var toolUse3 = new ContentBlock.ToolUse("id3", "tool", "not json");
        var request3 = LlmRequest.builder().model("m")
                .addMessage(new Message.AssistantMessage(List.of(toolUse3))).build();
        JsonNode root3 = objectMapper.readTree(mapper.toRequestJson(request3, false));
        JsonNode input3 = root3.get("messages").get(0).get("content").get(0).get("input");
        assertThat(input3.isObject()).isTrue();
        assertThat(input3.isEmpty()).isTrue();
    }

    @Test
    void noSystemPrompt_omitsSystemField() throws Exception {
        var request = LlmRequest.builder().model("m").addMessage(Message.user("hi")).build();
        JsonNode root = objectMapper.readTree(mapper.toRequestJson(request, false));
        assertThat(root.has("system")).isFalse();
    }

    @Test
    void noThinking_includesTemperature() throws Exception {
        var request = baseRequest().thinkingBudget(0).temperature(0.7).build();
        JsonNode root = objectMapper.readTree(mapper.toRequestJson(request, false));
        assertThat(root.get("temperature").asDouble()).isEqualTo(0.7);
        assertThat(root.has("thinking")).isFalse();
    }
}

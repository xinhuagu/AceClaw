package dev.aceclaw.llm.openai;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.llm.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAIMapperTest {

    private ObjectMapper objectMapper;
    private OpenAIMapper mapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper = new OpenAIMapper(objectMapper);
    }

    private LlmRequest.Builder baseRequest() {
        return LlmRequest.builder()
                .model("gpt-4o")
                .addMessage(Message.user("Hello"));
    }

    @Test
    void toRequestJson_producesValidStructure() throws Exception {
        var schema = objectMapper.createObjectNode().put("type", "object");
        var request = baseRequest()
                .addTool(new ToolDefinition("read_file", "Read", schema))
                .build();
        JsonNode root = objectMapper.readTree(mapper.toRequestJson(request, false));

        assertThat(root.get("model").asText()).isEqualTo("gpt-4o");
        assertThat(root.get("max_completion_tokens").asInt()).isGreaterThan(0);
        assertThat(root.get("messages").isArray()).isTrue();
        assertThat(root.get("tools").isArray()).isTrue();
        assertThat(root.get("tool_choice").asText()).isEqualTo("auto");
        assertThat(root.has("stream")).isFalse();
    }

    @Test
    void systemPrompt_isFirstMessage() throws Exception {
        var request = baseRequest().systemPrompt("Be helpful").build();
        JsonNode root = objectMapper.readTree(mapper.toRequestJson(request, false));
        JsonNode messages = root.get("messages");

        assertThat(messages.get(0).get("role").asText()).isEqualTo("system");
        assertThat(messages.get(0).get("content").asText()).isEqualTo("Be helpful");
        assertThat(messages.get(1).get("role").asText()).isEqualTo("user");
    }

    @Test
    void streamFlag_setsStreamAndOptions() throws Exception {
        JsonNode root = objectMapper.readTree(mapper.toRequestJson(baseRequest().build(), true));
        assertThat(root.get("stream").asBoolean()).isTrue();
        assertThat(root.get("stream_options").get("include_usage").asBoolean()).isTrue();
    }

    @Test
    void toolUseInAssistantMessage_usesToolCallsArray() throws Exception {
        var toolUse = new ContentBlock.ToolUse("call_1", "read_file", "{\"path\":\"/tmp\"}");
        var request = LlmRequest.builder()
                .model("gpt-4o")
                .addMessage(new Message.AssistantMessage(List.of(toolUse)))
                .build();
        JsonNode root = objectMapper.readTree(mapper.toRequestJson(request, false));

        JsonNode msg = root.get("messages").get(0);
        assertThat(msg.get("role").asText()).isEqualTo("assistant");
        JsonNode tc = msg.get("tool_calls").get(0);
        assertThat(tc.get("id").asText()).isEqualTo("call_1");
        assertThat(tc.get("type").asText()).isEqualTo("function");
        assertThat(tc.get("function").get("name").asText()).isEqualTo("read_file");
        assertThat(tc.get("function").get("arguments").asText()).isEqualTo("{\"path\":\"/tmp\"}");
    }

    @Test
    void toolResult_mappedAsToolRoleMessage() throws Exception {
        var result = new ContentBlock.ToolResult("call_1", "file contents", false);
        var request = LlmRequest.builder()
                .model("gpt-4o")
                .addMessage(new Message.UserMessage(List.of(result)))
                .build();
        JsonNode root = objectMapper.readTree(mapper.toRequestJson(request, false));

        JsonNode msg = root.get("messages").get(0);
        assertThat(msg.get("role").asText()).isEqualTo("tool");
        assertThat(msg.get("tool_call_id").asText()).isEqualTo("call_1");
        assertThat(msg.get("content").asText()).isEqualTo("file contents");
    }

    @Test
    void thinkingBlocks_areStripped() throws Exception {
        var request = LlmRequest.builder()
                .model("gpt-4o")
                .addMessage(new Message.AssistantMessage(List.of(
                        new ContentBlock.Thinking("hmm"),
                        new ContentBlock.Text("answer")
                )))
                .build();
        JsonNode root = objectMapper.readTree(mapper.toRequestJson(request, false));
        JsonNode msg = root.get("messages").get(0);
        assertThat(msg.get("content").asText()).isEqualTo("answer");
        assertThat(msg.has("tool_calls")).isFalse();
    }

    @Test
    void toolDefinition_wrappedInFunctionType() throws Exception {
        var schema = objectMapper.createObjectNode().put("type", "object");
        var request = baseRequest()
                .addTool(new ToolDefinition("my_tool", "does stuff", schema))
                .build();
        JsonNode root = objectMapper.readTree(mapper.toRequestJson(request, false));
        JsonNode tool = root.get("tools").get(0);
        assertThat(tool.get("type").asText()).isEqualTo("function");
        assertThat(tool.get("function").get("name").asText()).isEqualTo("my_tool");
        assertThat(tool.get("function").get("description").asText()).isEqualTo("does stuff");
    }

    @Test
    void thinkingBudget_emitsThinkingBlock() throws Exception {
        var request = baseRequest()
                .thinkingBudget(10240)
                .build();
        JsonNode root = objectMapper.readTree(mapper.toRequestJson(request, false));

        assertThat(root.has("thinking")).isTrue();
        assertThat(root.get("thinking").get("type").asText()).isEqualTo("enabled");
        assertThat(root.get("thinking").get("budget_tokens").asInt()).isEqualTo(10240);
        // Temperature must be removed when thinking is enabled
        assertThat(root.has("temperature")).isFalse();
    }

    @Test
    void zeroThinkingBudget_omitsThinkingBlock() throws Exception {
        var request = baseRequest()
                .thinkingBudget(0)
                .build();
        JsonNode root = objectMapper.readTree(mapper.toRequestJson(request, false));

        assertThat(root.has("thinking")).isFalse();
    }

    // --- Response parsing ---

    @Test
    void toResponse_parsesTextAndToolCalls() throws Exception {
        String json = """
                {
                  "id": "chatcmpl-123",
                  "model": "gpt-4o",
                  "choices": [{
                    "finish_reason": "tool_calls",
                    "message": {
                      "content": "Let me check.",
                      "tool_calls": [{
                        "id": "call_1",
                        "type": "function",
                        "function": {"name": "read_file", "arguments": "{\\"path\\":\\"/tmp\\"}"}
                      }]
                    }
                  }],
                  "usage": {"prompt_tokens": 10, "completion_tokens": 20}
                }
                """;
        LlmResponse response = mapper.toResponse(json);
        assertThat(response.id()).isEqualTo("chatcmpl-123");
        assertThat(response.stopReason()).isEqualTo(StopReason.TOOL_USE);
        assertThat(response.content()).hasSize(2);
        assertThat(response.content().get(0)).isInstanceOf(ContentBlock.Text.class);
        assertThat(response.content().get(1)).isInstanceOf(ContentBlock.ToolUse.class);
        assertThat(response.usage().inputTokens()).isEqualTo(10);
        assertThat(response.usage().outputTokens()).isEqualTo(20);
    }

    @Test
    void toResponse_extractsThinkTags() throws Exception {
        String json = """
                {
                  "id": "x", "model": "m",
                  "choices": [{"finish_reason": "stop", "message": {"content": "<think>reasoning here</think>Final answer"}}],
                  "usage": {}
                }
                """;
        LlmResponse response = mapper.toResponse(json);
        assertThat(response.content()).hasSize(2);
        assertThat(response.content().get(0)).isInstanceOf(ContentBlock.Thinking.class);
        assertThat(((ContentBlock.Thinking) response.content().get(0)).text()).isEqualTo("reasoning here");
        assertThat(((ContentBlock.Text) response.content().get(1)).text()).isEqualTo("Final answer");
    }

    @Test
    void toResponse_extractsReasoningField() throws Exception {
        String json = """
                {
                  "id": "x", "model": "m",
                  "choices": [{"finish_reason": "stop", "message": {"reasoning_content": "deep thought", "content": "42"}}],
                  "usage": {}
                }
                """;
        LlmResponse response = mapper.toResponse(json);
        assertThat(response.content()).hasSize(2);
        assertThat(response.content().get(0)).isInstanceOf(ContentBlock.Thinking.class);
        assertThat(((ContentBlock.Thinking) response.content().get(0)).text()).isEqualTo("deep thought");
    }

    @Test
    void toResponse_emptyChoices_returnsError() throws Exception {
        String json = """
                {"id": "x", "model": "m", "choices": [], "usage": {}}
                """;
        LlmResponse response = mapper.toResponse(json);
        assertThat(response.stopReason()).isEqualTo(StopReason.ERROR);
        assertThat(response.content()).isEmpty();
    }

    @Test
    void parseUsage_handlesMissing() {
        Usage usage = mapper.parseUsage(objectMapper.missingNode());
        assertThat(usage.inputTokens()).isZero();
        assertThat(usage.outputTokens()).isZero();
    }
}

package dev.aceclaw.llm.openai;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.llm.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAIResponsesMapperTest {

    private ObjectMapper objectMapper;
    private OpenAIResponsesMapper mapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper = new OpenAIResponsesMapper(objectMapper);
    }

    private LlmRequest.Builder baseRequest() {
        return LlmRequest.builder()
                .model("gpt-5.2-codex")
                .addMessage(Message.user("Hello"));
    }

    // --- Request mapping ---

    @Test
    void toRequestJson_producesValidStructure() throws Exception {
        var request = baseRequest().build();
        JsonNode root = objectMapper.readTree(mapper.toRequestJson(request, false));

        assertThat(root.get("model").asText()).isEqualTo("gpt-5.2-codex");
        assertThat(root.get("max_output_tokens").asInt()).isGreaterThan(0);
        assertThat(root.get("input").isArray()).isTrue();
        assertThat(root.has("stream")).isFalse();
        // Should NOT have "messages" (that's Chat Completions)
        assertThat(root.has("messages")).isFalse();
    }

    @Test
    void systemPrompt_usesInstructionsField() throws Exception {
        var request = baseRequest().systemPrompt("You are a coding assistant.").build();
        JsonNode root = objectMapper.readTree(mapper.toRequestJson(request, false));

        assertThat(root.get("instructions").asText()).isEqualTo("You are a coding assistant.");
        // Should NOT have system message in input
        JsonNode input = root.get("input");
        for (JsonNode item : input) {
            if (item.has("role")) {
                assertThat(item.get("role").asText()).isNotEqualTo("system");
            }
        }
    }

    @Test
    void streamFlag_setsStreamField() throws Exception {
        JsonNode root = objectMapper.readTree(mapper.toRequestJson(baseRequest().build(), true));
        assertThat(root.get("stream").asBoolean()).isTrue();
        // Responses API does NOT use stream_options
        assertThat(root.has("stream_options")).isFalse();
    }

    @Test
    void userMessage_mappedToInputItem() throws Exception {
        var request = baseRequest().build();
        JsonNode root = objectMapper.readTree(mapper.toRequestJson(request, false));
        JsonNode input = root.get("input");

        assertThat(input).hasSize(1);
        assertThat(input.get(0).get("role").asText()).isEqualTo("user");
        assertThat(input.get(0).get("content").asText()).isEqualTo("Hello");
    }

    @Test
    void assistantMessage_mappedToInputItem() throws Exception {
        var request = LlmRequest.builder()
                .model("gpt-5.2-codex")
                .addMessage(new Message.AssistantMessage(List.of(new ContentBlock.Text("Sure!"))))
                .build();
        JsonNode root = objectMapper.readTree(mapper.toRequestJson(request, false));
        JsonNode input = root.get("input");

        assertThat(input.get(0).get("role").asText()).isEqualTo("assistant");
        assertThat(input.get(0).get("content").asText()).isEqualTo("Sure!");
    }

    @Test
    void toolResult_mappedToFunctionCallOutput() throws Exception {
        var result = new ContentBlock.ToolResult("call_abc", "file contents here", false);
        var request = LlmRequest.builder()
                .model("gpt-5.2-codex")
                .addMessage(new Message.UserMessage(List.of(result)))
                .build();
        JsonNode root = objectMapper.readTree(mapper.toRequestJson(request, false));
        JsonNode input = root.get("input");

        assertThat(input).hasSize(1);
        JsonNode item = input.get(0);
        assertThat(item.get("type").asText()).isEqualTo("function_call_output");
        assertThat(item.get("call_id").asText()).isEqualTo("call_abc");
        assertThat(item.get("output").asText()).isEqualTo("file contents here");
    }

    @Test
    void assistantToolUse_mappedToFunctionCallItem() throws Exception {
        var toolUse = new ContentBlock.ToolUse("call_1", "read_file", "{\"path\":\"/tmp\"}");
        var request = LlmRequest.builder()
                .model("gpt-5.2-codex")
                .addMessage(new Message.AssistantMessage(List.of(toolUse)))
                .build();
        JsonNode root = objectMapper.readTree(mapper.toRequestJson(request, false));
        JsonNode input = root.get("input");

        JsonNode fc = input.get(0);
        assertThat(fc.get("type").asText()).isEqualTo("function_call");
        assertThat(fc.get("call_id").asText()).isEqualTo("call_1");
        assertThat(fc.get("name").asText()).isEqualTo("read_file");
        assertThat(fc.get("arguments").asText()).isEqualTo("{\"path\":\"/tmp\"}");
    }

    @Test
    void toolDefinitions_wrappedInFunctionType() throws Exception {
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
    void thinkingBlocks_areStripped() throws Exception {
        var request = LlmRequest.builder()
                .model("gpt-5.2-codex")
                .addMessage(new Message.AssistantMessage(List.of(
                        new ContentBlock.Thinking("hmm"),
                        new ContentBlock.Text("answer")
                )))
                .build();
        JsonNode root = objectMapper.readTree(mapper.toRequestJson(request, false));
        JsonNode input = root.get("input");

        // Should only have one item (the text), thinking stripped
        assertThat(input.get(0).get("content").asText()).isEqualTo("answer");
    }

    // --- Response parsing ---

    @Test
    void toResponse_parsesTextOutput() throws Exception {
        String json = """
                {
                  "id": "resp_123",
                  "model": "gpt-5.2-codex",
                  "output": [
                    {"type": "message", "role": "assistant", "content": [{"type": "output_text", "text": "Hello world!"}]}
                  ],
                  "usage": {"input_tokens": 10, "output_tokens": 5},
                  "status": "completed"
                }
                """;
        LlmResponse response = mapper.toResponse(json);

        assertThat(response.id()).isEqualTo("resp_123");
        assertThat(response.model()).isEqualTo("gpt-5.2-codex");
        assertThat(response.stopReason()).isEqualTo(StopReason.END_TURN);
        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0)).isInstanceOf(ContentBlock.Text.class);
        assertThat(((ContentBlock.Text) response.content().get(0)).text()).isEqualTo("Hello world!");
    }

    @Test
    void toResponse_parsesFunctionCall() throws Exception {
        String json = """
                {
                  "id": "resp_456",
                  "model": "gpt-5.2-codex",
                  "output": [
                    {"type": "function_call", "id": "fc_1", "call_id": "call_abc", "name": "read_file", "arguments": "{\\"path\\":\\"/tmp\\"}"}
                  ],
                  "usage": {"input_tokens": 20, "output_tokens": 10},
                  "status": "completed"
                }
                """;
        LlmResponse response = mapper.toResponse(json);

        assertThat(response.stopReason()).isEqualTo(StopReason.TOOL_USE);
        assertThat(response.content()).hasSize(1);
        var toolUse = (ContentBlock.ToolUse) response.content().get(0);
        assertThat(toolUse.id()).isEqualTo("call_abc");
        assertThat(toolUse.name()).isEqualTo("read_file");
        assertThat(toolUse.inputJson()).isEqualTo("{\"path\":\"/tmp\"}");
    }

    @Test
    void toResponse_parsesUsage() throws Exception {
        String json = """
                {
                  "id": "resp_x", "model": "m",
                  "output": [{"type": "message", "content": [{"type": "output_text", "text": "hi"}]}],
                  "usage": {"input_tokens": 100, "output_tokens": 50},
                  "status": "completed"
                }
                """;
        LlmResponse response = mapper.toResponse(json);
        assertThat(response.usage().inputTokens()).isEqualTo(100);
        assertThat(response.usage().outputTokens()).isEqualTo(50);
        assertThat(response.usage().totalTokens()).isEqualTo(150);
    }

    @Test
    void toResponse_incompleteStatus_mapsToMaxTokens() throws Exception {
        String json = """
                {
                  "id": "resp_x", "model": "m",
                  "output": [{"type": "message", "content": [{"type": "output_text", "text": "partial"}]}],
                  "usage": {"input_tokens": 10, "output_tokens": 128000},
                  "status": "incomplete"
                }
                """;
        LlmResponse response = mapper.toResponse(json);
        assertThat(response.stopReason()).isEqualTo(StopReason.MAX_TOKENS);
    }

    @Test
    void toResponse_failedStatus_mapsToError() throws Exception {
        String json = """
                {
                  "id": "resp_x", "model": "m",
                  "output": [],
                  "usage": {},
                  "status": "failed"
                }
                """;
        LlmResponse response = mapper.toResponse(json);
        assertThat(response.stopReason()).isEqualTo(StopReason.ERROR);
    }

    @Test
    void toResponse_mixedTextAndToolCall() throws Exception {
        String json = """
                {
                  "id": "resp_mix", "model": "gpt-5.2-codex",
                  "output": [
                    {"type": "message", "role": "assistant", "content": [{"type": "output_text", "text": "Let me check."}]},
                    {"type": "function_call", "id": "fc_2", "call_id": "call_xyz", "name": "write_file", "arguments": "{}"}
                  ],
                  "usage": {"input_tokens": 30, "output_tokens": 15},
                  "status": "completed"
                }
                """;
        LlmResponse response = mapper.toResponse(json);
        assertThat(response.content()).hasSize(2);
        assertThat(response.content().get(0)).isInstanceOf(ContentBlock.Text.class);
        assertThat(response.content().get(1)).isInstanceOf(ContentBlock.ToolUse.class);
        // Should be TOOL_USE since there's a function_call
        assertThat(response.stopReason()).isEqualTo(StopReason.TOOL_USE);
    }

    @Test
    void parseUsage_handlesMissing() {
        Usage usage = mapper.parseUsage(objectMapper.missingNode());
        assertThat(usage.inputTokens()).isZero();
        assertThat(usage.outputTokens()).isZero();
    }
}

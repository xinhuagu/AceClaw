package dev.aceclaw.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.llm.ContentBlock;
import dev.aceclaw.core.llm.LlmClient;
import dev.aceclaw.core.llm.LlmException;
import dev.aceclaw.core.llm.LlmRequest;
import dev.aceclaw.core.llm.LlmResponse;
import dev.aceclaw.core.llm.StreamSession;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link StreamingAgentLoop#tryConvertTextToToolUse}, the fallback that turns a
 * text-only assistant turn into a native {@code ToolUse} for models without native tool calling.
 * Covers both the {@code [tool:start]} marker format and the bare-JSON format.
 */
class StreamingAgentLoopTextToolConversionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private StreamingAgentLoop newLoop() {
        var registry = new ToolRegistry();
        registry.register(new StubTool("bash"));
        registry.register(new StubTool("read_file"));
        registry.register(new StubTool("write_file"));
        // MCP tools are named mcp__<server>__<tool>, and <tool> may contain '-' or '.'.
        registry.register(new StubTool("mcp__context7__query-docs"));
        return new StreamingAgentLoop(new NoopLlmClient(), registry, "model", null);
    }

    private List<ContentBlock> convert(String text) {
        return newLoop().tryConvertTextToToolUse(List.of(new ContentBlock.Text(text)));
    }

    private ContentBlock.ToolUse toolUseOf(List<ContentBlock> blocks) {
        assertThat(blocks).isNotNull();
        return (ContentBlock.ToolUse) blocks.stream()
                .filter(b -> b instanceof ContentBlock.ToolUse)
                .findFirst()
                .orElseThrow();
    }

    // --- Marker format ---

    @Test
    void parsesMarkerFormatWithArgs() throws Exception {
        var result = convert("[tool:start] bash {\"command\": \"ls -la\"} [tool:stop]");
        var toolUse = toolUseOf(result);
        assertThat(toolUse.name()).isEqualTo("bash");
        assertThat(JSON.readTree(toolUse.inputJson()).path("command").asText()).isEqualTo("ls -la");
    }

    @Test
    void parsesMarkerFormatAfterReasoningProse() {
        var result = convert("I'll list the files first.\n\n[tool:start] bash {\"command\": \"ls\"} [tool:stop]");
        assertThat(toolUseOf(result).name()).isEqualTo("bash");
    }

    @Test
    void parsesMarkerFormatWithoutStopMarker() {
        var result = convert("[tool:start] read_file {\"path\": \"a.txt\"}");
        assertThat(toolUseOf(result).name()).isEqualTo("read_file");
    }

    /**
     * Finding: the marker tool-name scan must not stop at '-' or '.' — MCP tool names
     * (mcp__server__tool) frequently contain hyphens, and the bare-JSON path already accepts them.
     */
    @Test
    void parsesMarkerFormatWithHyphenatedMcpToolName() throws Exception {
        var result = convert("[tool:start] mcp__context7__query-docs {\"q\": \"streams\"} [tool:stop]");
        var toolUse = toolUseOf(result);
        assertThat(toolUse.name()).isEqualTo("mcp__context7__query-docs");
        assertThat(JSON.readTree(toolUse.inputJson()).path("q").asText()).isEqualTo("streams");
    }

    @Test
    void parsesMarkerFormatWithHyphenatedNameNoSpaceBeforeArgs() {
        var result = convert("[tool:start] mcp__context7__query-docs{\"q\": \"x\"}");
        assertThat(toolUseOf(result).name()).isEqualTo("mcp__context7__query-docs");
    }

    @Test
    void markerWithoutArgsUsesEmptyObject() {
        var result = convert("[tool:start] bash [tool:stop]");
        var toolUse = toolUseOf(result);
        assertThat(toolUse.name()).isEqualTo("bash");
        assertThat(toolUse.inputJson()).isEqualTo("{}");
    }

    /** Finding 1: a literal "[tool:stop]" inside a string argument must not truncate the JSON. */
    @Test
    void stopMarkerInsideStringArgIsNotTreatedAsBoundary() throws Exception {
        String content = "before [tool:stop] after";
        var result = convert("[tool:start] write_file {\"path\": \"d.md\", \"content\": \""
                + content + "\"} [tool:stop]");
        var toolUse = toolUseOf(result);
        assertThat(toolUse.name()).isEqualTo("write_file");
        var args = JSON.readTree(toolUse.inputJson());
        assertThat(args.path("path").asText()).isEqualTo("d.md");
        assertThat(args.path("content").asText()).isEqualTo(content);
    }

    /** Finding 2: malformed args must not fire a tool call with silently-empty args. */
    @Test
    void markerWithMalformedArgsIsNotConverted() {
        var result = convert("[tool:start] bash {\"command\": \"ls\" broken");
        assertThat(result).isNull();
    }

    @Test
    void markerWithUnknownToolIsNotConverted() {
        var result = convert("[tool:start] not_a_real_tool {\"x\": 1} [tool:stop]");
        assertThat(result).isNull();
    }

    /** Finding (PR #519): an illustrative marker followed by more prose must not execute a tool. */
    @Test
    void markerFollowedByTrailingProseIsNotConverted() {
        var result = convert("For example you'd call [tool:start] bash {\"command\": \"ls\"} "
                + "and then read the output to decide what to do next.");
        assertThat(result).isNull();
    }

    /** Trailing prose after an explicit [tool:stop] is still extra content and must be rejected. */
    @Test
    void markerWithProseAfterStopIsNotConverted() {
        var result = convert("[tool:start] bash {\"command\": \"ls\"} [tool:stop] Now I'll analyze it.");
        assertThat(result).isNull();
    }

    /** Trailing prose after an args-less marker call must also be rejected. */
    @Test
    void markerWithoutArgsButTrailingProseIsNotConverted() {
        var result = convert("[tool:start] bash then something else happens");
        assertThat(result).isNull();
    }

    @Test
    void markerPreservesNonTextBlocks() {
        var loop = newLoop();
        var blocks = List.<ContentBlock>of(
                new ContentBlock.Thinking("reasoning"),
                new ContentBlock.Text("[tool:start] bash {\"command\": \"ls\"} [tool:stop]"));
        var result = loop.tryConvertTextToToolUse(blocks);
        assertThat(result).anyMatch(b -> b instanceof ContentBlock.Thinking);
        assertThat(result).noneMatch(b -> b instanceof ContentBlock.Text);
        assertThat(toolUseOf(result).name()).isEqualTo("bash");
    }

    // --- Bare-JSON format ---

    @Test
    void parsesBareJsonFormat() throws Exception {
        var result = convert("{\"name\": \"bash\", \"arguments\": {\"command\": \"pwd\"}}");
        var toolUse = toolUseOf(result);
        assertThat(toolUse.name()).isEqualTo("bash");
        assertThat(JSON.readTree(toolUse.inputJson()).path("command").asText()).isEqualTo("pwd");
    }

    @Test
    void bareJsonWithUnknownToolIsNotConverted() {
        assertThat(convert("{\"name\": \"nope\", \"arguments\": {}}")).isNull();
    }

    /**
     * Finding (PR #519): a valid bare-JSON tool call whose argument contains a literal marker must
     * be parsed as the outer call — the marker inside the string must not hijack execution.
     */
    @Test
    void bareJsonIsNotHijackedByMarkerInsideStringArg() throws Exception {
        var result = convert("{\"name\": \"write_file\", \"arguments\": {\"path\": \"a.md\", "
                + "\"content\": \"[tool:start] read_file {} [tool:stop]\"}}");
        var toolUse = toolUseOf(result);
        assertThat(toolUse.name()).isEqualTo("write_file");
        assertThat(JSON.readTree(toolUse.inputJson()).path("content").asText())
                .isEqualTo("[tool:start] read_file {} [tool:stop]");
    }

    // --- Unrecognized-tool-call WARN heuristic (Finding: PR #519) ---

    @Test
    void ordinaryJsonWithNameFieldIsNotFlagged() {
        assertThat(StreamingAgentLoop.looksLikeUnrecognizedToolCall("{\"name\":\"Alice\"}")).isFalse();
    }

    @Test
    void bareJsonToolCallShapeIsFlagged() {
        assertThat(StreamingAgentLoop.looksLikeUnrecognizedToolCall(
                "{\"name\":\"bash\",\"arguments\":{\"command\":\"ls\"}}")).isTrue();
    }

    @Test
    void markerTextIsFlagged() {
        assertThat(StreamingAgentLoop.looksLikeUnrecognizedToolCall(
                "some text [tool:start] bash")).isTrue();
    }

    @Test
    void plainTextIsNotFlagged() {
        assertThat(StreamingAgentLoop.looksLikeUnrecognizedToolCall("Here is your answer.")).isFalse();
    }

    // --- Non-matching / guard cases ---

    @Test
    void plainTextIsNotConverted() {
        assertThat(convert("Here is a summary of what I found.")).isNull();
    }

    @Test
    void emptyTextIsNotConverted() {
        assertThat(convert("   ")).isNull();
    }

    @Test
    void existingToolUseShortCircuits() {
        var loop = newLoop();
        var blocks = List.<ContentBlock>of(
                new ContentBlock.Text("[tool:start] bash {\"command\": \"ls\"} [tool:stop]"),
                new ContentBlock.ToolUse("existing", "bash", "{}"));
        assertThat(loop.tryConvertTextToToolUse(blocks)).isNull();
    }

    // --- Test doubles ---

    private static final class StubTool implements Tool {
        private final String name;

        StubTool(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return name + " stub";
        }

        @Override
        public com.fasterxml.jackson.databind.JsonNode inputSchema() {
            return JSON.createObjectNode();
        }

        @Override
        public ToolResult execute(String inputJson) {
            return new ToolResult("ok", false);
        }
    }

    private static final class NoopLlmClient implements LlmClient {
        @Override
        public LlmResponse sendMessage(LlmRequest request) throws LlmException {
            throw new UnsupportedOperationException("not used in these tests");
        }

        @Override
        public StreamSession streamMessage(LlmRequest request) throws LlmException {
            throw new UnsupportedOperationException("not used in these tests");
        }

        @Override
        public String provider() {
            return "stub";
        }

        @Override
        public String defaultModel() {
            return "model";
        }
    }
}

package dev.aceclaw.core.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.llm.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test locking the invariant: every assistant message containing
 * {@link ContentBlock.ToolUse} blocks MUST be immediately followed by a user
 * message with matching {@link ContentBlock.ToolResult} blocks.
 *
 * <p>Violation of this invariant causes Anthropic API HTTP 400:
 * "tool_use ids were found without tool_result blocks immediately after".
 */
class ToolUseToolResultInvariantTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ---- AgentLoop (non-streaming) ----

    @Test
    void agentLoop_toolUseAlwaysFollowedByToolResult() throws Exception {
        // LLM returns two parallel tool_use blocks, then final text
        var toolUse1 = new ContentBlock.ToolUse("tu-1", "tool_a", "{}");
        var toolUse2 = new ContentBlock.ToolUse("tu-2", "tool_b", "{}");
        var llm = new FakeLlmClient(List.of(
                new LlmResponse("r1", "model", List.of(toolUse1, toolUse2),
                        StopReason.TOOL_USE, new Usage(10, 5, 0, 0)),
                new LlmResponse("r2", "model", List.of(new ContentBlock.Text("Done")),
                        StopReason.END_TURN, new Usage(10, 5, 0, 0))
        ));

        var registry = new ToolRegistry();
        registry.register(new StubTool("tool_a", "result_a"));
        registry.register(new StubTool("tool_b", "result_b"));

        var loop = new AgentLoop(llm, registry, "model", null);
        var turn = loop.runTurn("go", List.of());

        assertToolUseToolResultInvariant(turn.newMessages());
    }

    @Test
    void agentLoop_missingToolStillProducesToolResult() throws Exception {
        // LLM requests a tool that isn't registered — should NOT orphan the tool_use
        var toolUse = new ContentBlock.ToolUse("tu-1", "nonexistent_tool", "{}");
        var llm = new FakeLlmClient(List.of(
                new LlmResponse("r1", "model", List.of(toolUse),
                        StopReason.TOOL_USE, new Usage(10, 5, 0, 0)),
                new LlmResponse("r2", "model", List.of(new ContentBlock.Text("OK")),
                        StopReason.END_TURN, new Usage(10, 5, 0, 0))
        ));

        var loop = new AgentLoop(llm, new ToolRegistry(), "model", null);
        var turn = loop.runTurn("go", List.of());

        assertToolUseToolResultInvariant(turn.newMessages());
    }

    @Test
    void agentLoop_multiTurnToolUse_invariantHoldsAcrossIterations() throws Exception {
        // Two consecutive tool_use rounds before final text
        var tu1 = new ContentBlock.ToolUse("tu-1", "step1", "{}");
        var tu2 = new ContentBlock.ToolUse("tu-2", "step2", "{}");
        var llm = new FakeLlmClient(List.of(
                new LlmResponse("r1", "model", List.of(tu1),
                        StopReason.TOOL_USE, new Usage(1, 1, 0, 0)),
                new LlmResponse("r2", "model", List.of(tu2),
                        StopReason.TOOL_USE, new Usage(1, 1, 0, 0)),
                new LlmResponse("r3", "model", List.of(new ContentBlock.Text("All done")),
                        StopReason.END_TURN, new Usage(1, 1, 0, 0))
        ));

        var registry = new ToolRegistry();
        registry.register(new StubTool("step1", "ok1"));
        registry.register(new StubTool("step2", "ok2"));

        var loop = new AgentLoop(llm, registry, "model", null);
        var turn = loop.runTurn("multi-step", List.of());

        assertToolUseToolResultInvariant(turn.newMessages());
        // 6 messages: user(prompt), asst(tu1), user(tr1), asst(tu2), user(tr2), asst(text)
        assertThat(turn.newMessages()).hasSize(6);
    }

    @Test
    void agentLoop_parallelToolsWithPermissionDenied_invariantHolds() throws Exception {
        // Two parallel tools, both denied
        var tu1 = new ContentBlock.ToolUse("tu-1", "denied_a", "{}");
        var tu2 = new ContentBlock.ToolUse("tu-2", "denied_b", "{}");
        var llm = new FakeLlmClient(List.of(
                new LlmResponse("r1", "model", List.of(tu1, tu2),
                        StopReason.TOOL_USE, new Usage(1, 1, 0, 0)),
                new LlmResponse("r2", "model", List.of(new ContentBlock.Text("OK")),
                        StopReason.END_TURN, new Usage(1, 1, 0, 0))
        ));

        var registry = new ToolRegistry();
        registry.register(new StubTool("denied_a", "x"));
        registry.register(new StubTool("denied_b", "x"));

        var config = AgentLoopConfig.builder()
                .permissionChecker((name, input) -> ToolPermissionResult.denied("nope"))
                .build();

        var loop = new AgentLoop(llm, registry, "model", null, config);
        var turn = loop.runTurn("denied", List.of());

        assertToolUseToolResultInvariant(turn.newMessages());
    }

    @Test
    void agentLoop_maxIterationsReached_invariantStillHolds() throws Exception {
        // LLM always returns tool_use, maxIterations=2 caps the loop
        var callCount = new java.util.concurrent.atomic.AtomicInteger();
        var llm = new LlmClient() {
            @Override public String provider() { return "test"; }
            @Override public String defaultModel() { return "m"; }
            @Override
            public LlmResponse sendMessage(LlmRequest request) {
                int seq = callCount.incrementAndGet();
                return new LlmResponse("id-" + seq, "m",
                        List.of(new ContentBlock.ToolUse("tu-" + seq, "looping", "{}")),
                        StopReason.TOOL_USE, new Usage(1, 1, 0, 0));
            }
            @Override
            public StreamSession streamMessage(LlmRequest request) {
                throw new UnsupportedOperationException();
            }
        };

        var registry = new ToolRegistry();
        registry.register(new StubTool("looping", "ok"));

        var config = AgentLoopConfig.builder().maxIterations(2).build();
        var loop = new AgentLoop(llm, registry, "m", null, config);
        var turn = loop.runTurn("loop forever", List.of());

        assertToolUseToolResultInvariant(turn.newMessages());
        assertThat(turn.maxIterationsReached()).isTrue();
    }

    // ---- Invariant assertion ----

    /**
     * Asserts the tool_use / tool_result pairing invariant on a message list.
     *
     * <p>For every AssistantMessage containing ToolUse blocks, the immediately
     * following message must be a UserMessage whose ToolResult IDs exactly match
     * the ToolUse IDs.
     */
    private static void assertToolUseToolResultInvariant(List<Message> messages) {
        for (int i = 0; i < messages.size(); i++) {
            if (!(messages.get(i) instanceof Message.AssistantMessage asst)) continue;

            Set<String> toolUseIds = asst.content().stream()
                    .filter(b -> b instanceof ContentBlock.ToolUse)
                    .map(b -> ((ContentBlock.ToolUse) b).id())
                    .collect(Collectors.toSet());

            if (toolUseIds.isEmpty()) continue;

            // Must have a following message
            assertThat(i + 1)
                    .withFailMessage("AssistantMessage at index %d has tool_use but no following message", i)
                    .isLessThan(messages.size());

            // Following message must be UserMessage with ToolResult blocks
            var next = messages.get(i + 1);
            assertThat(next)
                    .withFailMessage("Message at index %d after tool_use must be UserMessage, was %s",
                            i + 1, next.getClass().getSimpleName())
                    .isInstanceOf(Message.UserMessage.class);

            Set<String> toolResultIds = ((Message.UserMessage) next).content().stream()
                    .filter(b -> b instanceof ContentBlock.ToolResult)
                    .map(b -> ((ContentBlock.ToolResult) b).toolUseId())
                    .collect(Collectors.toSet());

            assertThat(toolResultIds)
                    .withFailMessage(
                            "ToolResult IDs %s at index %d don't match ToolUse IDs %s at index %d",
                            toolResultIds, i + 1, toolUseIds, i)
                    .isEqualTo(toolUseIds);
        }
    }

    // ---- Helpers ----

    private static class FakeLlmClient implements LlmClient {
        private final List<LlmResponse> responses;
        private int callIndex = 0;

        FakeLlmClient(List<LlmResponse> responses) {
            this.responses = new ArrayList<>(responses);
        }

        @Override public String provider() { return "test"; }
        @Override public String defaultModel() { return "test-model"; }

        @Override
        public LlmResponse sendMessage(LlmRequest request) {
            if (callIndex >= responses.size()) {
                return new LlmResponse("id", "model", List.of(new ContentBlock.Text("fallback")),
                        StopReason.END_TURN, new Usage(0, 0, 0, 0));
            }
            return responses.get(callIndex++);
        }

        @Override
        public StreamSession streamMessage(LlmRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    private static class StubTool implements Tool {
        private final String toolName;
        private final String output;

        StubTool(String toolName, String output) {
            this.toolName = toolName;
            this.output = output;
        }

        @Override public String name() { return toolName; }
        @Override public String description() { return "stub"; }
        @Override public JsonNode inputSchema() {
            return JSON.createObjectNode().put("type", "object");
        }
        @Override public ToolResult execute(String inputJson) {
            return new ToolResult(output, false);
        }
    }
}

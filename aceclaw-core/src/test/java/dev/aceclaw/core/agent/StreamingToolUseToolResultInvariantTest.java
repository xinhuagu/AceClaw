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
 * <p>This is the streaming counterpart of {@link ToolUseToolResultInvariantTest},
 * testing {@link StreamingAgentLoop} which is the path that actually hit the
 * original HTTP 400 bug from the Anthropic API.
 */
class StreamingToolUseToolResultInvariantTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void streamingLoop_toolUseAlwaysFollowedByToolResult() throws Exception {
        var responses = List.of(
                toolUseStreamResponse("tu-1", "tool_a", "{}",
                        "tu-2", "tool_b", "{}"),
                textStreamResponse("Done")
        );
        var llm = new FakeStreamingLlmClient(responses);

        var registry = new ToolRegistry();
        registry.register(new StubTool("tool_a", "result_a"));
        registry.register(new StubTool("tool_b", "result_b"));

        var loop = new StreamingAgentLoop(llm, registry, "model", null);
        var turn = loop.runTurn("go", List.of(), new StreamEventHandler() {});

        assertToolUseToolResultInvariant(turn.newMessages());
    }

    @Test
    void streamingLoop_missingToolStillProducesToolResult() throws Exception {
        var responses = List.of(
                toolUseStreamResponse("tu-1", "nonexistent_tool", "{}"),
                textStreamResponse("OK")
        );
        var llm = new FakeStreamingLlmClient(responses);

        var loop = new StreamingAgentLoop(llm, new ToolRegistry(), "model", null);
        var turn = loop.runTurn("go", List.of(), new StreamEventHandler() {});

        assertToolUseToolResultInvariant(turn.newMessages());
    }

    @Test
    void streamingLoop_multiTurnToolUse_invariantHoldsAcrossIterations() throws Exception {
        var responses = List.of(
                toolUseStreamResponse("tu-1", "step1", "{}"),
                toolUseStreamResponse("tu-2", "step2", "{}"),
                textStreamResponse("All done")
        );
        var llm = new FakeStreamingLlmClient(responses);

        var registry = new ToolRegistry();
        registry.register(new StubTool("step1", "ok1"));
        registry.register(new StubTool("step2", "ok2"));

        var loop = new StreamingAgentLoop(llm, registry, "model", null);
        var turn = loop.runTurn("multi-step", List.of(), new StreamEventHandler() {});

        assertToolUseToolResultInvariant(turn.newMessages());
        // 6 messages: user(prompt), asst(tu1), user(tr1), asst(tu2), user(tr2), asst(text)
        assertThat(turn.newMessages()).hasSize(6);
    }

    @Test
    void streamingLoop_parallelToolsWithPermissionDenied_invariantHolds() throws Exception {
        var responses = List.of(
                toolUseStreamResponse("tu-1", "denied_a", "{}",
                        "tu-2", "denied_b", "{}"),
                textStreamResponse("OK")
        );
        var llm = new FakeStreamingLlmClient(responses);

        var registry = new ToolRegistry();
        registry.register(new StubTool("denied_a", "x"));
        registry.register(new StubTool("denied_b", "x"));

        var config = AgentLoopConfig.builder()
                .permissionChecker((name, input) -> ToolPermissionResult.denied("nope"))
                .build();

        var loop = new StreamingAgentLoop(llm, registry, "model", null,
                16384, 0, null, config);
        var turn = loop.runTurn("denied", List.of(), new StreamEventHandler() {});

        assertToolUseToolResultInvariant(turn.newMessages());
    }

    /**
     * Directly exercises the parallel scope {@code catch (Exception e)} at
     * {@link StreamingAgentLoop#executeTools} line 589.
     *
     * <p>When a tool throws {@link Error} (not Exception), it escapes
     * {@code executeSingleTool}'s {@code catch (Exception)}. Inside the
     * {@link java.util.concurrent.StructuredTaskScope.ShutdownOnFailure},
     * the subtask enters FAILED state, causing {@code Subtask::get()} to
     * throw {@link IllegalStateException} — which IS caught by the
     * parallel scope's {@code catch (Exception e)}.
     */
    @Test
    void streamingLoop_parallelToolThrowsError_safetyNetProducesFallbackResults() throws Exception {
        var responses = List.of(
                toolUseStreamResponse("tu-1", "normal_tool", "{}",
                        "tu-2", "error_tool", "{}"),
                textStreamResponse("Recovered")
        );
        var llm = new FakeStreamingLlmClient(responses);

        var registry = new ToolRegistry();
        registry.register(new StubTool("normal_tool", "ok"));
        registry.register(new ErrorThrowingTool("error_tool"));

        var loop = new StreamingAgentLoop(llm, registry, "model", null);
        var turn = loop.runTurn("go", List.of(), new StreamEventHandler() {});

        assertToolUseToolResultInvariant(turn.newMessages());
        // Verify the fallback results contain error info
        var toolResultMsg = (Message.UserMessage) turn.newMessages().get(2);
        var results = toolResultMsg.content().stream()
                .filter(b -> b instanceof ContentBlock.ToolResult)
                .map(b -> (ContentBlock.ToolResult) b)
                .toList();
        assertThat(results).hasSize(2);
        assertThat(results).allMatch(ContentBlock.ToolResult::isError);
    }

    @Test
    void streamingLoop_maxIterationsReached_invariantStillHolds() throws Exception {
        // LLM always returns tool_use, maxIterations=2 caps the loop
        var callCount = new java.util.concurrent.atomic.AtomicInteger();
        var llm = new LlmClient() {
            @Override public String provider() { return "test"; }
            @Override public String defaultModel() { return "m"; }
            @Override
            public LlmResponse sendMessage(LlmRequest request) {
                throw new UnsupportedOperationException();
            }
            @Override
            public StreamSession streamMessage(LlmRequest request) {
                int seq = callCount.incrementAndGet();
                return new FakeStreamSession(
                        toolUseEvents("tu-" + seq, "looping", "{}"));
            }
        };

        var registry = new ToolRegistry();
        registry.register(new StubTool("looping", "ok"));

        var config = AgentLoopConfig.builder().maxIterations(2).build();
        var loop = new StreamingAgentLoop(llm, registry, "m", null,
                16384, 0, null, config);
        var turn = loop.runTurn("loop forever", List.of(), new StreamEventHandler() {});

        assertToolUseToolResultInvariant(turn.newMessages());
        assertThat(turn.maxIterationsReached()).isTrue();
    }

    @Test
    void streamingLoop_cancellationDuringToolUseStream_generatesPlaceholderToolResults() throws Exception {
        // LLM returns tool_use, but we cancel before tool execution
        var responses = List.of(
                toolUseStreamResponse("tu-1", "write_file", "{}",
                        "tu-2", "write_file", "{}")
        );
        var llm = new FakeStreamingLlmClient(responses);

        var registry = new ToolRegistry();
        registry.register(new StubTool("write_file", "ok"));

        var cancellationToken = new CancellationToken();
        // Cancel immediately after stream completes (before tool execution)
        var handler = new StreamEventHandler() {
            @Override
            public void onComplete(StreamEvent.StreamComplete event) {
                cancellationToken.cancel();
            }
        };

        var loop = new StreamingAgentLoop(llm, registry, "model", null);
        var turn = loop.runTurn("write files", List.of(), handler, cancellationToken);

        // The invariant must hold: every tool_use has a matching tool_result
        assertToolUseToolResultInvariant(turn.newMessages());

        // Verify the placeholder results are marked as errors
        var lastMsg = turn.newMessages().getLast();
        assertThat(lastMsg).isInstanceOf(Message.UserMessage.class);
        var results = ((Message.UserMessage) lastMsg).content().stream()
                .filter(b -> b instanceof ContentBlock.ToolResult)
                .map(b -> (ContentBlock.ToolResult) b)
                .toList();
        assertThat(results).hasSize(2);
        assertThat(results).allMatch(ContentBlock.ToolResult::isError);
        assertThat(results).extracting(ContentBlock.ToolResult::toolUseId)
                .containsExactlyInAnyOrder("tu-1", "tu-2");
    }

    // ---- Invariant assertion (same logic as non-streaming test) ----

    private static void assertToolUseToolResultInvariant(List<Message> messages) {
        for (int i = 0; i < messages.size(); i++) {
            if (!(messages.get(i) instanceof Message.AssistantMessage asst)) continue;

            Set<String> toolUseIds = asst.content().stream()
                    .filter(b -> b instanceof ContentBlock.ToolUse)
                    .map(b -> ((ContentBlock.ToolUse) b).id())
                    .collect(Collectors.toSet());

            if (toolUseIds.isEmpty()) continue;

            assertThat(i + 1)
                    .withFailMessage("AssistantMessage at index %d has tool_use but no following message", i)
                    .isLessThan(messages.size());

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

    /**
     * Builds stream events for a single tool_use response.
     */
    private static List<StreamEvent> toolUseEvents(String id, String name, String inputJson) {
        var events = new ArrayList<StreamEvent>();
        events.add(new StreamEvent.MessageStart("msg-1", "model"));
        events.add(new StreamEvent.ContentBlockStart(0,
                new ContentBlock.ToolUse(id, name, "")));
        events.add(new StreamEvent.ToolUseDelta(0, name, inputJson));
        events.add(new StreamEvent.ContentBlockStop(0));
        events.add(new StreamEvent.MessageDelta(
                StopReason.TOOL_USE, new Usage(10, 5, 0, 0)));
        events.add(new StreamEvent.StreamComplete());
        return events;
    }

    /**
     * Builds stream events for a response with two parallel tool_use blocks.
     */
    private static List<StreamEvent> toolUseStreamResponse(String id1, String name1, String input1,
                                                            String id2, String name2, String input2) {
        var events = new ArrayList<StreamEvent>();
        events.add(new StreamEvent.MessageStart("msg-1", "model"));
        // First tool_use block
        events.add(new StreamEvent.ContentBlockStart(0,
                new ContentBlock.ToolUse(id1, name1, "")));
        events.add(new StreamEvent.ToolUseDelta(0, name1, input1));
        events.add(new StreamEvent.ContentBlockStop(0));
        // Second tool_use block
        events.add(new StreamEvent.ContentBlockStart(1,
                new ContentBlock.ToolUse(id2, name2, "")));
        events.add(new StreamEvent.ToolUseDelta(1, name2, input2));
        events.add(new StreamEvent.ContentBlockStop(1));
        events.add(new StreamEvent.MessageDelta(
                StopReason.TOOL_USE, new Usage(10, 5, 0, 0)));
        events.add(new StreamEvent.StreamComplete());
        return events;
    }

    /**
     * Builds stream events for a single tool_use response (overload for single tool).
     */
    private static List<StreamEvent> toolUseStreamResponse(String id, String name, String inputJson) {
        return toolUseEvents(id, name, inputJson);
    }

    /**
     * Builds stream events for a text-only response.
     */
    private static List<StreamEvent> textStreamResponse(String text) {
        var events = new ArrayList<StreamEvent>();
        events.add(new StreamEvent.MessageStart("msg-1", "model"));
        events.add(new StreamEvent.ContentBlockStart(0, new ContentBlock.Text("")));
        events.add(new StreamEvent.TextDelta(text));
        events.add(new StreamEvent.ContentBlockStop(0));
        events.add(new StreamEvent.MessageDelta(
                StopReason.END_TURN, new Usage(10, 5, 0, 0)));
        events.add(new StreamEvent.StreamComplete());
        return events;
    }

    /**
     * A fake StreamSession that replays canned events.
     */
    private static class FakeStreamSession implements StreamSession {
        private final List<StreamEvent> events;

        FakeStreamSession(List<StreamEvent> events) {
            this.events = List.copyOf(events);
        }

        @Override
        public void onEvent(StreamEventHandler handler) {
            for (var event : events) {
                switch (event) {
                    case StreamEvent.MessageStart e -> handler.onMessageStart(e);
                    case StreamEvent.ContentBlockStart e -> handler.onContentBlockStart(e);
                    case StreamEvent.TextDelta e -> handler.onTextDelta(e);
                    case StreamEvent.ThinkingDelta e -> handler.onThinkingDelta(e);
                    case StreamEvent.ToolUseDelta e -> handler.onToolUseDelta(e);
                    case StreamEvent.ContentBlockStop e -> handler.onContentBlockStop(e);
                    case StreamEvent.MessageDelta e -> handler.onMessageDelta(e);
                    case StreamEvent.StreamComplete e -> handler.onComplete(e);
                    case StreamEvent.StreamError e -> handler.onError(e);
                    case StreamEvent.Heartbeat e -> handler.onHeartbeat(e);
                }
            }
        }

        @Override
        public void cancel() {}
    }

    /**
     * A fake streaming LLM client that returns canned stream responses in sequence.
     */
    private static class FakeStreamingLlmClient implements LlmClient {
        private final List<List<StreamEvent>> responses;
        private int callIndex = 0;

        FakeStreamingLlmClient(List<List<StreamEvent>> responses) {
            this.responses = new ArrayList<>(responses);
        }

        @Override public String provider() { return "test"; }
        @Override public String defaultModel() { return "test-model"; }

        @Override
        public LlmResponse sendMessage(LlmRequest request) {
            throw new UnsupportedOperationException("Use streamMessage");
        }

        @Override
        public StreamSession streamMessage(LlmRequest request) {
            if (callIndex >= responses.size()) {
                return new FakeStreamSession(textStreamResponse("fallback"));
            }
            return new FakeStreamSession(responses.get(callIndex++));
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

    /**
     * A tool that throws {@link Error} (not Exception) to bypass
     * {@code executeSingleTool}'s {@code catch (Exception)} and force
     * the structured scope's catch path.
     */
    private static class ErrorThrowingTool implements Tool {
        private final String toolName;

        ErrorThrowingTool(String toolName) {
            this.toolName = toolName;
        }

        @Override public String name() { return toolName; }
        @Override public String description() { return "throws Error"; }
        @Override public JsonNode inputSchema() {
            return JSON.createObjectNode().put("type", "object");
        }
        @Override public ToolResult execute(String inputJson) {
            throw new Error("Simulated fatal tool failure");
        }
    }
}

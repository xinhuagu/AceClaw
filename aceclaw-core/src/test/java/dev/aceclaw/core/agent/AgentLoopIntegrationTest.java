package dev.aceclaw.core.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.llm.*;
import dev.aceclaw.infra.event.AgentEvent;
import dev.aceclaw.infra.event.AceClawEvent;
import dev.aceclaw.infra.event.EventBus;
import dev.aceclaw.infra.event.ToolEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentLoopIntegrationTest {

    private EventBus eventBus;
    private final List<AceClawEvent> capturedEvents = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        eventBus = new EventBus();
        eventBus.start();
        eventBus.subscribe(AgentEvent.class, capturedEvents::add);
        eventBus.subscribe(ToolEvent.class, capturedEvents::add);
    }

    @AfterEach
    void tearDown() {
        eventBus.stop();
    }

    @Test
    void publishesTurnStartedAndCompleted() throws Exception {
        var llm = new FakeLlmClient(List.of(
                new LlmResponse("id", "model", List.of(new ContentBlock.Text("Hello")),
                        StopReason.END_TURN, new Usage(10, 5, 0, 0))
        ));
        var registry = new ToolRegistry();
        var config = AgentLoopConfig.builder()
                .sessionId("test-session")
                .eventBus(eventBus)
                .build();

        var loop = new AgentLoop(llm, registry, "test-model", "system", config);
        var turn = loop.runTurn("hi", List.of());

        assertThat(turn.text()).isEqualTo("Hello");

        // Give async event bus time to deliver
        Thread.sleep(100);

        var agentEvents = capturedEvents.stream()
                .filter(e -> e instanceof AgentEvent)
                .toList();
        assertThat(agentEvents).hasSize(2);
        assertThat(agentEvents.get(0)).isInstanceOf(AgentEvent.TurnStarted.class);
        assertThat(agentEvents.get(1)).isInstanceOf(AgentEvent.TurnCompleted.class);

        var started = (AgentEvent.TurnStarted) agentEvents.get(0);
        assertThat(started.sessionId()).isEqualTo("test-session");
        assertThat(started.turnNumber()).isEqualTo(1);

        var completed = (AgentEvent.TurnCompleted) agentEvents.get(1);
        assertThat(completed.sessionId()).isEqualTo("test-session");
        assertThat(completed.durationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void publishesTurnErrorOnLlmException() {
        var llm = new FakeLlmClient(List.of()); // will throw
        llm.throwOnNext = new LlmException("API error", 500);
        var registry = new ToolRegistry();
        var config = AgentLoopConfig.builder()
                .sessionId("err-session")
                .eventBus(eventBus)
                .build();

        var loop = new AgentLoop(llm, registry, "test-model", null, config);

        assertThatThrownBy(() -> loop.runTurn("hi", List.of()))
                .isInstanceOf(LlmException.class);

        // Give async event bus time to deliver
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}

        var errorEvents = capturedEvents.stream()
                .filter(e -> e instanceof AgentEvent.TurnError)
                .toList();
        assertThat(errorEvents).hasSize(1);
        var error = (AgentEvent.TurnError) errorEvents.get(0);
        assertThat(error.sessionId()).isEqualTo("err-session");
        assertThat(error.error()).contains("API error");
    }

    @Test
    void publishesToolEvents() throws Exception {
        // First response: tool use, second response: final text
        var toolUse = new ContentBlock.ToolUse("t1", "echo", "{}");
        var llm = new FakeLlmClient(List.of(
                new LlmResponse("id", "model", List.of(toolUse),
                        StopReason.TOOL_USE, new Usage(10, 5, 0, 0)),
                new LlmResponse("id", "model", List.of(new ContentBlock.Text("Done")),
                        StopReason.END_TURN, new Usage(10, 5, 0, 0))
        ));

        var registry = new ToolRegistry();
        registry.register(new StubTool("echo", "echoed"));

        var config = AgentLoopConfig.builder()
                .sessionId("tool-session")
                .eventBus(eventBus)
                .build();

        var loop = new AgentLoop(llm, registry, "model", null, config);
        loop.runTurn("do something", List.of());

        Thread.sleep(100);

        var toolEvents = capturedEvents.stream()
                .filter(e -> e instanceof ToolEvent)
                .toList();
        assertThat(toolEvents).hasSize(2);
        assertThat(toolEvents.get(0)).isInstanceOf(ToolEvent.Invoked.class);
        assertThat(toolEvents.get(1)).isInstanceOf(ToolEvent.Completed.class);

        var invoked = (ToolEvent.Invoked) toolEvents.get(0);
        assertThat(invoked.toolName()).isEqualTo("echo");

        var completed = (ToolEvent.Completed) toolEvents.get(1);
        assertThat(completed.toolName()).isEqualTo("echo");
        assertThat(completed.isError()).isFalse();
    }

    @Test
    void permissionDeniedReturnsErrorResult() throws Exception {
        var toolUse = new ContentBlock.ToolUse("t1", "dangerous_tool", "{}");
        var llm = new FakeLlmClient(List.of(
                new LlmResponse("id", "model", List.of(toolUse),
                        StopReason.TOOL_USE, new Usage(10, 5, 0, 0)),
                new LlmResponse("id", "model", List.of(new ContentBlock.Text("OK")),
                        StopReason.END_TURN, new Usage(10, 5, 0, 0))
        ));

        var registry = new ToolRegistry();
        registry.register(new StubTool("dangerous_tool", "should not run"));

        var config = AgentLoopConfig.builder()
                .sessionId("perm-session")
                .eventBus(eventBus)
                .permissionChecker((toolName, input) ->
                        ToolPermissionResult.denied("Not allowed"))
                .build();

        var loop = new AgentLoop(llm, registry, "model", null, config);
        var turn = loop.runTurn("do dangerous thing", List.of());

        Thread.sleep(100);

        // Should have permission denied event
        var denied = capturedEvents.stream()
                .filter(e -> e instanceof ToolEvent.PermissionDenied)
                .toList();
        assertThat(denied).hasSize(1);
        var pd = (ToolEvent.PermissionDenied) denied.get(0);
        assertThat(pd.toolName()).isEqualTo("dangerous_tool");
        assertThat(pd.reason()).isEqualTo("Not allowed");
    }

    @Test
    void permissionAllowedExecutesTool() throws Exception {
        var toolUse = new ContentBlock.ToolUse("t1", "safe_tool", "{}");
        var llm = new FakeLlmClient(List.of(
                new LlmResponse("id", "model", List.of(toolUse),
                        StopReason.TOOL_USE, new Usage(10, 5, 0, 0)),
                new LlmResponse("id", "model", List.of(new ContentBlock.Text("Done")),
                        StopReason.END_TURN, new Usage(10, 5, 0, 0))
        ));

        var registry = new ToolRegistry();
        registry.register(new StubTool("safe_tool", "executed"));

        var config = AgentLoopConfig.builder()
                .sessionId("perm-ok")
                .eventBus(eventBus)
                .permissionChecker((toolName, input) -> ToolPermissionResult.ALLOWED)
                .build();

        var loop = new AgentLoop(llm, registry, "model", null, config);
        loop.runTurn("do thing", List.of());

        Thread.sleep(100);

        var invoked = capturedEvents.stream()
                .filter(e -> e instanceof ToolEvent.Invoked)
                .toList();
        assertThat(invoked).hasSize(1);

        var completed = capturedEvents.stream()
                .filter(e -> e instanceof ToolEvent.Completed)
                .map(e -> (ToolEvent.Completed) e)
                .toList();
        assertThat(completed).hasSize(1);
        assertThat(completed.get(0).isError()).isFalse();
    }

    @Test
    void worksWithoutConfig() throws Exception {
        var llm = new FakeLlmClient(List.of(
                new LlmResponse("id", "model", List.of(new ContentBlock.Text("Hello")),
                        StopReason.END_TURN, new Usage(10, 5, 0, 0))
        ));
        var registry = new ToolRegistry();

        // Use original constructor — no config
        var loop = new AgentLoop(llm, registry, "model", null);
        var turn = loop.runTurn("hi", List.of());
        assertThat(turn.text()).isEqualTo("Hello");
    }

    @Test
    void maxIterationsFromConfigCapsLoop() throws Exception {
        var llmCalls = new AtomicInteger();
        var llm = new LlmClient() {
            @Override
            public String provider() {
                return "test";
            }

            @Override
            public String defaultModel() {
                return "test-model";
            }

            @Override
            public LlmResponse sendMessage(LlmRequest request) {
                int seq = llmCalls.incrementAndGet();
                var toolUse = new ContentBlock.ToolUse("t" + seq, "missing_tool", "{}");
                return new LlmResponse(
                        "id-" + seq, "model", List.of(toolUse), StopReason.TOOL_USE, new Usage(1, 1, 0, 0));
            }

            @Override
            public StreamSession streamMessage(LlmRequest request) {
                throw new UnsupportedOperationException();
            }
        };

        var config = AgentLoopConfig.builder()
                .maxIterations(3)
                .build();
        var loop = new AgentLoop(llm, new ToolRegistry(), "model", null, config);

        var turn = loop.runTurn("loop", List.of());
        assertThat(llmCalls.get()).isEqualTo(3);
        assertThat(turn.finalStopReason()).isEqualTo(StopReason.END_TURN);
    }

    @Test
    void singleLlmCallReportsOneRequest() throws Exception {
        var llm = new FakeLlmClient(List.of(
                new LlmResponse("id", "model", List.of(new ContentBlock.Text("Hello")),
                        StopReason.END_TURN, new Usage(10, 5, 0, 0))
        ));
        var loop = new AgentLoop(llm, new ToolRegistry(), "model", null);
        var turn = loop.runTurn("hi", List.of());

        assertThat(turn.llmRequestCount()).isEqualTo(1);
    }

    @Test
    void multiIterationToolUseCountsAllLlmRequests() throws Exception {
        var toolUse = new ContentBlock.ToolUse("t1", "echo", "{}");
        var llm = new FakeLlmClient(List.of(
                new LlmResponse("id", "model", List.of(toolUse),
                        StopReason.TOOL_USE, new Usage(10, 5, 0, 0)),
                new LlmResponse("id", "model", List.of(new ContentBlock.Text("Done")),
                        StopReason.END_TURN, new Usage(10, 5, 0, 0))
        ));

        var registry = new ToolRegistry();
        registry.register(new StubTool("echo", "echoed"));

        var loop = new AgentLoop(llm, registry, "model", null);
        var turn = loop.runTurn("do something", List.of());

        assertThat(turn.llmRequestCount()).isEqualTo(2);
    }

    @Test
    void maxIterationsReachedCountsAllLlmRequests() throws Exception {
        var llmCalls = new AtomicInteger();
        var llm = new LlmClient() {
            @Override public String provider() { return "test"; }
            @Override public String defaultModel() { return "test-model"; }

            @Override
            public LlmResponse sendMessage(LlmRequest request) {
                int seq = llmCalls.incrementAndGet();
                var toolUse = new ContentBlock.ToolUse("t" + seq, "missing_tool", "{}");
                return new LlmResponse(
                        "id-" + seq, "model", List.of(toolUse), StopReason.TOOL_USE, new Usage(1, 1, 0, 0));
            }

            @Override
            public StreamSession streamMessage(LlmRequest request) {
                throw new UnsupportedOperationException();
            }
        };

        var config = AgentLoopConfig.builder().maxIterations(5).build();
        var loop = new AgentLoop(llm, new ToolRegistry(), "model", null, config);
        var turn = loop.runTurn("loop forever", List.of());

        assertThat(turn.maxIterationsReached()).isTrue();
        assertThat(turn.llmRequestCount()).isEqualTo(5);
    }

    // -- Test helpers --

    private static final ObjectMapper JSON = new ObjectMapper();

    private static class FakeLlmClient implements LlmClient {
        private final List<LlmResponse> responses;
        private int callIndex = 0;
        LlmException throwOnNext;

        FakeLlmClient(List<LlmResponse> responses) {
            this.responses = new ArrayList<>(responses);
        }

        @Override public String provider() { return "test"; }
        @Override public String defaultModel() { return "test-model"; }

        @Override
        public LlmResponse sendMessage(LlmRequest request) throws LlmException {
            if (throwOnNext != null) throw throwOnNext;
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

package dev.aceclaw.core.planner;

import dev.aceclaw.core.agent.CancellationToken;
import dev.aceclaw.core.agent.StreamingAgentLoop;
import dev.aceclaw.core.agent.ToolRegistry;
import dev.aceclaw.core.llm.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SequentialPlanExecutorTest {

    /**
     * Simple mock LlmClient that returns canned streaming text responses.
     */
    static class SimpleMockLlmClient implements LlmClient {
        private final ConcurrentLinkedQueue<Object> responses = new ConcurrentLinkedQueue<>();

        void enqueueTextResponse(String text) {
            responses.add(List.of(
                    new StreamEvent.MessageStart("msg-mock", "mock-model"),
                    new StreamEvent.ContentBlockStart(0, new ContentBlock.Text("")),
                    new StreamEvent.TextDelta(text),
                    new StreamEvent.ContentBlockStop(0),
                    new StreamEvent.MessageDelta(StopReason.END_TURN, new Usage(100, 50)),
                    new StreamEvent.StreamComplete()));
        }

        void enqueueError() {
            responses.add(List.of(
                    new StreamEvent.MessageStart("msg-mock", "mock-model"),
                    new StreamEvent.StreamError(new LlmException("Step failed", 500))));
        }

        @Override
        public LlmResponse sendMessage(LlmRequest request) {
            return new LlmResponse("id", "model", List.of(new ContentBlock.Text("ok")),
                    StopReason.END_TURN, new Usage(10, 5));
        }

        @SuppressWarnings("unchecked")
        @Override
        public StreamSession streamMessage(LlmRequest request) throws LlmException {
            var next = responses.poll();
            if (next == null) throw new LlmException("No mock responses", 500);
            var events = (List<StreamEvent>) next;
            return new SimpleStreamSession(events);
        }

        @Override
        public String provider() { return "mock"; }

        @Override
        public String defaultModel() { return "mock-model"; }
    }

    static class SimpleStreamSession implements StreamSession {
        private final List<StreamEvent> events;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        SimpleStreamSession(List<StreamEvent> events) { this.events = events; }

        @Override
        public void onEvent(StreamEventHandler handler) {
            for (var event : events) {
                if (cancelled.get()) return;
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
        public void cancel() { cancelled.set(true); }
    }

    private StreamingAgentLoop createLoop(SimpleMockLlmClient client) {
        return new StreamingAgentLoop(client, new ToolRegistry(), "mock-model", null);
    }

    private TaskPlan createTestPlan(int stepCount) {
        var steps = new ArrayList<PlannedStep>();
        for (int i = 0; i < stepCount; i++) {
            steps.add(new PlannedStep(
                    "step-" + (i + 1),
                    "Step " + (i + 1),
                    "Do step " + (i + 1),
                    List.of("read_file"),
                    i == 0 ? "Try alternative approach" : null,
                    java.util.Set.of(),
                    StepStatus.PENDING));
        }
        return new TaskPlan("plan-1", "Test goal", steps, new PlanStatus.Draft(), Instant.now());
    }

    private final StreamEventHandler noOpHandler = new StreamEventHandler() {};

    @Test
    void allStepsSucceed() throws LlmException {
        var client = new SimpleMockLlmClient();
        client.enqueueTextResponse("result 1");
        client.enqueueTextResponse("result 2");
        client.enqueueTextResponse("result 3");

        var plan = createTestPlan(3);
        var loop = createLoop(client);
        var executor = new SequentialPlanExecutor();

        var result = executor.execute(plan, loop, new ArrayList<>(), noOpHandler, null);

        assertTrue(result.success());
        assertEquals(3, result.stepResults().size());
        assertTrue(result.stepResults().stream().allMatch(StepResult::success));
        assertTrue(result.totalDurationMs() >= 0);
        assertTrue(result.totalTokensUsed() > 0);
        assertInstanceOf(PlanStatus.Completed.class, result.plan().status());
    }

    @Test
    void stepFailsWithFallbackSuccess() throws LlmException {
        var client = new SimpleMockLlmClient();
        client.enqueueError();                    // step 1 fails
        client.enqueueTextResponse("fallback ok"); // fallback for step 1 succeeds
        client.enqueueTextResponse("step 2 ok");   // step 2 succeeds

        var plan = createTestPlan(2); // step-1 has fallback
        var loop = createLoop(client);
        var executor = new SequentialPlanExecutor();

        var result = executor.execute(plan, loop, new ArrayList<>(), noOpHandler, null);

        assertTrue(result.success());
        assertEquals(2, result.stepResults().size());
    }

    @Test
    void stepFailsNoFallback() throws LlmException {
        var client = new SimpleMockLlmClient();
        client.enqueueError(); // step 1 fails

        var steps = List.of(
                new PlannedStep("s1", "Step 1", "Do it", List.of(), null, java.util.Set.of(), StepStatus.PENDING),
                new PlannedStep("s2", "Step 2", "Do more", List.of(), null, java.util.Set.of(), StepStatus.PENDING));
        var plan = new TaskPlan("plan-1", "Goal", steps, new PlanStatus.Draft(), Instant.now());

        var loop = createLoop(client);
        var executor = new SequentialPlanExecutor();

        var result = executor.execute(plan, loop, new ArrayList<>(), noOpHandler, null);

        assertFalse(result.success());
        assertEquals(1, result.stepResults().size());
        assertFalse(result.stepResults().get(0).success());
        assertInstanceOf(PlanStatus.Failed.class, result.plan().status());
    }

    @Test
    void cancellationBetweenSteps() throws LlmException {
        var client = new SimpleMockLlmClient();
        client.enqueueTextResponse("result 1");
        client.enqueueTextResponse("result 2"); // should never be used

        var plan = createTestPlan(2);
        var loop = createLoop(client);
        var token = new CancellationToken();
        var executor = new SequentialPlanExecutor();

        // Execute step 1, then cancel before step 2
        // We use a listener to cancel after step 1 completes
        var cancellingListener = new PlanEventListener() {
            @Override
            public void onStepStarted(PlannedStep step, int stepIndex, int totalSteps) {}

            @Override
            public void onStepCompleted(PlannedStep step, int stepIndex, StepResult result) {
                if (stepIndex == 0) token.cancel();
            }

            @Override
            public void onPlanCompleted(TaskPlan p, boolean success, long totalDurationMs) {}
        };

        var cancelExecutor = new SequentialPlanExecutor(cancellingListener);
        var result = cancelExecutor.execute(plan, loop, new ArrayList<>(), noOpHandler, token);

        assertEquals(1, result.stepResults().size());
        assertTrue(result.stepResults().get(0).success());
    }

    @Test
    void emptyPlan() throws LlmException {
        var client = new SimpleMockLlmClient();
        var plan = new TaskPlan("plan-1", "Goal", List.of(), new PlanStatus.Draft(), Instant.now());

        var loop = createLoop(client);
        var executor = new SequentialPlanExecutor();

        var result = executor.execute(plan, loop, new ArrayList<>(), noOpHandler, null);

        assertTrue(result.success());
        assertEquals(0, result.stepResults().size());
    }

    @Test
    void singleStep() throws LlmException {
        var client = new SimpleMockLlmClient();
        client.enqueueTextResponse("done");

        var plan = createTestPlan(1);
        var loop = createLoop(client);
        var executor = new SequentialPlanExecutor();

        var result = executor.execute(plan, loop, new ArrayList<>(), noOpHandler, null);

        assertTrue(result.success());
        assertEquals(1, result.stepResults().size());
        assertTrue(result.totalTokensUsed() > 0);
    }

    @Test
    void buildStepPrompt_includesPreviousResults() {
        var plan = createTestPlan(3);
        var previousResults = List.of(
                new StepResult(true, "Found 5 auth files", null, 1000, 60, 40));

        var prompt = SequentialPlanExecutor.buildStepPrompt(
                plan.steps().get(1), 1, plan, previousResults);

        assertTrue(prompt.contains("step 2 of 3"));
        assertTrue(prompt.contains("Test goal"));
        assertTrue(prompt.contains("Step 2"));
        assertTrue(prompt.contains("Previous Steps Completed"));
        assertTrue(prompt.contains("Found 5 auth files"));
    }

    @Test
    void eventListenerCalled() throws LlmException {
        var client = new SimpleMockLlmClient();
        client.enqueueTextResponse("r1");
        client.enqueueTextResponse("r2");

        var plan = createTestPlan(2);
        var loop = createLoop(client);
        var startedSteps = new ArrayList<String>();
        var completedSteps = new ArrayList<String>();

        var listener = new PlanEventListener() {
            @Override
            public void onStepStarted(PlannedStep step, int stepIndex, int totalSteps) {
                startedSteps.add(step.name());
            }

            @Override
            public void onStepCompleted(PlannedStep step, int stepIndex, StepResult result) {
                completedSteps.add(step.name());
            }

            @Override
            public void onPlanCompleted(TaskPlan p, boolean success, long totalDurationMs) {}
        };

        var executor = new SequentialPlanExecutor(listener);
        executor.execute(plan, loop, new ArrayList<>(), noOpHandler, null);

        assertEquals(List.of("Step 1", "Step 2"), startedSteps);
        assertEquals(List.of("Step 1", "Step 2"), completedSteps);
    }
}

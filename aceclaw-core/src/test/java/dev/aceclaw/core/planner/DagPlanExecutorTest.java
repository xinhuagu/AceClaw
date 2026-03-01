package dev.aceclaw.core.planner;

import dev.aceclaw.core.agent.CancellationToken;
import dev.aceclaw.core.agent.StreamingAgentLoop;
import dev.aceclaw.core.agent.ToolRegistry;
import dev.aceclaw.core.llm.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DagPlanExecutorTest {

    /**
     * Thread-safe mock LlmClient for parallel execution tests.
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

    private AgentLoopFactory createFactory(SimpleMockLlmClient client) {
        return () -> new StreamingAgentLoop(client, new ToolRegistry(), "mock-model", null);
    }

    private final StreamEventHandler noOpHandler = new StreamEventHandler() {};

    // --- Diamond DAG: A → {B, C} → D ---

    @Test
    void diamondDag_executesInCorrectOrder() throws LlmException {
        var client = new SimpleMockLlmClient();
        // A, B, C, D all need responses
        client.enqueueTextResponse("A done");
        client.enqueueTextResponse("B done");
        client.enqueueTextResponse("C done");
        client.enqueueTextResponse("D done");

        var stepA = new PlannedStep("a", "Step A", "Root step", List.of(), null, Set.of(), StepStatus.PENDING);
        var stepB = new PlannedStep("b", "Step B", "Branch 1", List.of(), null, Set.of("a"), StepStatus.PENDING);
        var stepC = new PlannedStep("c", "Step C", "Branch 2", List.of(), null, Set.of("a"), StepStatus.PENDING);
        var stepD = new PlannedStep("d", "Step D", "Join step", List.of(), null, Set.of("b", "c"), StepStatus.PENDING);
        var plan = new TaskPlan("plan-1", "Diamond test", List.of(stepA, stepB, stepC, stepD),
                new PlanStatus.Draft(), Instant.now());

        var startedSteps = new ConcurrentLinkedQueue<String>();
        var completedSteps = new ConcurrentLinkedQueue<String>();

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

        var executor = new DagPlanExecutor(listener, createFactory(client));
        var result = executor.execute(plan, createLoop(client), new ArrayList<>(), noOpHandler, null);

        assertTrue(result.success());
        assertEquals(4, result.stepResults().size());
        assertTrue(result.stepResults().stream().allMatch(StepResult::success));
        assertInstanceOf(PlanStatus.Completed.class, result.plan().status());

        // A must complete before B and C; B and C must complete before D
        var completedList = new ArrayList<>(completedSteps);
        assertTrue(completedList.indexOf("Step A") < completedList.indexOf("Step B"));
        assertTrue(completedList.indexOf("Step A") < completedList.indexOf("Step C"));
        assertTrue(completedList.indexOf("Step B") < completedList.indexOf("Step D"));
        assertTrue(completedList.indexOf("Step C") < completedList.indexOf("Step D"));
    }

    // --- Fully independent steps ---

    @Test
    void fullyIndependentSteps_allRunInParallel() throws LlmException {
        var client = new SimpleMockLlmClient();
        client.enqueueTextResponse("X done");
        client.enqueueTextResponse("Y done");
        client.enqueueTextResponse("Z done");

        var stepX = new PlannedStep("x", "Step X", "Independent 1", List.of(), null, Set.of(), StepStatus.PENDING);
        var stepY = new PlannedStep("y", "Step Y", "Independent 2", List.of(), null, Set.of(), StepStatus.PENDING);
        var stepZ = new PlannedStep("z", "Step Z", "Independent 3", List.of(), null, Set.of(), StepStatus.PENDING);
        var plan = new TaskPlan("plan-2", "Parallel test", List.of(stepX, stepY, stepZ),
                new PlanStatus.Draft(), Instant.now());

        var executor = new DagPlanExecutor(null, createFactory(client));
        var result = executor.execute(plan, createLoop(client), new ArrayList<>(), noOpHandler, null);

        assertTrue(result.success());
        assertEquals(3, result.stepResults().size());
    }

    // --- Purely sequential fallback (single linear chain) ---

    @Test
    void purelySequential_executesInOrder() throws LlmException {
        var client = new SimpleMockLlmClient();
        client.enqueueTextResponse("first");
        client.enqueueTextResponse("second");
        client.enqueueTextResponse("third");

        var s1 = new PlannedStep("s1", "Step 1", "First", List.of(), null, Set.of(), StepStatus.PENDING);
        var s2 = new PlannedStep("s2", "Step 2", "Second", List.of(), null, Set.of("s1"), StepStatus.PENDING);
        var s3 = new PlannedStep("s3", "Step 3", "Third", List.of(), null, Set.of("s2"), StepStatus.PENDING);
        var plan = new TaskPlan("plan-3", "Sequential test", List.of(s1, s2, s3),
                new PlanStatus.Draft(), Instant.now());

        var completedSteps = new ConcurrentLinkedQueue<String>();
        var listener = new PlanEventListener() {
            @Override
            public void onStepStarted(PlannedStep step, int stepIndex, int totalSteps) {}

            @Override
            public void onStepCompleted(PlannedStep step, int stepIndex, StepResult result) {
                completedSteps.add(step.name());
            }

            @Override
            public void onPlanCompleted(TaskPlan p, boolean success, long totalDurationMs) {}
        };

        var executor = new DagPlanExecutor(listener, createFactory(client));
        var result = executor.execute(plan, createLoop(client), new ArrayList<>(), noOpHandler, null);

        assertTrue(result.success());
        assertEquals(3, result.stepResults().size());

        var ordered = new ArrayList<>(completedSteps);
        assertEquals(List.of("Step 1", "Step 2", "Step 3"), ordered);
    }

    // --- Branch failure + skip ---

    @Test
    void branchFailure_skipsDependents_continuesIndependent() throws LlmException {
        var client = new SimpleMockLlmClient();
        // A and C are both roots — run in parallel, both succeed (order doesn't matter)
        client.enqueueTextResponse("A done");
        client.enqueueTextResponse("C done");
        // B depends on A — runs alone after A completes, fails
        client.enqueueError();

        // C is an independent root (no deps on A), so it runs in parallel with A
        // B depends on A, D depends on B
        var stepA = new PlannedStep("a", "Step A", "Root 1", List.of(), null, Set.of(), StepStatus.PENDING);
        var stepC = new PlannedStep("c", "Step C", "Root 2 (independent)", List.of(), null, Set.of(), StepStatus.PENDING);
        var stepB = new PlannedStep("b", "Step B", "Will fail", List.of(), null, Set.of("a"), StepStatus.PENDING);
        var stepD = new PlannedStep("d", "Step D", "Depends on B", List.of(), null, Set.of("b"), StepStatus.PENDING);
        var plan = new TaskPlan("plan-4", "Failure test", List.of(stepA, stepC, stepB, stepD),
                new PlanStatus.Draft(), Instant.now());

        var executor = new DagPlanExecutor(null, createFactory(client));
        var result = executor.execute(plan, createLoop(client), new ArrayList<>(), noOpHandler, null);

        assertFalse(result.success());

        // D should be SKIPPED because B failed
        var finalPlan = result.plan();
        assertEquals(StepStatus.COMPLETED, findStepStatus(finalPlan, "a"));
        assertEquals(StepStatus.COMPLETED, findStepStatus(finalPlan, "c"));
        assertEquals(StepStatus.FAILED, findStepStatus(finalPlan, "b"));
        assertEquals(StepStatus.SKIPPED, findStepStatus(finalPlan, "d"));
    }

    // --- Cancellation ---

    @Test
    void cancellation_stopsExecution() throws LlmException {
        var client = new SimpleMockLlmClient();
        client.enqueueTextResponse("first");
        client.enqueueTextResponse("second");

        var s1 = new PlannedStep("s1", "Step 1", "First", List.of(), null, Set.of(), StepStatus.PENDING);
        var s2 = new PlannedStep("s2", "Step 2", "Second", List.of(), null, Set.of("s1"), StepStatus.PENDING);
        var plan = new TaskPlan("plan-5", "Cancel test", List.of(s1, s2),
                new PlanStatus.Draft(), Instant.now());

        var token = new CancellationToken();

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

        var executor = new DagPlanExecutor(cancellingListener, createFactory(client));
        var result = executor.execute(plan, createLoop(client), new ArrayList<>(), noOpHandler, token);

        assertFalse(result.success());
        assertEquals(1, result.stepResults().size());
        assertInstanceOf(PlanStatus.Failed.class, result.plan().status());
    }

    // --- hasParallelizableSteps ---

    @Test
    void hasParallelizableSteps_trueForDiamondDag() {
        var plan = new TaskPlan("p", "goal", List.of(
                new PlannedStep("a", "A", "a", List.of(), null, Set.of(), StepStatus.PENDING),
                new PlannedStep("b", "B", "b", List.of(), null, Set.of("a"), StepStatus.PENDING),
                new PlannedStep("c", "C", "c", List.of(), null, Set.of("a"), StepStatus.PENDING)
        ), new PlanStatus.Draft(), Instant.now());
        assertTrue(plan.hasParallelizableSteps());
    }

    @Test
    void hasParallelizableSteps_trueForMultipleRoots() {
        var plan = new TaskPlan("p", "goal", List.of(
                new PlannedStep("a", "A", "a", List.of(), null, Set.of(), StepStatus.PENDING),
                new PlannedStep("b", "B", "b", List.of(), null, Set.of(), StepStatus.PENDING)
        ), new PlanStatus.Draft(), Instant.now());
        assertTrue(plan.hasParallelizableSteps());
    }

    @Test
    void hasParallelizableSteps_falseForSingleLinearChain() {
        var plan = new TaskPlan("p", "goal", List.of(
                new PlannedStep("a", "A", "a", List.of(), null, Set.of(), StepStatus.PENDING)
        ), new PlanStatus.Draft(), Instant.now());
        assertFalse(plan.hasParallelizableSteps());
    }

    // --- readySteps ---

    @Test
    void readySteps_returnsRootsInitially() {
        var plan = new TaskPlan("p", "goal", List.of(
                new PlannedStep("a", "A", "a", List.of(), null, Set.of(), StepStatus.PENDING),
                new PlannedStep("b", "B", "b", List.of(), null, Set.of("a"), StepStatus.PENDING)
        ), new PlanStatus.Draft(), Instant.now());

        var ready = plan.readySteps();
        assertEquals(1, ready.size());
        assertEquals("a", ready.getFirst().stepId());
    }

    @Test
    void readySteps_afterCompletion() {
        var plan = new TaskPlan("p", "goal", List.of(
                new PlannedStep("a", "A", "a", List.of(), null, Set.of(), StepStatus.COMPLETED),
                new PlannedStep("b", "B", "b", List.of(), null, Set.of("a"), StepStatus.PENDING),
                new PlannedStep("c", "C", "c", List.of(), null, Set.of("a"), StepStatus.PENDING)
        ), new PlanStatus.Draft(), Instant.now());

        var ready = plan.readySteps();
        assertEquals(2, ready.size());
    }

    // --- buildStepPrompt ---

    @Test
    void buildStepPrompt_includesDependencyResults() {
        var stepA = new PlannedStep("a", "Step A", "Root", List.of(), null, Set.of(), StepStatus.COMPLETED);
        var stepB = new PlannedStep("b", "Step B", "Depends on A", List.of(), null, Set.of("a"), StepStatus.PENDING);
        var plan = new TaskPlan("p", "Test goal", List.of(stepA, stepB),
                new PlanStatus.Draft(), Instant.now());

        var results = java.util.Map.of("a", new StepResult(true, "Found 5 files", null, 1000, 60, 40));

        var prompt = DagPlanExecutor.buildStepPrompt(stepB, 1, plan, results);

        assertTrue(prompt.contains("Step B"));
        assertTrue(prompt.contains("Dependency Results"));
        assertTrue(prompt.contains("Step A"));
        assertTrue(prompt.contains("Found 5 files"));
    }

    @Test
    void buildStepPrompt_noDependencies_noSection() {
        var stepA = new PlannedStep("a", "Step A", "Root", List.of(), null, Set.of(), StepStatus.PENDING);
        var plan = new TaskPlan("p", "Test goal", List.of(stepA),
                new PlanStatus.Draft(), Instant.now());

        var prompt = DagPlanExecutor.buildStepPrompt(stepA, 0, plan, java.util.Map.of());

        assertTrue(prompt.contains("Step A"));
        assertFalse(prompt.contains("Dependency Results"));
    }

    private static StepStatus findStepStatus(TaskPlan plan, String stepId) {
        return plan.steps().stream()
                .filter(s -> s.stepId().equals(stepId))
                .findFirst()
                .map(PlannedStep::status)
                .orElseThrow();
    }
}

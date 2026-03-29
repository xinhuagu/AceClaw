package dev.aceclaw.core.planner;

import dev.aceclaw.core.agent.CancellationToken;
import dev.aceclaw.core.agent.StreamingAgentLoop;
import dev.aceclaw.core.agent.ToolRegistry;
import dev.aceclaw.core.agent.WatchdogTimer;
import dev.aceclaw.core.llm.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SequentialPlanExecutorReplanTest {

    /**
     * Mock LlmClient that supports both streaming (for agent loop) and
     * non-streaming (for replanner) responses.
     */
    static class ReplanAwareMockLlmClient implements LlmClient {
        private final ConcurrentLinkedQueue<Object> streamResponses = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<String> sendResponses = new ConcurrentLinkedQueue<>();

        void enqueueStreamTextResponse(String text) {
            streamResponses.add(List.of(
                    new StreamEvent.MessageStart("msg-mock", "mock-model"),
                    new StreamEvent.ContentBlockStart(0, new ContentBlock.Text("")),
                    new StreamEvent.TextDelta(text),
                    new StreamEvent.ContentBlockStop(0),
                    new StreamEvent.MessageDelta(StopReason.END_TURN, new Usage(100, 50)),
                    new StreamEvent.StreamComplete()));
        }

        void enqueueStreamError() {
            streamResponses.add(List.of(
                    new StreamEvent.MessageStart("msg-mock", "mock-model"),
                    new StreamEvent.StreamError(new LlmException("Step failed", 500))));
        }

        void enqueueSendResponse(String text) {
            sendResponses.add(text);
        }

        @Override
        public LlmResponse sendMessage(LlmRequest request) throws LlmException {
            var next = sendResponses.poll();
            if (next == null) {
                throw new LlmException("No mock sendMessage response");
            }
            return new LlmResponse("id", "model",
                    List.of(new ContentBlock.Text(next)),
                    StopReason.END_TURN, new Usage(10, 5));
        }

        @SuppressWarnings("unchecked")
        @Override
        public StreamSession streamMessage(LlmRequest request) throws LlmException {
            var next = streamResponses.poll();
            if (next == null) throw new LlmException("No mock stream responses", 500);
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

    private StreamingAgentLoop createLoop(ReplanAwareMockLlmClient client) {
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
                    null,  // no fallback
                    StepStatus.PENDING));
        }
        return new TaskPlan("plan-1", "Test goal", steps, new PlanStatus.Draft(), Instant.now());
    }

    private static final String REVISE_ONE_STEP = """
            {
              "action": "revise",
              "rationale": "Retry with alternative approach",
              "steps": [
                {
                  "name": "Alternative step",
                  "description": "Try alternative approach",
                  "requiredTools": ["read_file"]
                }
              ]
            }
            """;

    private static final String ESCALATE_RESPONSE = """
            {
              "action": "escalate",
              "reason": "Cannot recover from this failure"
            }
            """;

    private final StreamEventHandler noOpHandler = new StreamEventHandler() {};

    @Test
    void execute_withReplanner_revisesAndContinues() throws LlmException {
        var client = new ReplanAwareMockLlmClient();
        // Step 1 succeeds
        client.enqueueStreamTextResponse("step 1 done");
        // Step 2 fails
        client.enqueueStreamError();
        // Replanner returns revised plan with 1 new step
        client.enqueueSendResponse(REVISE_ONE_STEP);
        // Revised step succeeds
        client.enqueueStreamTextResponse("alternative step done");

        var plan = createTestPlan(3);
        var loop = createLoop(client);
        var replanner = new AdaptiveReplanner(client, "mock-model");
        var executor = new SequentialPlanExecutor(null, replanner);

        var result = executor.execute(plan, loop, new ArrayList<>(), noOpHandler, null);

        assertTrue(result.success());
        // step 1 success + step 2 fail + revised step success = 3 results
        assertEquals(3, result.stepResults().size());
        assertTrue(result.stepResults().get(0).success());
        assertFalse(result.stepResults().get(1).success());
        assertTrue(result.stepResults().get(2).success());
    }

    @Test
    void execute_withReplanner_escalates() throws LlmException {
        var client = new ReplanAwareMockLlmClient();
        // Step 1 fails
        client.enqueueStreamError();
        // Replanner escalates
        client.enqueueSendResponse(ESCALATE_RESPONSE);

        var steps = List.of(
                new PlannedStep("s1", "Step 1", "Do it", List.of(), null, StepStatus.PENDING),
                new PlannedStep("s2", "Step 2", "Do more", List.of(), null, StepStatus.PENDING));
        var plan = new TaskPlan("plan-1", "Goal", steps, new PlanStatus.Draft(), Instant.now());

        var loop = createLoop(client);
        var replanner = new AdaptiveReplanner(client, "mock-model");
        var executor = new SequentialPlanExecutor(null, replanner);

        var result = executor.execute(plan, loop, new ArrayList<>(), noOpHandler, null);

        assertFalse(result.success());
        assertInstanceOf(PlanStatus.Failed.class, result.plan().status());
        var failed = (PlanStatus.Failed) result.plan().status();
        assertTrue(failed.reason().contains("Cannot recover"));
    }

    @Test
    void execute_withoutReplanner_originalBehavior() throws LlmException {
        var client = new ReplanAwareMockLlmClient();
        client.enqueueStreamError(); // step 1 fails

        var steps = List.of(
                new PlannedStep("s1", "Step 1", "Do it", List.of(), null, StepStatus.PENDING),
                new PlannedStep("s2", "Step 2", "Do more", List.of(), null, StepStatus.PENDING));
        var plan = new TaskPlan("plan-1", "Goal", steps, new PlanStatus.Draft(), Instant.now());

        var loop = createLoop(client);
        // No replanner (null)
        var executor = new SequentialPlanExecutor(null, null);

        var result = executor.execute(plan, loop, new ArrayList<>(), noOpHandler, null);

        assertFalse(result.success());
        assertEquals(1, result.stepResults().size());
        assertInstanceOf(PlanStatus.Failed.class, result.plan().status());
    }

    @Test
    void execute_replanAttemptLimit() throws LlmException {
        var client = new ReplanAwareMockLlmClient();
        // Step 1 succeeds
        client.enqueueStreamTextResponse("step 1 done");
        // Step 2 fails -> replan 1 -> revised step fails -> replan 2 -> revised step fails -> replan 3 -> revised step fails -> max exceeded
        client.enqueueStreamError(); // step 2 fails
        client.enqueueSendResponse(REVISE_ONE_STEP); // replan attempt 1
        client.enqueueStreamError(); // revised step fails
        client.enqueueSendResponse(REVISE_ONE_STEP); // replan attempt 2
        client.enqueueStreamError(); // revised step fails again
        client.enqueueSendResponse(REVISE_ONE_STEP); // replan attempt 3
        client.enqueueStreamError(); // revised step fails again
        // Attempt 4 would exceed MAX_REPLAN_ATTEMPTS (3) -- auto-escalated

        var plan = createTestPlan(3);
        var loop = createLoop(client);
        var replanner = new AdaptiveReplanner(client, "mock-model");
        var executor = new SequentialPlanExecutor(null, replanner);

        var result = executor.execute(plan, loop, new ArrayList<>(), noOpHandler, null);

        assertFalse(result.success());
        assertInstanceOf(PlanStatus.Failed.class, result.plan().status());
        var failed = (PlanStatus.Failed) result.plan().status();
        assertTrue(failed.reason().contains("Max replan attempts"));
    }

    @Test
    void listenerCallbacks_onReplan() throws LlmException {
        var client = new ReplanAwareMockLlmClient();
        client.enqueueStreamError(); // step 1 fails
        client.enqueueSendResponse(REVISE_ONE_STEP); // replanner revises
        client.enqueueStreamTextResponse("alternative done"); // revised step succeeds

        var steps = List.of(
                new PlannedStep("s1", "Step 1", "Do it", List.of(), null, StepStatus.PENDING),
                new PlannedStep("s2", "Step 2", "Do more", List.of(), null, StepStatus.PENDING));
        var plan = new TaskPlan("plan-1", "Goal", steps, new PlanStatus.Draft(), Instant.now());

        var loop = createLoop(client);
        var replanner = new AdaptiveReplanner(client, "mock-model");

        var replanCalled = new AtomicBoolean(false);
        var replanAttemptValue = new AtomicInteger(0);
        var escalateCalled = new AtomicBoolean(false);

        var listener = new SequentialPlanExecutor.PlanEventListener() {
            @Override
            public void onStepStarted(PlannedStep step, int stepIndex, int totalSteps) {}
            @Override
            public void onStepCompleted(PlannedStep step, int stepIndex, StepResult result) {}
            @Override
            public void onPlanCompleted(TaskPlan p, boolean success, long totalDurationMs) {}
            @Override
            public void onPlanReplanned(TaskPlan oldPlan, TaskPlan newPlan, int attempt, String rationale) {
                replanCalled.set(true);
                replanAttemptValue.set(attempt);
            }
            @Override
            public void onPlanEscalated(TaskPlan p, String reason) {
                escalateCalled.set(true);
            }
        };

        var executor = new SequentialPlanExecutor(listener, replanner);
        var result = executor.execute(plan, loop, new ArrayList<>(), noOpHandler, null);

        assertTrue(result.success());
        assertTrue(replanCalled.get(), "onPlanReplanned should have been called");
        assertEquals(1, replanAttemptValue.get());
        assertFalse(escalateCalled.get(), "onPlanEscalated should not have been called");
    }

    @Test
    void listenerCallbacks_onEscalate() throws LlmException {
        var client = new ReplanAwareMockLlmClient();
        client.enqueueStreamError(); // step 1 fails
        client.enqueueSendResponse(ESCALATE_RESPONSE);

        var steps = List.of(
                new PlannedStep("s1", "Step 1", "Do it", List.of(), null, StepStatus.PENDING));
        var plan = new TaskPlan("plan-1", "Goal", steps, new PlanStatus.Draft(), Instant.now());

        var loop = createLoop(client);
        var replanner = new AdaptiveReplanner(client, "mock-model");

        var escalateCalled = new AtomicBoolean(false);
        var escalateReason = new StringBuilder();

        var listener = new SequentialPlanExecutor.PlanEventListener() {
            @Override
            public void onStepStarted(PlannedStep step, int stepIndex, int totalSteps) {}
            @Override
            public void onStepCompleted(PlannedStep step, int stepIndex, StepResult result) {}
            @Override
            public void onPlanCompleted(TaskPlan p, boolean success, long totalDurationMs) {}
            @Override
            public void onPlanEscalated(TaskPlan p, String reason) {
                escalateCalled.set(true);
                escalateReason.append(reason);
            }
        };

        var executor = new SequentialPlanExecutor(listener, replanner);
        var result = executor.execute(plan, loop, new ArrayList<>(), noOpHandler, null);

        assertFalse(result.success());
        assertTrue(escalateCalled.get(), "onPlanEscalated should have been called");
        assertTrue(escalateReason.toString().contains("Cannot recover"));
    }

    @Test
    void execute_withWatchdog_resetsPerStep() throws LlmException {
        var client = new ReplanAwareMockLlmClient();
        // 3 steps, all succeed
        client.enqueueStreamTextResponse("step 1 done");
        client.enqueueStreamTextResponse("step 2 done");
        client.enqueueStreamTextResponse("step 3 done");

        var plan = createTestPlan(3);
        var loop = createLoop(client);
        var cancellationToken = new CancellationToken();

        // Create a watchdog with a very short initial wall-clock (50ms).
        // Without per-step reset, step 2+ would be cancelled.
        // With per-step reset (5 seconds each), all steps should complete.
        try (var watchdog = new WatchdogTimer(0, Duration.ofMillis(50), cancellationToken)) {
            var executor = new SequentialPlanExecutor(
                    null, null, watchdog, Duration.ofSeconds(5), Duration.ofHours(1));

            var result = executor.execute(plan, loop, new ArrayList<>(), noOpHandler, cancellationToken);

            assertTrue(result.success(), "All steps should succeed with per-step wall-clock reset");
            assertEquals(3, result.stepResults().size());
            // Watchdog should NOT be exhausted since each step gets a fresh 5s budget
            assertFalse(watchdog.isExhausted(),
                    "Watchdog should not be exhausted when per-step resets are active");
        }
    }

    @Test
    void execute_totalPlanTimeout() throws LlmException {
        var client = new ReplanAwareMockLlmClient();
        // 3 steps, all succeed
        client.enqueueStreamTextResponse("step 1 done");
        client.enqueueStreamTextResponse("step 2 done");
        client.enqueueStreamTextResponse("step 3 done");

        var plan = createTestPlan(3);
        var loop = createLoop(client);

        // Listener that introduces delay after step completion, ensuring
        // the total plan budget (5ms) is exceeded before the next step starts.
        var delayListener = new SequentialPlanExecutor.PlanEventListener() {
            @Override
            public void onStepStarted(PlannedStep step, int stepIndex, int totalSteps) {}
            @Override
            public void onStepCompleted(PlannedStep step, int stepIndex, StepResult result) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            @Override
            public void onPlanCompleted(TaskPlan p, boolean success, long totalDurationMs) {}
        };

        // Total plan wall time of 5ms — step 1 runs, the 20ms listener delay pushes
        // elapsed past 5ms, so step 2 is blocked by the budget check.
        var executor = new SequentialPlanExecutor(
                delayListener, null, null, null, Duration.ofMillis(5));

        var result = executor.execute(plan, loop, new ArrayList<>(), noOpHandler, null);

        assertFalse(result.success(), "Plan should fail due to total time budget");
        assertInstanceOf(PlanStatus.Failed.class, result.plan().status());
        var failed = (PlanStatus.Failed) result.plan().status();
        assertEquals("plan_time_budget", failed.reason());
    }

    @Test
    void execute_totalPlanTimeout_postLoopCheck() throws LlmException {
        var client = new ReplanAwareMockLlmClient();
        // Single step that succeeds
        client.enqueueStreamTextResponse("step 1 done");

        var steps = List.of(
                new PlannedStep("s1", "Step 1", "Do it", List.of("read_file"), null, StepStatus.PENDING));
        var plan = new TaskPlan("plan-1", "Goal", steps, new PlanStatus.Draft(), Instant.now());
        var loop = createLoop(client);

        // Listener that introduces a 20ms delay AFTER the only step completes.
        // This ensures total elapsed exceeds the 5ms budget by the time the
        // post-loop check runs, even though the pre-step check passed.
        var delayListener = new SequentialPlanExecutor.PlanEventListener() {
            @Override
            public void onStepStarted(PlannedStep step, int stepIndex, int totalSteps) {}
            @Override
            public void onStepCompleted(PlannedStep step, int stepIndex, StepResult result) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            @Override
            public void onPlanCompleted(TaskPlan p, boolean success, long totalDurationMs) {}
        };

        var executor = new SequentialPlanExecutor(
                delayListener, null, null, null, Duration.ofMillis(5));

        var result = executor.execute(plan, loop, new ArrayList<>(), noOpHandler, null);

        // The single step completed, but the post-loop check should catch the overrun
        assertFalse(result.success(), "Plan should fail due to post-loop total time budget check");
        assertInstanceOf(PlanStatus.Failed.class, result.plan().status());
        var failed = (PlanStatus.Failed) result.plan().status();
        assertEquals("plan_time_budget", failed.reason());
        // The step itself did complete
        assertEquals(1, result.stepResults().size());
        assertTrue(result.stepResults().get(0).success());
    }

    @Test
    void execute_withWatchdog_requiresCancellationToken() {
        var token = new CancellationToken();
        try (var watchdog = new WatchdogTimer(0, Duration.ZERO, token)) {
            var executor = new SequentialPlanExecutor(
                    null, null, watchdog, Duration.ofSeconds(5), null);
            var plan = createTestPlan(1);
            var loop = createLoop(new ReplanAwareMockLlmClient());

            assertThrows(IllegalArgumentException.class,
                    () -> executor.execute(plan, loop, new ArrayList<>(), noOpHandler, null),
                    "Should throw when watchdog is set but cancellationToken is null");
        }
    }

    @Test
    void execute_watchdogResetCappedToRemainingTotalBudget() throws LlmException {
        var client = new ReplanAwareMockLlmClient();
        // 2 steps
        client.enqueueStreamTextResponse("step 1 done");
        client.enqueueStreamTextResponse("step 2 done");

        var plan = createTestPlan(2);
        var cancellationToken = new CancellationToken();

        // perStepWallTime = 1 hour, totalPlanWallTime = 200ms.
        // Step 1's watchdog reset is capped to min(1h, ~200ms) ≈ 200ms.
        // The listener introduces a 500ms delay after step 1, exceeding the total budget.
        // The capped watchdog fires during the delay (at ~200ms), cancelling the token.
        // Without capping, the watchdog would wait 1 hour and the overrun would only
        // be caught at the pre-step check -- the key difference is that capping lets
        // the watchdog enforce the total deadline during step execution too.
        // (Timings are generous to avoid flaky failures on slow CI runners.)
        var delayListener = new SequentialPlanExecutor.PlanEventListener() {
            @Override
            public void onStepStarted(PlannedStep step, int stepIndex, int totalSteps) {}
            @Override
            public void onStepCompleted(PlannedStep step, int stepIndex, StepResult result) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            @Override
            public void onPlanCompleted(TaskPlan p, boolean success, long totalDurationMs) {}
        };

        var loop = createLoop(client);
        try (var watchdog = new WatchdogTimer(0, Duration.ofHours(1), cancellationToken)) {
            var executor = new SequentialPlanExecutor(
                    delayListener, null, watchdog, Duration.ofHours(1), Duration.ofMillis(200));

            var result = executor.execute(plan, loop, new ArrayList<>(), noOpHandler, cancellationToken);

            // Plan must fail -- the capped watchdog fires during the listener delay,
            // and the executor correctly reports the watchdog reason, not "Cancelled by user".
            assertFalse(result.success(), "Plan should fail when total budget is exceeded");
            assertTrue(cancellationToken.isCancelled(),
                    "Token should be cancelled by capped watchdog timer");
            assertInstanceOf(PlanStatus.Failed.class, result.plan().status());
            var failed = (PlanStatus.Failed) result.plan().status();
            assertEquals("time_budget", failed.reason(),
                    "Should report watchdog exhaustion reason, not 'Cancelled by user'");
        }
    }
}

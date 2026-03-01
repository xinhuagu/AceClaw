package dev.aceclaw.core.planner;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlanCheckpointTest {

    private static TaskPlan samplePlan(int stepCount) {
        var steps = new java.util.ArrayList<PlannedStep>();
        for (int i = 0; i < stepCount; i++) {
            steps.add(new PlannedStep(
                    "step-" + i, "Step " + (i + 1), "Do step " + (i + 1),
                    List.of("bash"), null, java.util.Set.of(), StepStatus.PENDING));
        }
        return new TaskPlan("plan-1", "Build feature X", steps,
                new PlanStatus.Draft(), Instant.now());
    }

    private static PlanCheckpoint sampleCheckpoint(TaskPlan plan, int lastCompleted) {
        var results = new java.util.ArrayList<StepResult>();
        for (int i = 0; i <= lastCompleted; i++) {
            results.add(new StepResult(true, "output-" + i, null, 100 * (i + 1), 50, 25));
        }
        return new PlanCheckpoint(
                plan.planId(), "session-1", "ws-hash", plan.originalGoal(), plan,
                results, lastCompleted, List.of("{\"role\":\"user\",\"content\":\"hello\"}"),
                PlanCheckpoint.CheckpointStatus.ACTIVE, null, List.of(),
                Instant.now(), Instant.now());
    }

    @Test
    void nextStepIndex_initiallyZero() {
        var cp = sampleCheckpoint(samplePlan(3), -1);
        assertEquals(0, cp.nextStepIndex());
    }

    @Test
    void nextStepIndex_afterFirstCompletion() {
        var cp = sampleCheckpoint(samplePlan(3), 0);
        assertEquals(1, cp.nextStepIndex());
    }

    @Test
    void nextStepIndex_afterAllCompleted() {
        var cp = sampleCheckpoint(samplePlan(3), 2);
        assertEquals(3, cp.nextStepIndex());
    }

    @Test
    void hasRemainingSteps_trueWhenIncomplete() {
        var cp = sampleCheckpoint(samplePlan(3), 1);
        assertTrue(cp.hasRemainingSteps());
    }

    @Test
    void hasRemainingSteps_falseWhenAllDone() {
        var cp = sampleCheckpoint(samplePlan(3), 2);
        assertFalse(cp.hasRemainingSteps());
    }

    @Test
    void remainingSteps_returnsCorrectSublist() {
        var plan = samplePlan(4);
        var cp = sampleCheckpoint(plan, 1);
        var remaining = cp.remainingSteps();
        assertEquals(2, remaining.size());
        assertEquals("Step 3", remaining.get(0).name());
        assertEquals("Step 4", remaining.get(1).name());
    }

    @Test
    void remainingSteps_emptyWhenAllDone() {
        var cp = sampleCheckpoint(samplePlan(2), 1);
        assertTrue(cp.remainingSteps().isEmpty());
    }

    @Test
    void withStepCompleted_updatesIndexAndResults() {
        var plan = samplePlan(3);
        var cp = sampleCheckpoint(plan, 0);
        var result = new StepResult(true, "step 2 done", null, 200, 80, 40);
        var updatedPlan = plan.withStepStatus("step-1", StepStatus.COMPLETED);

        var updated = cp.withStepCompleted(1, result, updatedPlan,
                List.of("{\"role\":\"user\",\"content\":\"continued\"}"),
                "Step 2 completed", List.of("file.txt"));

        assertEquals(1, updated.lastCompletedStepIndex());
        assertEquals(2, updated.completedStepResults().size());
        assertEquals("step 2 done", updated.completedStepResults().get(1).output());
        assertEquals(1, updated.artifacts().size());
        assertEquals("file.txt", updated.artifacts().getFirst());
        assertEquals("Step 2 completed", updated.resumeHint());
    }

    @Test
    void withStatus_returnsNewInstance() {
        var cp = sampleCheckpoint(samplePlan(2), 0);
        var updated = cp.withStatus(PlanCheckpoint.CheckpointStatus.COMPLETED);
        assertEquals(PlanCheckpoint.CheckpointStatus.COMPLETED, updated.status());
        assertEquals(PlanCheckpoint.CheckpointStatus.ACTIVE, cp.status()); // original unchanged
    }

    @Test
    void nullGuards_inConstructor() {
        assertThrows(NullPointerException.class, () ->
                new PlanCheckpoint(null, "s", "ws", "goal", samplePlan(1),
                        null, -1, null, PlanCheckpoint.CheckpointStatus.ACTIVE,
                        null, null, Instant.now(), Instant.now()));

        assertThrows(NullPointerException.class, () ->
                new PlanCheckpoint("p", null, "ws", "goal", samplePlan(1),
                        null, -1, null, PlanCheckpoint.CheckpointStatus.ACTIVE,
                        null, null, Instant.now(), Instant.now()));

        assertThrows(NullPointerException.class, () ->
                new PlanCheckpoint("p", "s", "ws", "goal", null,
                        null, -1, null, PlanCheckpoint.CheckpointStatus.ACTIVE,
                        null, null, Instant.now(), Instant.now()));
    }

    @Test
    void nullLists_defaultToEmpty() {
        var cp = new PlanCheckpoint(
                "p", "s", "ws", "goal", samplePlan(1),
                null, -1, null, PlanCheckpoint.CheckpointStatus.ACTIVE,
                null, null, Instant.now(), Instant.now());
        assertNotNull(cp.completedStepResults());
        assertTrue(cp.completedStepResults().isEmpty());
        assertNotNull(cp.conversationSnapshot());
        assertTrue(cp.conversationSnapshot().isEmpty());
        assertNotNull(cp.artifacts());
        assertTrue(cp.artifacts().isEmpty());
    }

    @Test
    void listsAreImmutableCopies() {
        var mutableResults = new java.util.ArrayList<StepResult>();
        mutableResults.add(new StepResult(true, "out", null, 100, 50, 25));
        var cp = new PlanCheckpoint(
                "p", "s", "ws", "goal", samplePlan(1),
                mutableResults, 0, List.of(), PlanCheckpoint.CheckpointStatus.ACTIVE,
                null, List.of(), Instant.now(), Instant.now());

        // Modifying the original list should not affect the checkpoint
        mutableResults.add(new StepResult(false, null, "err", 50, 10, 5));
        assertEquals(1, cp.completedStepResults().size());
    }

    @Test
    void withStepCompleted_preservesExistingArtifacts() {
        var plan = samplePlan(3);
        var cp = new PlanCheckpoint(
                plan.planId(), "s", "ws", plan.originalGoal(), plan,
                List.of(), -1, List.of(), PlanCheckpoint.CheckpointStatus.ACTIVE,
                null, List.of("existing.txt"), Instant.now(), Instant.now());

        var result = new StepResult(true, "done", null, 100, 50, 25);
        var updated = cp.withStepCompleted(0, result, plan,
                List.of(), "hint", List.of("new.txt"));

        assertEquals(2, updated.artifacts().size());
        assertEquals("existing.txt", updated.artifacts().get(0));
        assertEquals("new.txt", updated.artifacts().get(1));
    }
}

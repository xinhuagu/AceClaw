package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.planner.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FilePlanCheckpointStoreTest {

    @TempDir
    Path tempDir;
    private FilePlanCheckpointStore store;

    @BeforeEach
    void setUp() {
        store = new FilePlanCheckpointStore(tempDir, new ObjectMapper());
    }

    private static TaskPlan samplePlan(String planId, int stepCount) {
        var steps = new java.util.ArrayList<PlannedStep>();
        for (int i = 0; i < stepCount; i++) {
            steps.add(new PlannedStep(
                    "step-" + i, "Step " + (i + 1), "Do step " + (i + 1),
                    List.of("bash"), null, StepStatus.PENDING));
        }
        return new TaskPlan(planId, "Build feature X", steps,
                new PlanStatus.Draft(), Instant.now());
    }

    private static PlanCheckpoint sampleCheckpoint(String planId, String sessionId,
                                                     String wsHash, int lastCompleted) {
        var plan = samplePlan(planId, 4);
        var results = new java.util.ArrayList<StepResult>();
        for (int i = 0; i <= lastCompleted; i++) {
            results.add(new StepResult(true, "output-" + i, null, 100, 50, 25));
        }
        return new PlanCheckpoint(
                planId, sessionId, wsHash, plan.originalGoal(), plan,
                results, lastCompleted, List.of("{\"role\":\"user\",\"content\":\"test\"}"),
                PlanCheckpoint.CheckpointStatus.ACTIVE, "hint", List.of("file.txt"),
                Instant.now(), Instant.now());
    }

    @Test
    void saveAndLoad_roundTrip() {
        var cp = sampleCheckpoint("plan-1", "session-1", "ws-abc", 1);
        store.save(cp);

        var loaded = store.load("plan-1");
        assertTrue(loaded.isPresent());
        var lcp = loaded.get();
        assertEquals("plan-1", lcp.planId());
        assertEquals("session-1", lcp.sessionId());
        assertEquals("ws-abc", lcp.workspaceHash());
        assertEquals("Build feature X", lcp.originalGoal());
        assertEquals(1, lcp.lastCompletedStepIndex());
        assertEquals(2, lcp.completedStepResults().size());
        assertTrue(lcp.completedStepResults().get(0).success());
        assertEquals("output-1", lcp.completedStepResults().get(1).output());
        assertEquals(PlanCheckpoint.CheckpointStatus.ACTIVE, lcp.status());
        assertEquals("hint", lcp.resumeHint());
        assertEquals(1, lcp.artifacts().size());
        assertEquals(1, lcp.conversationSnapshot().size());
    }

    @Test
    void load_nonExistent_returnsEmpty() {
        assertTrue(store.load("nonexistent").isEmpty());
    }

    @Test
    void save_overwritesExisting() {
        var cp1 = sampleCheckpoint("plan-1", "session-1", "ws-abc", 0);
        store.save(cp1);

        var cp2 = cp1.withStepCompleted(1,
                new StepResult(true, "step2 done", null, 200, 80, 40),
                cp1.plan(), List.of(), "Updated hint", List.of());
        store.save(cp2);

        var loaded = store.load("plan-1");
        assertTrue(loaded.isPresent());
        assertEquals(1, loaded.get().lastCompletedStepIndex());
        assertEquals(2, loaded.get().completedStepResults().size());
    }

    @Test
    void findResumable_filtersByWorkspaceAndStatus() {
        store.save(sampleCheckpoint("plan-1", "s1", "ws-A", 1));
        store.save(sampleCheckpoint("plan-2", "s2", "ws-B", 0));
        store.save(sampleCheckpoint("plan-3", "s3", "ws-A", 2));

        var resumable = store.findResumable("ws-A");
        assertEquals(2, resumable.size());
        assertTrue(resumable.stream().allMatch(c -> "ws-A".equals(c.workspaceHash())));
    }

    @Test
    void findResumable_excludesNonResumableStatuses() {
        store.save(sampleCheckpoint("plan-1", "s1", "ws-A", 1));
        store.markCompleted("plan-1");

        var resumable = store.findResumable("ws-A");
        assertTrue(resumable.isEmpty());
    }

    @Test
    void findBySession_returnsMatchingCheckpoints() {
        store.save(sampleCheckpoint("plan-1", "session-X", "ws-A", 1));
        store.save(sampleCheckpoint("plan-2", "session-Y", "ws-A", 0));
        store.save(sampleCheckpoint("plan-3", "session-X", "ws-B", 2));

        var bySession = store.findBySession("session-X");
        assertEquals(2, bySession.size());
        assertTrue(bySession.stream().allMatch(c -> "session-X".equals(c.sessionId())));
    }

    @Test
    void markResumed_updatesStatus() {
        store.save(sampleCheckpoint("plan-1", "s1", "ws-A", 1));
        store.markResumed("plan-1");

        var loaded = store.load("plan-1");
        assertTrue(loaded.isPresent());
        assertEquals(PlanCheckpoint.CheckpointStatus.RESUMED, loaded.get().status());
    }

    @Test
    void markCompleted_updatesStatus() {
        store.save(sampleCheckpoint("plan-1", "s1", "ws-A", 1));
        store.markCompleted("plan-1");

        var loaded = store.load("plan-1");
        assertTrue(loaded.isPresent());
        assertEquals(PlanCheckpoint.CheckpointStatus.COMPLETED, loaded.get().status());
    }

    @Test
    void markFailed_updatesStatus() {
        store.save(sampleCheckpoint("plan-1", "s1", "ws-A", 1));
        store.markFailed("plan-1");

        var loaded = store.load("plan-1");
        assertTrue(loaded.isPresent());
        assertEquals(PlanCheckpoint.CheckpointStatus.FAILED, loaded.get().status());
    }

    @Test
    void planStatusSerialization_executingStatus() {
        var plan = new TaskPlan("plan-ex", "goal", List.of(
                new PlannedStep("s1", "Step 1", "desc", List.of(), null, StepStatus.COMPLETED),
                new PlannedStep("s2", "Step 2", "desc", List.of(), null, StepStatus.PENDING)
        ), new PlanStatus.Executing(1, 2), Instant.now());

        var cp = new PlanCheckpoint("plan-ex", "s1", "ws", "goal", plan,
                List.of(new StepResult(true, "done", null, 100, 50, 25)),
                0, List.of(), PlanCheckpoint.CheckpointStatus.ACTIVE,
                null, List.of(), Instant.now(), Instant.now());
        store.save(cp);

        var loaded = store.load("plan-ex");
        assertTrue(loaded.isPresent());
        assertInstanceOf(PlanStatus.Executing.class, loaded.get().plan().status());
        var exec = (PlanStatus.Executing) loaded.get().plan().status();
        assertEquals(1, exec.completedSteps());
        assertEquals(2, exec.totalSteps());
    }

    @Test
    void planStatusSerialization_completedStatus() {
        var plan = new TaskPlan("plan-c", "goal", List.of(
                new PlannedStep("s1", "Step 1", "desc", List.of(), null, StepStatus.COMPLETED)
        ), new PlanStatus.Completed(java.time.Duration.ofMillis(5000)), Instant.now());

        var cp = new PlanCheckpoint("plan-c", "s1", "ws", "goal", plan,
                List.of(new StepResult(true, "done", null, 5000, 100, 50)),
                0, List.of(), PlanCheckpoint.CheckpointStatus.COMPLETED,
                null, List.of(), Instant.now(), Instant.now());
        store.save(cp);

        var loaded = store.load("plan-c");
        assertTrue(loaded.isPresent());
        assertInstanceOf(PlanStatus.Completed.class, loaded.get().plan().status());
    }

    @Test
    void planStatusSerialization_failedStatus() {
        var plan = new TaskPlan("plan-f", "goal", List.of(
                new PlannedStep("s1", "Step 1", "desc", List.of(), null, StepStatus.FAILED)
        ), new PlanStatus.Failed("timeout", "s1"), Instant.now());

        var cp = new PlanCheckpoint("plan-f", "s1", "ws", "goal", plan,
                List.of(new StepResult(false, null, "timeout", 1000, 50, 25)),
                0, List.of(), PlanCheckpoint.CheckpointStatus.FAILED,
                "timeout", List.of(), Instant.now(), Instant.now());
        store.save(cp);

        var loaded = store.load("plan-f");
        assertTrue(loaded.isPresent());
        assertInstanceOf(PlanStatus.Failed.class, loaded.get().plan().status());
        assertEquals("timeout", ((PlanStatus.Failed) loaded.get().plan().status()).reason());
    }

    @Test
    void stepDetailsPreserved_afterRoundTrip() {
        var plan = new TaskPlan("plan-steps", "goal", List.of(
                new PlannedStep("s1", "Research", "Find APIs",
                        List.of("bash", "grep"), "manual search", StepStatus.COMPLETED),
                new PlannedStep("s2", "Implement", "Write code",
                        List.of("edit_file"), null, StepStatus.PENDING)
        ), new PlanStatus.Executing(1, 2), Instant.now());

        var cp = new PlanCheckpoint("plan-steps", "s1", "ws", "goal", plan,
                List.of(new StepResult(true, "found 3 APIs", null, 2000, 200, 100)),
                0, List.of(), PlanCheckpoint.CheckpointStatus.ACTIVE,
                null, List.of(), Instant.now(), Instant.now());
        store.save(cp);

        var loaded = store.load("plan-steps").orElseThrow();
        var steps = loaded.plan().steps();
        assertEquals(2, steps.size());

        var s1 = steps.get(0);
        assertEquals("Research", s1.name());
        assertEquals("Find APIs", s1.description());
        assertEquals(List.of("bash", "grep"), s1.requiredTools());
        assertEquals("manual search", s1.fallbackApproach());
        assertEquals(StepStatus.COMPLETED, s1.status());

        var s2 = steps.get(1);
        assertEquals("Implement", s2.name());
        assertNull(s2.fallbackApproach());
        assertEquals(StepStatus.PENDING, s2.status());
    }

    @Test
    void cleanup_deletesOldCheckpoints() {
        // Save a checkpoint with very old updatedAt
        var now = Instant.now();
        var oldTime = now.minus(java.time.Duration.ofDays(30));
        var plan = samplePlan("plan-old", 2);
        var cp = new PlanCheckpoint("plan-old", "s1", "ws", "goal", plan,
                List.of(), -1, List.of(), PlanCheckpoint.CheckpointStatus.COMPLETED,
                null, List.of(), oldTime, oldTime);
        store.save(cp);

        // Save a recent checkpoint
        store.save(sampleCheckpoint("plan-new", "s2", "ws", 0));

        int deleted = store.cleanup(7);

        assertEquals(1, deleted);
        assertTrue(store.load("plan-old").isEmpty());
        assertTrue(store.load("plan-new").isPresent());
    }

    @Test
    void cleanup_deletesCorruptFiles() throws IOException {
        // Save a valid checkpoint
        store.save(sampleCheckpoint("plan-valid", "s1", "ws", 0));

        // Write a corrupt checkpoint file
        Files.writeString(tempDir.resolve("plan-corrupt.checkpoint.json"), "not-valid-json{{{");

        int deleted = store.cleanup(7);

        // Corrupt file should be deleted
        assertEquals(1, deleted);
        assertFalse(Files.exists(tempDir.resolve("plan-corrupt.checkpoint.json")));
        // Valid checkpoint should remain
        assertTrue(store.load("plan-valid").isPresent());
    }

    @Test
    void cleanup_emptyDirectory_returnsZero() {
        assertEquals(0, store.cleanup(7));
    }
}

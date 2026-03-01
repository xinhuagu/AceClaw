package dev.aceclaw.daemon;

import dev.aceclaw.core.planner.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ResumeRouterTest {

    private static TaskPlan samplePlan(int stepCount) {
        var steps = new ArrayList<PlannedStep>();
        for (int i = 0; i < stepCount; i++) {
            steps.add(new PlannedStep(
                    "step-" + i, "Step " + (i + 1), "Do step " + (i + 1),
                    List.of("bash"), null, StepStatus.PENDING));
        }
        return new TaskPlan("plan-1", "Build feature X", steps,
                new PlanStatus.Draft(), Instant.now());
    }

    private static PlanCheckpoint checkpoint(String planId, String sessionId,
                                              String wsHash, int lastCompleted,
                                              PlanCheckpoint.CheckpointStatus status,
                                              Instant updatedAt) {
        var plan = samplePlan(4);
        var results = new ArrayList<StepResult>();
        for (int i = 0; i <= lastCompleted; i++) {
            results.add(new StepResult(true, "out-" + i, null, 100, 50, 25));
        }
        return new PlanCheckpoint(
                planId, sessionId, wsHash, "goal", plan,
                results, lastCompleted, List.of(),
                status, null, List.of(),
                Instant.now(), updatedAt);
    }

    /**
     * In-memory PlanCheckpointStore for testing.
     */
    static class InMemoryCheckpointStore implements PlanCheckpointStore {
        private final java.util.Map<String, PlanCheckpoint> store = new java.util.LinkedHashMap<>();

        @Override
        public void save(PlanCheckpoint checkpoint) {
            store.put(checkpoint.planId(), checkpoint);
        }

        @Override
        public Optional<PlanCheckpoint> load(String planId) {
            return Optional.ofNullable(store.get(planId));
        }

        @Override
        public List<PlanCheckpoint> findResumable(String workspaceHash) {
            return store.values().stream()
                    .filter(c -> workspaceHash.equals(c.workspaceHash()))
                    .filter(c -> isResumable(c.status()))
                    .toList();
        }

        @Override
        public List<PlanCheckpoint> findBySession(String sessionId) {
            return store.values().stream()
                    .filter(c -> sessionId.equals(c.sessionId()))
                    .filter(c -> isResumable(c.status()))
                    .toList();
        }

        @Override
        public void markResumed(String planId) { updateStatus(planId, PlanCheckpoint.CheckpointStatus.RESUMED); }
        @Override
        public void markCompleted(String planId) { updateStatus(planId, PlanCheckpoint.CheckpointStatus.COMPLETED); }
        @Override
        public void markFailed(String planId) { updateStatus(planId, PlanCheckpoint.CheckpointStatus.FAILED); }
        @Override
        public int cleanup(int maxAgeDays) { return 0; }

        private void updateStatus(String planId, PlanCheckpoint.CheckpointStatus status) {
            var cp = store.get(planId);
            if (cp != null) store.put(planId, cp.withStatus(status));
        }

        private boolean isResumable(PlanCheckpoint.CheckpointStatus status) {
            return status == PlanCheckpoint.CheckpointStatus.ACTIVE
                    || status == PlanCheckpoint.CheckpointStatus.INTERRUPTED;
        }
    }

    @Test
    void route_sameSession_returnsSessionRoute() {
        var mem = new InMemoryCheckpointStore();
        mem.save(checkpoint("p1", "session-A", "ws-1", 1,
                PlanCheckpoint.CheckpointStatus.ACTIVE, Instant.now()));

        var router = new ResumeRouter(mem);
        var decision = router.route("session-A", Path.of("/project"));

        assertTrue(decision.hasCheckpoint());
        assertEquals("session", decision.route());
        assertEquals("p1", decision.checkpoint().planId());
        assertFalse(decision.ambiguous());
    }

    @Test
    void route_differentSessionSameWorkspace_returnsWorkspaceRoute() {
        var mem = new InMemoryCheckpointStore();
        // Compute the actual workspace hash for /project
        var wsHash = ResumeRouter.hashWorkspace(Path.of("/project"));
        mem.save(checkpoint("p1", "session-OLD", wsHash, 1,
                PlanCheckpoint.CheckpointStatus.ACTIVE, Instant.now()));

        var router = new ResumeRouter(mem);
        var decision = router.route("session-NEW", Path.of("/project"));

        assertTrue(decision.hasCheckpoint());
        assertEquals("workspace", decision.route());
        assertEquals("p1", decision.checkpoint().planId());
    }

    @Test
    void route_differentWorkspace_returnsNone() {
        var mem = new InMemoryCheckpointStore();
        var wsHash = ResumeRouter.hashWorkspace(Path.of("/other-project"));
        mem.save(checkpoint("p1", "session-A", wsHash, 1,
                PlanCheckpoint.CheckpointStatus.ACTIVE, Instant.now()));

        var router = new ResumeRouter(mem);
        var decision = router.route("session-B", Path.of("/this-project"));

        assertFalse(decision.hasCheckpoint());
        assertEquals("none", decision.route());
    }

    @Test
    void route_noCheckpoints_returnsNone() {
        var mem = new InMemoryCheckpointStore();
        var router = new ResumeRouter(mem);
        var decision = router.route("session-A", Path.of("/project"));

        assertFalse(decision.hasCheckpoint());
        assertEquals("none", decision.route());
    }

    @Test
    void route_nonResumableStatus_ignored() {
        var mem = new InMemoryCheckpointStore();
        mem.save(checkpoint("p1", "session-A", "ws-1", 1,
                PlanCheckpoint.CheckpointStatus.COMPLETED, Instant.now()));
        mem.save(checkpoint("p2", "session-A", "ws-1", 0,
                PlanCheckpoint.CheckpointStatus.FAILED, Instant.now()));

        var router = new ResumeRouter(mem);
        var decision = router.route("session-A", Path.of("/project"));

        assertFalse(decision.hasCheckpoint());
    }

    @Test
    void route_interruptedStatus_isResumable() {
        var mem = new InMemoryCheckpointStore();
        mem.save(checkpoint("p1", "session-A", "ws-1", 1,
                PlanCheckpoint.CheckpointStatus.INTERRUPTED, Instant.now()));

        var router = new ResumeRouter(mem);
        var decision = router.route("session-A", Path.of("/project"));

        assertTrue(decision.hasCheckpoint());
    }

    @Test
    void route_multipleCheckpoints_returnsNewest() {
        var mem = new InMemoryCheckpointStore();
        mem.save(checkpoint("p-old", "session-A", "ws-1", 0,
                PlanCheckpoint.CheckpointStatus.ACTIVE, Instant.parse("2026-01-01T00:00:00Z")));
        mem.save(checkpoint("p-new", "session-A", "ws-1", 2,
                PlanCheckpoint.CheckpointStatus.ACTIVE, Instant.parse("2026-03-01T00:00:00Z")));

        var router = new ResumeRouter(mem);
        var decision = router.route("session-A", Path.of("/project"));

        assertTrue(decision.hasCheckpoint());
        assertEquals("p-new", decision.checkpoint().planId());
        assertTrue(decision.ambiguous());
    }

    @Test
    void route_sessionPriorityOverWorkspace() {
        var mem = new InMemoryCheckpointStore();
        var wsHash = ResumeRouter.hashWorkspace(Path.of("/project"));
        // Older checkpoint in same session
        mem.save(checkpoint("p-session", "session-A", wsHash, 0,
                PlanCheckpoint.CheckpointStatus.ACTIVE, Instant.parse("2026-01-01T00:00:00Z")));
        // Newer checkpoint in different session but same workspace
        mem.save(checkpoint("p-workspace", "session-OLD", wsHash, 2,
                PlanCheckpoint.CheckpointStatus.ACTIVE, Instant.parse("2026-03-01T00:00:00Z")));

        var router = new ResumeRouter(mem);
        var decision = router.route("session-A", Path.of("/project"));

        assertTrue(decision.hasCheckpoint());
        assertEquals("session", decision.route());
        assertEquals("p-session", decision.checkpoint().planId());
    }

    @Test
    void hashWorkspace_deterministic() {
        var hash1 = ResumeRouter.hashWorkspace(Path.of("/some/project"));
        var hash2 = ResumeRouter.hashWorkspace(Path.of("/some/project"));
        assertEquals(hash1, hash2);
    }

    @Test
    void hashWorkspace_differentPaths_differentHashes() {
        var hash1 = ResumeRouter.hashWorkspace(Path.of("/project-a"));
        var hash2 = ResumeRouter.hashWorkspace(Path.of("/project-b"));
        assertNotEquals(hash1, hash2);
    }

    @Test
    void buildResumePrompt_includesCompletedSteps() {
        var plan = samplePlan(4);
        var cp = new PlanCheckpoint(
                "plan-1", "s1", "ws", "Build feature X", plan,
                List.of(
                        new StepResult(true, "Found the APIs", null, 100, 50, 25),
                        new StepResult(true, "Wrote the code", null, 200, 80, 40)
                ),
                1, List.of(), PlanCheckpoint.CheckpointStatus.ACTIVE,
                "Need to test", List.of("api.java"),
                Instant.now(), Instant.now());

        var prompt = ResumeRouter.buildResumePrompt(cp);

        assertTrue(prompt.contains("[PLAN_RESUME_CONTEXT]"));
        assertTrue(prompt.contains("[/PLAN_RESUME_CONTEXT]"));
        assertTrue(prompt.contains("planId: plan-1"));
        assertTrue(prompt.contains("originalGoal: Build feature X"));
        assertTrue(prompt.contains("completedSteps: 2/4"));
        assertTrue(prompt.contains("Step 1:"));
        assertTrue(prompt.contains("[OK]"));
        assertTrue(prompt.contains("nextStep: 3"));
        assertTrue(prompt.contains("resumeHint: Need to test"));
        assertTrue(prompt.contains("artifactsProduced: api.java"));
        assertTrue(prompt.contains("Continue from step 3"));
    }

    @Test
    void buildResumePrompt_noRemainingSteps() {
        var plan = samplePlan(1);
        var cp = new PlanCheckpoint(
                "plan-1", "s1", "ws", "goal", plan,
                List.of(new StepResult(true, "done", null, 100, 50, 25)),
                0, List.of(), PlanCheckpoint.CheckpointStatus.ACTIVE,
                null, List.of(), Instant.now(), Instant.now());

        var prompt = ResumeRouter.buildResumePrompt(cp);
        assertTrue(prompt.contains("completedSteps: 1/1"));
        // Should not crash even with no remaining steps
        assertFalse(prompt.contains("nextStep:"));
    }
}

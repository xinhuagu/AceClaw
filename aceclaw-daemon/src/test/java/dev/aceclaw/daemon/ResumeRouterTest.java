package dev.aceclaw.daemon;

import dev.aceclaw.core.planner.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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
        assertTrue(prompt.contains("goal: Build feature X"));
        assertTrue(prompt.contains("progress: 2/4"));
        assertTrue(prompt.contains("Step 1:"));
        assertTrue(prompt.contains("[OK]"));
        assertTrue(prompt.contains("nextStep:"));
        assertTrue(prompt.contains("index: 3"));
        assertTrue(prompt.contains("doNotRepeat:"));
        assertTrue(prompt.contains("Need to test"));
        assertTrue(prompt.contains("artifacts:"));
        assertTrue(prompt.contains("api.java"));
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
        assertTrue(prompt.contains("progress: 1/1"));
        // Should not crash even with no remaining steps
        assertFalse(prompt.contains("nextStep:\n"));
    }

    // -- Turn-checkpoint priority tests (#501) ---------------------------------

    static class InMemoryTurnStore implements TurnCheckpointStore {
        private final java.util.Map<String, TurnCheckpoint> store = new java.util.LinkedHashMap<>();

        @Override
        public void save(TurnCheckpoint checkpoint) {
            store.put(checkpoint.turnId(), checkpoint);
        }

        @Override
        public Optional<TurnCheckpoint> load(String turnId) {
            return Optional.ofNullable(store.get(turnId));
        }

        @Override
        public List<TurnCheckpoint> findResumable(String workspaceHash) {
            return store.values().stream()
                    .filter(c -> workspaceHash.equals(c.workspaceHash()))
                    .filter(c -> isResumable(c.status()))
                    .toList();
        }

        @Override
        public List<TurnCheckpoint> findBySession(String sessionId) {
            return store.values().stream()
                    .filter(c -> sessionId.equals(c.sessionId()))
                    .filter(c -> isResumable(c.status()))
                    .toList();
        }

        @Override public void markResumed(String turnId) { updateStatus(turnId, PlanCheckpoint.CheckpointStatus.RESUMED); }
        @Override public void markCompleted(String turnId) { updateStatus(turnId, PlanCheckpoint.CheckpointStatus.COMPLETED); }
        @Override public void markFailed(String turnId) { updateStatus(turnId, PlanCheckpoint.CheckpointStatus.FAILED); }
        @Override public void markInterrupted(String turnId) { updateStatus(turnId, PlanCheckpoint.CheckpointStatus.INTERRUPTED); }
        @Override public void delete(String turnId) { store.remove(turnId); }
        @Override public int cleanup(int maxAgeDays) { return 0; }

        private void updateStatus(String turnId, PlanCheckpoint.CheckpointStatus status) {
            var cp = store.get(turnId);
            if (cp != null) store.put(turnId, cp.withStatus(status));
        }

        private boolean isResumable(PlanCheckpoint.CheckpointStatus status) {
            return status == PlanCheckpoint.CheckpointStatus.ACTIVE
                    || status == PlanCheckpoint.CheckpointStatus.INTERRUPTED;
        }
    }

    private static TurnCheckpoint turnCp(String turnId, String sessionId, String wsHash,
                                          int completedIterations,
                                          PlanCheckpoint.CheckpointStatus status,
                                          Instant updatedAt) {
        return new TurnCheckpoint(
                turnId, sessionId, wsHash, "fix it",
                List.of(), completedIterations, "tool-use-" + completedIterations,
                List.of(), status, Instant.now(), updatedAt);
    }

    @Test
    void route_planBeatsTurnInSameSession() {
        var planStore = new InMemoryCheckpointStore();
        var turnStore = new InMemoryTurnStore();
        var wsHash = ResumeRouter.hashWorkspace(Path.of("/project"));

        planStore.save(checkpoint("plan-1", "session-A", wsHash, 1,
                PlanCheckpoint.CheckpointStatus.ACTIVE, Instant.parse("2026-01-01T00:00:00Z")));
        // Newer turn checkpoint — but plan still wins because plan tier > turn tier.
        turnStore.save(turnCp("turn-1", "session-A", wsHash, 2,
                PlanCheckpoint.CheckpointStatus.ACTIVE, Instant.parse("2026-06-01T00:00:00Z")));

        var router = new ResumeRouter(planStore, turnStore);
        var decision = router.route("session-A", Path.of("/project"));

        assertTrue(decision.hasCheckpoint());
        assertEquals("session", decision.route());
        assertInstanceOf(Resumable.OfPlan.class, decision.resumable());
        assertEquals("plan-1", decision.checkpoint().planId());
    }

    @Test
    void route_turnSessionBeatsTurnWorkspace() {
        var planStore = new InMemoryCheckpointStore();
        var turnStore = new InMemoryTurnStore();
        var wsHash = ResumeRouter.hashWorkspace(Path.of("/project"));

        // Older turn in same session — but session tier beats workspace tier.
        turnStore.save(turnCp("turn-session", "session-A", wsHash, 1,
                PlanCheckpoint.CheckpointStatus.ACTIVE, Instant.parse("2026-01-01T00:00:00Z")));
        turnStore.save(turnCp("turn-workspace", "session-OLD", wsHash, 3,
                PlanCheckpoint.CheckpointStatus.ACTIVE, Instant.parse("2026-06-01T00:00:00Z")));

        var router = new ResumeRouter(planStore, turnStore);
        var decision = router.route("session-A", Path.of("/project"));

        assertTrue(decision.hasCheckpoint());
        assertEquals("turn_session", decision.route());
        assertInstanceOf(Resumable.OfTurn.class, decision.resumable());
    }

    @Test
    void route_turnWorkspaceMatch() {
        var planStore = new InMemoryCheckpointStore();
        var turnStore = new InMemoryTurnStore();
        var wsHash = ResumeRouter.hashWorkspace(Path.of("/project"));

        turnStore.save(turnCp("turn-1", "session-OLD", wsHash, 2,
                PlanCheckpoint.CheckpointStatus.ACTIVE, Instant.now()));

        var router = new ResumeRouter(planStore, turnStore);
        var decision = router.route("session-NEW", Path.of("/project"));

        assertTrue(decision.hasCheckpoint());
        assertEquals("turn_workspace", decision.route());
    }

    @Test
    void route_turnInterruptedStatusIsResumable() {
        var planStore = new InMemoryCheckpointStore();
        var turnStore = new InMemoryTurnStore();
        var wsHash = ResumeRouter.hashWorkspace(Path.of("/project"));

        turnStore.save(turnCp("turn-1", "session-A", wsHash, 2,
                PlanCheckpoint.CheckpointStatus.INTERRUPTED, Instant.now()));

        var router = new ResumeRouter(planStore, turnStore);
        var decision = router.route("session-A", Path.of("/project"));

        assertTrue(decision.hasCheckpoint());
        assertEquals("turn_session", decision.route());
    }

    @Test
    void route_turnCompletedStatusIgnored() {
        var planStore = new InMemoryCheckpointStore();
        var turnStore = new InMemoryTurnStore();
        var wsHash = ResumeRouter.hashWorkspace(Path.of("/project"));

        turnStore.save(turnCp("turn-1", "session-A", wsHash, 2,
                PlanCheckpoint.CheckpointStatus.COMPLETED, Instant.now()));
        turnStore.save(turnCp("turn-2", "session-A", wsHash, 2,
                PlanCheckpoint.CheckpointStatus.RESUMED, Instant.now()));
        turnStore.save(turnCp("turn-3", "session-A", wsHash, 2,
                PlanCheckpoint.CheckpointStatus.FAILED, Instant.now()));

        var router = new ResumeRouter(planStore, turnStore);
        assertFalse(router.route("session-A", Path.of("/project")).hasCheckpoint());
    }

    @Test
    void route_turnStoreNull_skipsTurnTier() {
        // Backward compat: existing callers can construct with plan-only and
        // the router must not NPE on turn lookup.
        var planStore = new InMemoryCheckpointStore();
        var router = new ResumeRouter(planStore); // delegating constructor, turn=null

        var decision = router.route("session-A", Path.of("/project"));
        assertFalse(decision.hasCheckpoint());
    }

    @Test
    void findAllResumable_returnsBoth_whenPlanAndTurnExist() {
        var planStore = new InMemoryCheckpointStore();
        var turnStore = new InMemoryTurnStore();
        var wsHash = ResumeRouter.hashWorkspace(Path.of("/project"));

        planStore.save(checkpoint("plan-1", "session-A", wsHash, 1,
                PlanCheckpoint.CheckpointStatus.ACTIVE, Instant.now()));
        turnStore.save(turnCp("turn-1", "session-A", wsHash, 2,
                PlanCheckpoint.CheckpointStatus.ACTIVE, Instant.now()));

        var router = new ResumeRouter(planStore, turnStore);
        var all = router.findAllResumable("session-A", Path.of("/project"));

        assertEquals(2, all.size());
        // Plan first (priority order)
        assertInstanceOf(Resumable.OfPlan.class, all.get(0));
        assertInstanceOf(Resumable.OfTurn.class, all.get(1));
    }

    @Test
    void findAllResumable_singleEntry_whenOnlyOneTypeExists() {
        var planStore = new InMemoryCheckpointStore();
        var turnStore = new InMemoryTurnStore();
        var wsHash = ResumeRouter.hashWorkspace(Path.of("/project"));
        turnStore.save(turnCp("turn-1", "session-A", wsHash, 2,
                PlanCheckpoint.CheckpointStatus.ACTIVE, Instant.now()));

        var router = new ResumeRouter(planStore, turnStore);
        var all = router.findAllResumable("session-A", Path.of("/project"));

        assertEquals(1, all.size());
        assertInstanceOf(Resumable.OfTurn.class, all.get(0));
    }

    @Test
    void findAllResumable_empty_whenNothingResumable() {
        var router = new ResumeRouter(new InMemoryCheckpointStore(), new InMemoryTurnStore());
        assertTrue(router.findAllResumable("session-A", Path.of("/project")).isEmpty());
    }

    @Test
    void buildTurnResumePrompt_includesAllSections() {
        var cp = new TurnCheckpoint(
                "turn-1", "session-A", "ws-hash",
                "Refactor the auth module to use the new permission API",
                List.of("{\"role\":\"user\",\"content\":\"go\"}"),
                3, "tool-use-abc",
                List.of("auth/Permissions.java", "auth/PermissionTest.java"),
                PlanCheckpoint.CheckpointStatus.ACTIVE,
                Instant.now(), Instant.now());

        var prompt = ResumeRouter.buildTurnResumePrompt(cp);

        assertTrue(prompt.contains("[TURN_RESUME_CONTEXT]"));
        assertTrue(prompt.contains("[/TURN_RESUME_CONTEXT]"));
        assertTrue(prompt.contains("originalPrompt: Refactor the auth module"));
        assertTrue(prompt.contains("turnId: turn-1"));
        assertTrue(prompt.contains("completedIterations: 3"));
        assertTrue(prompt.contains("3 iteration(s) already executed"));
        assertTrue(prompt.contains("lastToolUseId: tool-use-abc"));
        assertTrue(prompt.contains("artifacts:"));
        assertTrue(prompt.contains("auth/Permissions.java"));
        assertTrue(prompt.contains("Continue from iteration 4"));
        assertTrue(prompt.contains("Do not repeat"));
    }

    @Test
    void buildTurnResumePrompt_zeroIterations() {
        var cp = new TurnCheckpoint(
                "turn-1", "session-A", "ws-hash", "Do X",
                List.of(), 0, null, List.of(),
                PlanCheckpoint.CheckpointStatus.ACTIVE,
                Instant.now(), Instant.now());

        var prompt = ResumeRouter.buildTurnResumePrompt(cp);
        assertTrue(prompt.contains("completedIterations: 0"));
        assertTrue(prompt.contains("no completed iterations recorded"));
        assertTrue(prompt.contains("Continue from iteration 1"));
    }

    @Test
    void summarizeIterations_extractsToolUseAndResults() {
        // Snapshot in the ConversationSnapshot.serialize shape: each message is
        // one JSON object {"role":"...","blocks":[ ... ]} per line.
        var snapshot = List.of(
                "{\"role\":\"user\",\"blocks\":[{\"type\":\"text\",\"text\":\"go\"}]}",
                "{\"role\":\"assistant\",\"blocks\":["
                        + "{\"type\":\"text\",\"text\":\"reading\"},"
                        + "{\"type\":\"tool_use\",\"id\":\"tu-1\",\"name\":\"read_file\",\"input\":\"{\\\"path\\\":\\\"a.java\\\"}\"}]}",
                "{\"role\":\"user\",\"blocks\":["
                        + "{\"type\":\"tool_result\",\"tool_use_id\":\"tu-1\",\"content\":\"file contents 42\",\"is_error\":false}]}",
                "{\"role\":\"assistant\",\"blocks\":["
                        + "{\"type\":\"tool_use\",\"id\":\"tu-2\",\"name\":\"bash\",\"input\":\"{\\\"cmd\\\":\\\"ls\\\"}\"}]}",
                "{\"role\":\"user\",\"blocks\":["
                        + "{\"type\":\"tool_result\",\"tool_use_id\":\"tu-2\",\"content\":\"permission denied\",\"is_error\":true}]}"
        );

        var lines = ResumeRouter.summarizeIterations(snapshot);

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).contains("iter 1");
        assertThat(lines.get(0)).contains("read_file");
        assertThat(lines.get(0)).contains("ok:");
        assertThat(lines.get(0)).contains("file contents 42");
        assertThat(lines.get(1)).contains("iter 2");
        assertThat(lines.get(1)).contains("bash");
        assertThat(lines.get(1)).contains("FAILED:");
        assertThat(lines.get(1)).contains("permission denied");
    }

    @Test
    void summarizeIterations_emptyForEmptyOrLegacySnapshot() {
        assertThat(ResumeRouter.summarizeIterations(null)).isEmpty();
        assertThat(ResumeRouter.summarizeIterations(List.of())).isEmpty();
        // Legacy plan-checkpoint shape — no tool_use blocks
        assertThat(ResumeRouter.summarizeIterations(List.of(
                "{\"role\":\"user\",\"content\":\"hi\"}"
        ))).isEmpty();
    }

    @Test
    void summarizeIterations_handlesToolUseWithoutResult() {
        // A tool_use that crashed mid-execution and never got a result.
        var snapshot = List.of(
                "{\"role\":\"assistant\",\"blocks\":["
                        + "{\"type\":\"tool_use\",\"id\":\"tu-1\",\"name\":\"bash\",\"input\":\"{}\"}]}"
        );
        var lines = ResumeRouter.summarizeIterations(snapshot);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).contains("no result recorded");
    }

    @Test
    void buildTurnResumePrompt_respectsHardSizeCap() {
        var bigPrompt = "Q: " + "x".repeat(20_000);
        var cp = new TurnCheckpoint(
                "turn-1", "session-A", "ws-hash", bigPrompt,
                List.of(), 5, "tool-use-abc",
                List.of("a.txt", "b.txt", "c.txt"),
                PlanCheckpoint.CheckpointStatus.ACTIVE,
                Instant.now(), Instant.now());

        var prompt = ResumeRouter.buildTurnResumePrompt(cp, 800);

        assertThat(prompt.length()).isLessThanOrEqualTo(800);
        // Must still include the framing + the action line so the LLM has a
        // chance to do the right thing — content sections get truncated, not
        // the structural ones.
        assertThat(prompt).contains("[TURN_RESUME_CONTEXT]");
        assertThat(prompt).contains("Continue from iteration 6");
    }

    @Test
    void buildResumePromptBudgetsSectionsIndependently() {
        var plan = samplePlan(4);
        var longOutput = "step-output ".repeat(200);
        var cp = new PlanCheckpoint(
                "plan-1", "s1", "ws", "Build feature X", plan,
                List.of(
                        new StepResult(true, longOutput, null, 100, 50, 25),
                        new StepResult(true, longOutput, null, 200, 80, 40)
                ),
                1, List.of(), PlanCheckpoint.CheckpointStatus.ACTIVE,
                "Do not rerun migrations",
                List.of("artifact-a.txt", "artifact-b.txt", "artifact-c.txt", "artifact-d.txt"),
                Instant.now(), Instant.now());

        var prompt = ResumeRouter.buildResumePrompt(cp, 700);

        assertThat(prompt.length()).isLessThanOrEqualTo(700);
        assertThat(prompt).contains("[PLAN_RESUME_CONTEXT]");
        assertThat(prompt).contains("nextStep:");
        assertThat(prompt).contains("Do not rerun migrations");
        assertThat(prompt).contains("Continue from step 3");
    }
}

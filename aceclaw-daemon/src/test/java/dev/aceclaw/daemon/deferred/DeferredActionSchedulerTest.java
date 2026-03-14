package dev.aceclaw.daemon.deferred;

import dev.aceclaw.core.agent.ToolRegistry;
import dev.aceclaw.daemon.AgentSession;
import dev.aceclaw.daemon.MockLlmClient;
import dev.aceclaw.daemon.SessionManager;
import dev.aceclaw.infra.event.DeferEvent;
import dev.aceclaw.infra.event.EventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for deferred action store, scheduler, and tool.
 */
class DeferredActionSchedulerTest {

    @TempDir
    Path tempDir;

    private DeferredActionStore store;

    @BeforeEach
    void setUp() throws IOException {
        store = new DeferredActionStore(tempDir);
        store.load();
    }

    // -- DeferredActionStore tests --

    @Test
    void storeRoundTrip() throws IOException {
        var action = createAction("sess-1", "check build status");
        store.put(action);
        store.save();

        // Reload from disk
        var store2 = new DeferredActionStore(tempDir);
        store2.load();

        var loaded = store2.get(action.actionId());
        assertTrue(loaded.isPresent());
        assertEquals(action.actionId(), loaded.get().actionId());
        assertEquals(action.goal(), loaded.get().goal());
        assertEquals(action.sessionId(), loaded.get().sessionId());
        assertEquals(DeferredActionState.PENDING, loaded.get().state());
    }

    @Test
    void storeAllPending() {
        var a1 = createAction("sess-1", "goal 1");
        var a2 = createAction("sess-1", "goal 2");
        var a3 = createAction("sess-1", "goal 3").withState(DeferredActionState.COMPLETED);

        store.put(a1);
        store.put(a2);
        store.put(a3);

        assertEquals(2, store.allPending().size());
        assertEquals(3, store.size());
    }

    @Test
    void storeBySession() {
        var a1 = createAction("sess-1", "goal 1");
        var a2 = createAction("sess-2", "goal 2");

        store.put(a1);
        store.put(a2);

        assertEquals(1, store.bySession("sess-1").size());
        assertEquals(1, store.bySession("sess-2").size());
        assertEquals(0, store.bySession("sess-3").size());
    }

    @Test
    void storeFindByIdempotencyKey() {
        var action = createAction("sess-1", "check build");
        store.put(action);

        var found = store.findByIdempotencyKey(action.idempotencyKey());
        assertTrue(found.isPresent());
        assertEquals(action.actionId(), found.get().actionId());

        // Completed actions should not be found
        store.put(action.withState(DeferredActionState.COMPLETED));
        var notFound = store.findByIdempotencyKey(action.idempotencyKey());
        assertFalse(notFound.isPresent());
    }

    @Test
    void storeRemove() {
        var action = createAction("sess-1", "goal");
        store.put(action);
        assertEquals(1, store.size());

        assertTrue(store.remove(action.actionId()));
        assertEquals(0, store.size());
        assertFalse(store.remove(action.actionId()));
    }

    @Test
    void storePendingCountForSession() {
        store.put(createAction("sess-1", "g1"));
        store.put(createAction("sess-1", "g2"));
        store.put(createAction("sess-2", "g3"));

        assertEquals(2, store.pendingCountForSession("sess-1"));
        assertEquals(1, store.pendingCountForSession("sess-2"));
        assertEquals(3, store.totalPendingCount());
    }

    // -- DeferredAction record tests --

    @Test
    void actionIsDue() {
        var past = Instant.now().minusSeconds(10);
        var future = Instant.now().plusSeconds(3600);
        var action = new DeferredAction(
                UUID.randomUUID().toString(), "sess-1", "key",
                Instant.now(), past, future,
                "goal", 3, 0, DeferredActionState.PENDING, null, null);

        assertTrue(action.isDue(Instant.now()));

        var futureAction = new DeferredAction(
                UUID.randomUUID().toString(), "sess-1", "key2",
                Instant.now(), Instant.now().plusSeconds(60), future,
                "goal", 3, 0, DeferredActionState.PENDING, null, null);

        assertFalse(futureAction.isDue(Instant.now()));
    }

    @Test
    void actionIsExpired() {
        var past = Instant.now().minusSeconds(100);
        var expired = Instant.now().minusSeconds(10);
        var action = new DeferredAction(
                UUID.randomUUID().toString(), "sess-1", "key",
                past, past, expired,
                "goal", 3, 0, DeferredActionState.PENDING, null, null);

        assertTrue(action.isExpired(Instant.now()));
    }

    @Test
    void actionWithFailureRetry() {
        var action = createAction("sess-1", "goal");
        // maxRetries=3, attempts=0 -> after failure: attempts=1, state=PENDING (retryable)
        var failed = action.withFailure("timeout");
        assertEquals(1, failed.attempts());
        assertEquals(DeferredActionState.PENDING, failed.state());
        assertEquals("timeout", failed.lastError());
    }

    @Test
    void actionWithFailureFinal() {
        var action = new DeferredAction(
                UUID.randomUUID().toString(), "sess-1", "key",
                Instant.now(), Instant.now(), Instant.now().plusSeconds(3600),
                "goal", 2, 1, DeferredActionState.PENDING, null, null);

        // attempts=1, maxRetries=2 -> after failure: attempts=2 >= maxRetries, state=FAILED
        var failed = action.withFailure("error");
        assertEquals(2, failed.attempts());
        assertEquals(DeferredActionState.FAILED, failed.state());
    }

    @Test
    void actionWithSuccess() {
        var action = createAction("sess-1", "goal");
        var completed = action.withSuccess("all good");
        assertEquals(DeferredActionState.COMPLETED, completed.state());
        assertEquals("all good", completed.lastOutput());
        assertNull(completed.lastError());
    }

    // -- Scheduler limit enforcement tests --

    @Test
    void schedulerEnforcesPerSessionLimit() {
        var scheduler = createScheduler();

        // Schedule 3 actions for same session
        scheduler.schedule("sess-1", 60, "goal 1", 3);
        scheduler.schedule("sess-1", 60, "goal 2", 3);
        scheduler.schedule("sess-1", 60, "goal 3", 3);

        // 4th should fail
        assertThrows(IllegalArgumentException.class,
                () -> scheduler.schedule("sess-1", 60, "goal 4", 3));
    }

    @Test
    void schedulerEnforcesGlobalLimit() {
        var scheduler = createScheduler();

        // Fill up to global limit (10)
        for (int i = 0; i < DeferredActionScheduler.MAX_GLOBAL; i++) {
            String sessionId = "sess-" + (i / DeferredActionScheduler.MAX_PER_SESSION);
            scheduler.schedule(sessionId, 60, "goal " + i, 3);
        }

        // Next should fail
        assertThrows(IllegalArgumentException.class,
                () -> scheduler.schedule("sess-99", 60, "one more", 3));
    }

    @Test
    void schedulerEnforcesDelayLimits() {
        var scheduler = createScheduler();

        assertThrows(IllegalArgumentException.class,
                () -> scheduler.schedule("sess-1", 1, "too short", 3));

        assertThrows(IllegalArgumentException.class,
                () -> scheduler.schedule("sess-1", 7200, "too long", 3));
    }

    @Test
    void schedulerIdempotencyDedup() {
        var scheduler = createScheduler();

        var first = scheduler.schedule("sess-1", 60, "check build", 3);
        var second = scheduler.schedule("sess-1", 120, "check build", 3);

        // Same goal for same session -> same action
        assertEquals(first.actionId(), second.actionId());
    }

    @Test
    void schedulerCancelAction() {
        var scheduler = createScheduler();

        var action = scheduler.schedule("sess-1", 60, "check build", 3);
        assertTrue(scheduler.cancel(action.actionId(), "no longer needed"));

        var updated = store.get(action.actionId());
        assertTrue(updated.isPresent());
        assertEquals(DeferredActionState.CANCELLED, updated.get().state());

        // Cancel again should return false
        assertFalse(scheduler.cancel(action.actionId(), "again"));
    }

    // -- Tick tests --

    @Test
    void tickExpiresOldActions() {
        var scheduler = createScheduler();
        // Start scheduler so tick() guard passes, then stop it right away (we call tick() manually)
        scheduler.start();

        // Manually create an expired action
        var expired = new DeferredAction(
                UUID.randomUUID().toString(), "sess-1",
                "key:" + UUID.randomUUID(),
                Instant.now().minusSeconds(3600),
                Instant.now().minusSeconds(1800),
                Instant.now().minusSeconds(60),
                "old goal", 3, 0, DeferredActionState.PENDING, null, null);
        store.put(expired);

        scheduler.tick();
        scheduler.stop();

        var updated = store.get(expired.actionId());
        assertTrue(updated.isPresent());
        assertEquals(DeferredActionState.EXPIRED, updated.get().state());
    }

    // -- Idempotency key tests --

    @Test
    void idempotencyKeyDeterministic() {
        String key1 = DeferredActionScheduler.computeIdempotencyKey("sess-1", "check build");
        String key2 = DeferredActionScheduler.computeIdempotencyKey("sess-1", "check build");
        assertEquals(key1, key2);
    }

    @Test
    void idempotencyKeyDifferentGoals() {
        String key1 = DeferredActionScheduler.computeIdempotencyKey("sess-1", "check build");
        String key2 = DeferredActionScheduler.computeIdempotencyKey("sess-1", "check deploy");
        assertNotEquals(key1, key2);
    }

    @Test
    void idempotencyKeyDifferentSessions() {
        String key1 = DeferredActionScheduler.computeIdempotencyKey("sess-1", "check build");
        String key2 = DeferredActionScheduler.computeIdempotencyKey("sess-2", "check build");
        assertNotEquals(key1, key2);
    }

    // -- DeferCheckTool tests --

    @Test
    void deferCheckToolMissingParams() throws Exception {
        var scheduler = createScheduler();
        var tool = new DeferCheckTool(scheduler);
        tool.setCurrentSessionId("sess-1");

        // Missing goal
        var result1 = tool.execute("{\"delaySeconds\": 60}");
        assertTrue(result1.isError());

        // Missing delaySeconds
        var result2 = tool.execute("{\"goal\": \"check\"}");
        assertTrue(result2.isError());
    }

    @Test
    void deferCheckToolSuccess() throws Exception {
        var scheduler = createScheduler();
        var tool = new DeferCheckTool(scheduler);
        tool.setCurrentSessionId("sess-1");

        var result = tool.execute("{\"delaySeconds\": 60, \"goal\": \"check build status\"}");
        assertFalse(result.isError());
        assertTrue(result.output().contains("actionId:"));
        assertTrue(result.output().contains("scheduled successfully"));
    }

    @Test
    void deferCheckToolNoSession() throws Exception {
        var scheduler = createScheduler();
        var tool = new DeferCheckTool(scheduler);
        // No session ID set

        var result = tool.execute("{\"delaySeconds\": 60, \"goal\": \"check\"}");
        assertTrue(result.isError());
        assertTrue(result.output().contains("No active session"));
    }

    @Test
    void deferCheckToolNoScheduler() throws Exception {
        var tool = new DeferCheckTool(null);
        tool.setCurrentSessionId("sess-1");

        var result = tool.execute("{\"delaySeconds\": 60, \"goal\": \"check\"}");
        assertTrue(result.isError());
        assertTrue(result.output().contains("not available"));
    }

    // -- Reschedule tool registration test --

    @Test
    void rescheduleCheckToolName() {
        var tool = new RescheduleDeferredTool();
        assertEquals("reschedule_check", tool.name());
    }

    @Test
    void rescheduleDeferredToolNoPendingByDefault() {
        var tool = new RescheduleDeferredTool();
        assertNull(tool.pendingRequest());
    }

    // -- DeferEvent tests --

    @Test
    void deferEventRescheduledRecordCompiles() {
        var rescheduled = new DeferEvent.ActionRescheduled(
                "a1", "s1", "not ready", 120,
                Instant.now().plusSeconds(120), Instant.now());
        assertEquals("a1", rescheduled.actionId());
        assertEquals(120, rescheduled.delaySeconds());
        assertEquals("not ready", rescheduled.reason());
    }

    @Test
    void deferEventRecordsCompile() {
        var scheduled = new DeferEvent.ActionScheduled("a1", "s1", "goal",
                Instant.now().plusSeconds(60), Instant.now());
        assertEquals("a1", scheduled.actionId());
        assertEquals("s1", scheduled.sessionId());

        var triggered = new DeferEvent.ActionTriggered("a1", "s1", Instant.now());
        assertEquals("a1", triggered.actionId());

        var completed = new DeferEvent.ActionCompleted("a1", "s1", 500, "done", Instant.now());
        assertEquals(500, completed.durationMs());

        var failed = new DeferEvent.ActionFailed("a1", "s1", "err", 1, 3, Instant.now());
        assertEquals("err", failed.error());

        var expired = new DeferEvent.ActionExpired("a1", "s1", Instant.now());
        assertNotNull(expired.timestamp());

        var cancelled = new DeferEvent.ActionCancelled("a1", "s1", "user", Instant.now());
        assertEquals("user", cancelled.reason());
    }

    // -- Crash recovery tests --

    @Test
    void crashRecoveryLoadsPendingFromDisk() throws IOException {
        // Create some actions and save
        var a1 = createAction("sess-1", "goal 1");
        var a2 = createAction("sess-1", "goal 2").withState(DeferredActionState.COMPLETED);
        store.put(a1);
        store.put(a2);
        store.save();

        // Simulate restart: new store, load from disk
        var store2 = new DeferredActionStore(tempDir);
        store2.load();

        assertEquals(2, store2.size());
        assertEquals(1, store2.allPending().size());
        assertEquals(a1.actionId(), store2.allPending().get(0).actionId());
    }

    // -- Parallel execution tests --

    @Test
    void tickExecutesDueActionImmediately() {
        var scheduler = createScheduler();
        scheduler.start();

        // Manually create a due action
        var dueAction = new DeferredAction(
                UUID.randomUUID().toString(), "sess-1",
                "key:" + UUID.randomUUID(),
                Instant.now().minusSeconds(120),
                Instant.now().minusSeconds(10),
                Instant.now().plusSeconds(3600),
                "check something", 3, 0, DeferredActionState.PENDING, null, null);
        store.put(dueAction);

        // tick() marks action as RUNNING synchronously before spawning virtual thread
        scheduler.tick();

        // Action should already be RUNNING after tick() — no need to wait for worker
        var updated = store.get(dueAction.actionId());
        assertTrue(updated.isPresent());
        assertNotEquals(DeferredActionState.PENDING, updated.get().state(),
                "Due action should no longer be PENDING after tick");

        scheduler.stop();
    }

    @Test
    void deferredActionDoesNotModifySessionHistory() {
        var sessionManager = new SessionManager();
        var session = sessionManager.createSession(tempDir);
        String sessionId = session.id();

        // Add a message to session
        session.addMessage(new AgentSession.ConversationMessage.User("hello"));
        int messageCountBefore = session.messages().size();

        var scheduler = createSchedulerWith(sessionManager);
        scheduler.start();

        // Create a due action for this session
        var dueAction = new DeferredAction(
                UUID.randomUUID().toString(), sessionId,
                "key:" + UUID.randomUUID(),
                Instant.now().minusSeconds(120),
                Instant.now().minusSeconds(10),
                Instant.now().plusSeconds(3600),
                "check build", 3, 0, DeferredActionState.PENDING, null, null);
        store.put(dueAction);

        scheduler.tick();

        // stop() joins active worker threads, so this is a deterministic sync point
        scheduler.stop();

        // Session history should NOT have been modified by the deferred action
        assertEquals(messageCountBefore, session.messages().size(),
                "Deferred action must not modify session conversation history");
    }

    // -- Helpers --

    private DeferredAction createAction(String sessionId, String goal) {
        return new DeferredAction(
                UUID.randomUUID().toString(),
                sessionId,
                DeferredActionScheduler.computeIdempotencyKey(sessionId, goal),
                Instant.now(),
                Instant.now().plusSeconds(60),
                Instant.now().plusSeconds(3600),
                goal,
                3, 0, DeferredActionState.PENDING, null, null);
    }

    private DeferredActionScheduler createScheduler() {
        var sessionManager = new SessionManager();
        // Create sessions to satisfy scheduler's session lookups
        for (int i = 0; i < 20; i++) {
            sessionManager.createSession(tempDir);
        }
        return createSchedulerWith(sessionManager);
    }

    private DeferredActionScheduler createSchedulerWith(SessionManager sessionManager) {
        return new DeferredActionScheduler(
                store, sessionManager,
                new MockLlmClient(), new ToolRegistry(),
                "test-model", "test prompt",
                4096, 0,
                null, 5);
    }
}

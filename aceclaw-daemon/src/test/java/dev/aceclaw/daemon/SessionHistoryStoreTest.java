package dev.aceclaw.daemon;

import dev.aceclaw.memory.WorkspacePaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SessionHistoryStore} — JSONL-based session history persistence.
 */
class SessionHistoryStoreTest {

    @TempDir
    Path tempDir;

    private SessionHistoryStore store;

    @BeforeEach
    void setUp() throws IOException {
        store = new SessionHistoryStore(tempDir);
        store.init();
    }

    // =========================================================================
    // init
    // =========================================================================

    @Test
    void initCreatesHistoryDirectory() {
        assertThat(Files.isDirectory(tempDir.resolve("history"))).isTrue();
    }

    @Test
    void initIdempotent() throws IOException {
        store.init();
        store.init();
        assertThat(Files.isDirectory(tempDir.resolve("history"))).isTrue();
    }

    // =========================================================================
    // appendMessage
    // =========================================================================

    @Test
    void appendMessageCreatesFile() {
        var msg = new AgentSession.ConversationMessage.User("Hello");
        store.appendMessage("session-1", msg);

        Path file = tempDir.resolve("history/session-1.jsonl");
        assertThat(Files.isRegularFile(file)).isTrue();
    }

    @Test
    void appendMessageWritesJsonl() throws IOException {
        store.appendMessage("s1", new AgentSession.ConversationMessage.User("Hello"));
        store.appendMessage("s1", new AgentSession.ConversationMessage.Assistant("Hi there"));

        Path file = tempDir.resolve("history/s1.jsonl");
        var lines = Files.readAllLines(file);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).contains("\"role\":\"user\"");
        assertThat(lines.get(0)).contains("\"content\":\"Hello\"");
        assertThat(lines.get(1)).contains("\"role\":\"assistant\"");
    }

    @Test
    void appendMessageAllRoles() throws IOException {
        store.appendMessage("s1", new AgentSession.ConversationMessage.User("u"));
        store.appendMessage("s1", new AgentSession.ConversationMessage.Assistant("a"));
        store.appendMessage("s1", new AgentSession.ConversationMessage.System("s"));

        var lines = Files.readAllLines(tempDir.resolve("history/s1.jsonl"));
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).contains("\"role\":\"user\"");
        assertThat(lines.get(1)).contains("\"role\":\"assistant\"");
        assertThat(lines.get(2)).contains("\"role\":\"system\"");
    }

    // =========================================================================
    // saveSession + loadSession
    // =========================================================================

    @Test
    void saveAndLoadSessionRoundTrip() {
        var session = AgentSession.withId("round-trip", tempDir);
        session.addMessage(new AgentSession.ConversationMessage.User("Fix the build"));
        session.addMessage(new AgentSession.ConversationMessage.Assistant("I'll run gradle."));
        session.addMessage(new AgentSession.ConversationMessage.System("Context injected."));

        store.saveSession(session);

        var loaded = store.loadSession("round-trip");
        assertThat(loaded).hasSize(3);

        assertThat(loaded.get(0)).isInstanceOf(AgentSession.ConversationMessage.User.class);
        assertThat(((AgentSession.ConversationMessage.User) loaded.get(0)).content())
                .isEqualTo("Fix the build");

        assertThat(loaded.get(1)).isInstanceOf(AgentSession.ConversationMessage.Assistant.class);
        assertThat(((AgentSession.ConversationMessage.Assistant) loaded.get(1)).content())
                .isEqualTo("I'll run gradle.");

        assertThat(loaded.get(2)).isInstanceOf(AgentSession.ConversationMessage.System.class);
        assertThat(((AgentSession.ConversationMessage.System) loaded.get(2)).content())
                .isEqualTo("Context injected.");
    }

    @Test
    void saveSessionPersistsWorkspaceHashAndListsScopedSessions() {
        var workspaceA = tempDir.resolve("workspace-a");
        var workspaceB = tempDir.resolve("workspace-b");
        var sessionA = AgentSession.withId("session-a", workspaceA);
        var sessionB = AgentSession.withId("session-b", workspaceB);
        sessionA.addMessage(new AgentSession.ConversationMessage.User("A"));
        sessionB.addMessage(new AgentSession.ConversationMessage.User("B"));

        store.saveSession(sessionA);
        store.saveSession(sessionB);

        String workspaceHashA = WorkspacePaths.workspaceHash(workspaceA);
        String workspaceHashB = WorkspacePaths.workspaceHash(workspaceB);
        assertThat(store.listSessionsForWorkspace(workspaceHashA)).containsExactly("session-a");
        assertThat(store.listSessionsForWorkspace(workspaceHashB)).containsExactly("session-b");
    }

    @Test
    void saveSessionOverwritesPrevious() {
        var session = AgentSession.withId("overwrite", tempDir);
        session.addMessage(new AgentSession.ConversationMessage.User("First"));
        store.saveSession(session);

        // Replace messages and save again
        session.replaceMessages(List.of(
                new AgentSession.ConversationMessage.User("Replaced")));
        store.saveSession(session);

        var loaded = store.loadSession("overwrite");
        assertThat(loaded).hasSize(1);
        assertThat(((AgentSession.ConversationMessage.User) loaded.get(0)).content())
                .isEqualTo("Replaced");
    }

    @Test
    void loadSessionPreservesTimestamps() {
        var ts = Instant.parse("2025-06-15T10:30:00Z");
        var session = AgentSession.withId("ts-test", tempDir);
        session.addMessage(new AgentSession.ConversationMessage.User("Hello", ts));

        store.saveSession(session);

        var loaded = store.loadSession("ts-test");
        assertThat(loaded).hasSize(1);
        var user = (AgentSession.ConversationMessage.User) loaded.get(0);
        assertThat(user.timestamp()).isEqualTo(ts);
    }

    @Test
    void loadSessionNonExistentReturnsEmpty() {
        var loaded = store.loadSession("nonexistent-session");
        assertThat(loaded).isEmpty();
    }

    @Test
    void loadSessionHandlesMalformedJsonl() throws IOException {
        Path file = tempDir.resolve("history/malformed.jsonl");
        Files.writeString(file, "{\"role\":\"user\",\"content\":\"good\",\"timestamp\":\"2025-01-01T00:00:00Z\"}\n" +
                "NOT VALID JSON\n" +
                "{\"role\":\"assistant\",\"content\":\"also good\",\"timestamp\":\"2025-01-01T00:00:01Z\"}\n");

        var loaded = store.loadSession("malformed");
        assertThat(loaded).hasSize(2);
    }

    @Test
    void loadSessionSkipsBlankLines() throws IOException {
        Path file = tempDir.resolve("history/blanks.jsonl");
        Files.writeString(file,
                "{\"role\":\"user\",\"content\":\"hello\",\"timestamp\":\"2025-01-01T00:00:00Z\"}\n" +
                "\n" +
                "   \n" +
                "{\"role\":\"assistant\",\"content\":\"hi\",\"timestamp\":\"2025-01-01T00:00:01Z\"}\n");

        var loaded = store.loadSession("blanks");
        assertThat(loaded).hasSize(2);
    }

    // =========================================================================
    // listSessions
    // =========================================================================

    @Test
    void listSessionsEmpty() {
        var sessions = store.listSessions();
        assertThat(sessions).isEmpty();
    }

    @Test
    void listSessionsReturnsAll() {
        store.appendMessage("session-a", new AgentSession.ConversationMessage.User("a"));
        store.appendMessage("session-b", new AgentSession.ConversationMessage.User("b"));
        store.appendMessage("session-c", new AgentSession.ConversationMessage.User("c"));

        var sessions = store.listSessions();
        assertThat(sessions).hasSize(3);
        assertThat(sessions).containsExactlyInAnyOrder("session-a", "session-b", "session-c");
    }

    @Test
    void listSessionsIgnoresNonJsonlFiles() throws IOException {
        store.appendMessage("valid", new AgentSession.ConversationMessage.User("ok"));
        // Create a non-jsonl file in history dir
        Files.writeString(tempDir.resolve("history/notes.txt"), "not a session");

        var sessions = store.listSessions();
        assertThat(sessions).containsExactly("valid");
    }

    // =========================================================================
    // deleteSession
    // =========================================================================

    @Test
    void deleteSessionRemovesFile() {
        store.appendMessage("to-delete", new AgentSession.ConversationMessage.User("data"));
        assertThat(Files.isRegularFile(tempDir.resolve("history/to-delete.jsonl"))).isTrue();

        store.deleteSession("to-delete");
        assertThat(Files.isRegularFile(tempDir.resolve("history/to-delete.jsonl"))).isFalse();
    }

    @Test
    void deleteSessionNonExistentIsNoOp() {
        // Should not throw
        store.deleteSession("never-existed");
    }

    @Test
    void deleteSessionThenLoadReturnsEmpty() {
        store.appendMessage("del-load", new AgentSession.ConversationMessage.User("data"));
        store.deleteSession("del-load");

        var loaded = store.loadSession("del-load");
        assertThat(loaded).isEmpty();
    }

    // =========================================================================
    // flushAll
    // =========================================================================

    @Test
    void flushAllSavesActiveSessions() {
        var s1 = AgentSession.withId("flush-1", tempDir);
        s1.addMessage(new AgentSession.ConversationMessage.User("msg1"));

        var s2 = AgentSession.withId("flush-2", tempDir);
        s2.addMessage(new AgentSession.ConversationMessage.Assistant("msg2"));

        store.flushAll(List.of(s1, s2));

        assertThat(store.listSessions()).containsExactlyInAnyOrder("flush-1", "flush-2");

        var loaded1 = store.loadSession("flush-1");
        assertThat(loaded1).hasSize(1);
        assertThat(((AgentSession.ConversationMessage.User) loaded1.get(0)).content()).isEqualTo("msg1");

        var loaded2 = store.loadSession("flush-2");
        assertThat(loaded2).hasSize(1);
        assertThat(((AgentSession.ConversationMessage.Assistant) loaded2.get(0)).content()).isEqualTo("msg2");
    }

    @Test
    void flushAllSkipsEmptySessions() {
        var empty = AgentSession.withId("empty-session", tempDir);
        var nonEmpty = AgentSession.withId("non-empty", tempDir);
        nonEmpty.addMessage(new AgentSession.ConversationMessage.User("data"));

        store.flushAll(List.of(empty, nonEmpty));

        var sessions = store.listSessions();
        assertThat(sessions).containsExactly("non-empty");
    }

    @Test
    void flushAllWithNoSessionsIsNoOp() {
        store.flushAll(List.of());
        assertThat(store.listSessions()).isEmpty();
    }

    // =========================================================================
    // appendMessage + loadSession integration
    // =========================================================================

    @Test
    void appendThenLoadPreservesOrder() {
        store.appendMessage("order", new AgentSession.ConversationMessage.User("1st"));
        store.appendMessage("order", new AgentSession.ConversationMessage.Assistant("2nd"));
        store.appendMessage("order", new AgentSession.ConversationMessage.User("3rd"));

        var loaded = store.loadSession("order");
        assertThat(loaded).hasSize(3);
        assertThat(((AgentSession.ConversationMessage.User) loaded.get(0)).content()).isEqualTo("1st");
        assertThat(((AgentSession.ConversationMessage.Assistant) loaded.get(1)).content()).isEqualTo("2nd");
        assertThat(((AgentSession.ConversationMessage.User) loaded.get(2)).content()).isEqualTo("3rd");
    }

    @Test
    void separateSessionsDoNotInterfere() {
        store.appendMessage("s1", new AgentSession.ConversationMessage.User("session 1 data"));
        store.appendMessage("s2", new AgentSession.ConversationMessage.User("session 2 data"));

        var s1 = store.loadSession("s1");
        var s2 = store.loadSession("s2");
        assertThat(s1).hasSize(1);
        assertThat(s2).hasSize(1);
        assertThat(((AgentSession.ConversationMessage.User) s1.get(0)).content()).isEqualTo("session 1 data");
        assertThat(((AgentSession.ConversationMessage.User) s2.get(0)).content()).isEqualTo("session 2 data");
    }

    // =========================================================================
    // flushAll initializes history dir if needed
    // =========================================================================

    @Test
    void flushAllCreatesHistoryDirIfMissing() throws IOException {
        // Create a fresh store without calling init()
        var freshStore = new SessionHistoryStore(tempDir.resolve("fresh"));
        var session = AgentSession.withId("auto-init", tempDir);
        session.addMessage(new AgentSession.ConversationMessage.User("test"));

        freshStore.flushAll(List.of(session));

        assertThat(Files.isDirectory(tempDir.resolve("fresh/history"))).isTrue();
        assertThat(freshStore.loadSession("auto-init")).hasSize(1);
    }
}

package dev.aceclaw.daemon;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Represents a single agent session within the daemon.
 *
 * <p>Each session maintains its own conversation history and working directory.
 * Sessions persist across client reconnections.
 */
public final class AgentSession {

    private final String id;
    private final Path projectPath;
    private final Instant createdAt;
    private final List<ConversationMessage> messages;

    private volatile boolean active;

    private AgentSession(String id, Path projectPath) {
        this.id = id;
        this.projectPath = projectPath;
        this.createdAt = Instant.now();
        this.messages = Collections.synchronizedList(new ArrayList<>());
        this.active = true;
    }

    /**
     * Creates a new session with a generated UUID.
     */
    public static AgentSession create(Path projectPath) {
        return new AgentSession(UUID.randomUUID().toString(), projectPath);
    }

    /**
     * Creates a session with a specific ID (for resuming).
     */
    public static AgentSession withId(String id, Path projectPath) {
        return new AgentSession(id, projectPath);
    }

    public String id() { return id; }

    public Path projectPath() { return projectPath; }

    public Instant createdAt() { return createdAt; }

    public boolean isActive() { return active; }

    /**
     * Adds a message to the conversation history.
     */
    public void addMessage(ConversationMessage message) {
        messages.add(message);
    }

    /**
     * Returns an unmodifiable view of the conversation history.
     */
    public List<ConversationMessage> messages() {
        return Collections.unmodifiableList(new ArrayList<>(messages));
    }

    /**
     * Marks the session as inactive (client disconnected or session destroyed).
     */
    public void deactivate() {
        this.active = false;
    }

    /**
     * A message in the conversation history.
     */
    public sealed interface ConversationMessage {

        record User(String content, Instant timestamp) implements ConversationMessage {
            public User(String content) { this(content, Instant.now()); }
        }

        record Assistant(String content, Instant timestamp) implements ConversationMessage {
            public Assistant(String content) { this(content, Instant.now()); }
        }

        record System(String content, Instant timestamp) implements ConversationMessage {
            public System(String content) { this(content, Instant.now()); }
        }
    }
}

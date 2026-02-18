package dev.aceclaw.daemon;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Persists session conversation history to disk as JSONL (one JSON object per line).
 *
 * <p>Storage format: {@code ~/.aceclaw/history/{sessionId}.jsonl}
 *
 * <p>Each line is a JSON object with fields:
 * <ul>
 *   <li>{@code role} — "user", "assistant", or "system"</li>
 *   <li>{@code content} — message text</li>
 *   <li>{@code timestamp} — ISO-8601 instant</li>
 * </ul>
 */
public final class SessionHistoryStore {

    private static final Logger log = LoggerFactory.getLogger(SessionHistoryStore.class);

    private final Path historyDir;
    private final ObjectMapper mapper;
    /** Serializes file I/O to prevent JSONL corruption from concurrent writes. */
    private final ReentrantLock writeLock = new ReentrantLock();

    /**
     * Creates a history store under the given base directory.
     *
     * @param baseDir the aceclaw home directory (e.g. ~/.aceclaw)
     */
    public SessionHistoryStore(Path baseDir) {
        this.historyDir = baseDir.resolve("history");
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
    }

    /**
     * Ensures the history directory exists.
     */
    public void init() throws IOException {
        Files.createDirectories(historyDir);
    }

    /**
     * Appends a single message to the session's history file.
     */
    public void appendMessage(String sessionId, AgentSession.ConversationMessage message) {
        writeLock.lock();
        try {
            var entry = toEntry(message);
            String json = mapper.writeValueAsString(entry);
            Path file = historyDir.resolve(sessionId + ".jsonl");
            Files.writeString(file, json + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Failed to persist message for session {}: {}", sessionId, e.getMessage());
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Saves all messages from a session to disk (full flush).
     */
    public void saveSession(AgentSession session) {
        writeLock.lock();
        try {
            Path file = historyDir.resolve(session.id() + ".jsonl");
            var lines = new StringBuilder();
            for (var msg : session.messages()) {
                lines.append(mapper.writeValueAsString(toEntry(msg))).append("\n");
            }
            Files.writeString(file, lines.toString(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.debug("Saved session history: id={}, messages={}", session.id(), session.messages().size());
        } catch (IOException e) {
            log.warn("Failed to save session {}: {}", session.id(), e.getMessage());
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Loads conversation messages from a session history file.
     *
     * @return the loaded messages, or empty list if no history exists
     */
    public List<AgentSession.ConversationMessage> loadSession(String sessionId) {
        Path file = historyDir.resolve(sessionId + ".jsonl");
        if (!Files.isRegularFile(file)) {
            return List.of();
        }

        var messages = new ArrayList<AgentSession.ConversationMessage>();
        try {
            var lines = Files.readAllLines(file);
            for (String line : lines) {
                if (line.isBlank()) continue;
                var entry = mapper.readValue(line, HistoryEntry.class);
                messages.add(fromEntry(entry));
            }
            log.debug("Loaded session history: id={}, messages={}", sessionId, messages.size());
        } catch (IOException e) {
            log.warn("Failed to load session {}: {}", sessionId, e.getMessage());
        }
        return messages;
    }

    /**
     * Lists all session IDs that have persisted history.
     */
    public List<String> listSessions() {
        var sessionIds = new ArrayList<String>();
        try (var stream = Files.list(historyDir)) {
            stream.filter(p -> p.toString().endsWith(".jsonl"))
                    .forEach(p -> {
                        String name = p.getFileName().toString();
                        sessionIds.add(name.substring(0, name.length() - ".jsonl".length()));
                    });
        } catch (IOException e) {
            log.debug("No history directory or error listing: {}", e.getMessage());
        }
        return sessionIds;
    }

    /**
     * Deletes the history file for a session.
     */
    public void deleteSession(String sessionId) {
        try {
            Path file = historyDir.resolve(sessionId + ".jsonl");
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Failed to delete history for session {}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * Saves all active sessions to disk. Called during daemon shutdown.
     */
    public void flushAll(Iterable<AgentSession> sessions) {
        try {
            init();
            int count = 0;
            for (var session : sessions) {
                if (!session.messages().isEmpty()) {
                    saveSession(session);
                    count++;
                }
            }
            log.info("Flushed {} session histories to disk", count);
        } catch (IOException e) {
            log.error("Failed to initialize history directory for flush: {}", e.getMessage());
        }
    }

    // -- internal --------------------------------------------------------

    private static HistoryEntry toEntry(AgentSession.ConversationMessage msg) {
        return switch (msg) {
            case AgentSession.ConversationMessage.User u ->
                    new HistoryEntry("user", u.content(), u.timestamp());
            case AgentSession.ConversationMessage.Assistant a ->
                    new HistoryEntry("assistant", a.content(), a.timestamp());
            case AgentSession.ConversationMessage.System s ->
                    new HistoryEntry("system", s.content(), s.timestamp());
        };
    }

    private static AgentSession.ConversationMessage fromEntry(HistoryEntry entry) {
        return switch (entry.role) {
            case "user" -> new AgentSession.ConversationMessage.User(entry.content, entry.timestamp);
            case "assistant" -> new AgentSession.ConversationMessage.Assistant(entry.content, entry.timestamp);
            case "system" -> new AgentSession.ConversationMessage.System(entry.content, entry.timestamp);
            default -> new AgentSession.ConversationMessage.System(entry.content, entry.timestamp);
        };
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record HistoryEntry(String role, String content, Instant timestamp) {}
}

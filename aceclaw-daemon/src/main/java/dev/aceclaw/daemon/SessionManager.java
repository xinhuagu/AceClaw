package dev.aceclaw.daemon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Manages active agent sessions within the daemon.
 *
 * <p>Thread-safe: sessions may be created, accessed, and destroyed from any virtual thread.
 */
public final class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private final ConcurrentHashMap<String, AgentSession> sessions = new ConcurrentHashMap<>();
    private volatile Consumer<AgentSession> sessionEndCallback;

    /**
     * Creates a new session for the given project directory.
     *
     * @param projectPath working directory for this session
     * @return the newly created session
     */
    public AgentSession createSession(Path projectPath) {
        var session = AgentSession.create(projectPath);
        sessions.put(session.id(), session);
        log.info("Session created: id={}, project={}", session.id(), projectPath);
        return session;
    }

    /**
     * Retrieves an active session by ID.
     *
     * @return the session, or null if not found
     */
    public AgentSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * Sets a callback invoked before a session is deactivated.
     * Used for session-end memory extraction.
     *
     * @param callback receives the session before deactivation
     */
    public void setSessionEndCallback(Consumer<AgentSession> callback) {
        this.sessionEndCallback = callback;
    }

    /**
     * Destroys a session, deactivating it and removing from the active map.
     * If a session-end callback is set, it is invoked before deactivation.
     *
     * @return true if the session existed and was destroyed
     */
    public boolean destroySession(String sessionId) {
        var session = sessions.remove(sessionId);
        if (session != null) {
            if (sessionEndCallback != null) {
                try {
                    sessionEndCallback.accept(session);
                } catch (Exception e) {
                    log.warn("Session-end callback failed for {}: {}", sessionId, e.getMessage());
                }
            }
            session.deactivate();
            log.info("Session destroyed: id={}", sessionId);
            return true;
        }
        return false;
    }

    /**
     * Returns all active sessions (unmodifiable view).
     */
    public Collection<AgentSession> activeSessions() {
        return Collections.unmodifiableCollection(sessions.values());
    }

    /**
     * Returns the number of active sessions.
     */
    public int sessionCount() {
        return sessions.size();
    }

    /**
     * Destroys all sessions. Called during daemon shutdown.
     */
    public void destroyAll() {
        sessions.values().forEach(AgentSession::deactivate);
        int count = sessions.size();
        sessions.clear();
        log.info("All sessions destroyed: count={}", count);
    }
}

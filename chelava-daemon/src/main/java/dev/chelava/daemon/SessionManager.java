package dev.chelava.daemon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages active agent sessions within the daemon.
 *
 * <p>Thread-safe: sessions may be created, accessed, and destroyed from any virtual thread.
 */
public final class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private final ConcurrentHashMap<String, AgentSession> sessions = new ConcurrentHashMap<>();

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
     * Destroys a session, deactivating it and removing from the active map.
     *
     * @return true if the session existed and was destroyed
     */
    public boolean destroySession(String sessionId) {
        var session = sessions.remove(sessionId);
        if (session != null) {
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

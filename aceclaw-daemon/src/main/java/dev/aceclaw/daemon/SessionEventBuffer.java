package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-session ring buffer of broadcast envelopes for the snapshot endpoint
 * (issue #432). When a browser tab joins mid-execution (first paint, refresh,
 * tab switch in the sidebar), it sends {@code snapshot.request} and we ship
 * back every envelope we still hold for that session. The dashboard reducer
 * replays them to reconstruct the tree, then deduplicates the live stream
 * via the {@code lastEventId} watermark already wired in #435.
 *
 * <p>Why a buffer of envelopes and not a derived state object: the dashboard's
 * reducer is the source of truth for "events → tree". Reproducing that logic
 * in Java would double the surface area; shipping the events back lets the
 * existing reducer build state once and only once. The cost is buffer size:
 * Tier 1 sessions are interactive (minutes, hundreds of events ~ tens of KB
 * per session) so the cap below keeps daemon memory bounded even under
 * pathological event volume without paying the maintenance cost of a
 * server-side reducer.
 *
 * <p>Capacity is per-session to keep one runaway session from evicting other
 * sessions' history. When a session crosses the cap we drop the oldest
 * envelope — losing pre-history is a smaller harm than refusing to record
 * recent events. A single WARN log per session marks the first overflow so
 * the operator can correlate dashboard "missing root" reports with bursty
 * sessions.
 *
 * <p>Thread safety: an outer {@link ConcurrentHashMap} keys by sessionId; per
 * session we hold a synchronized {@link ArrayDeque}. Broadcasts come from
 * Jetty's WS thread pool; {@code snapshot.request} also reads from a Jetty
 * thread; the cross-thread race is "append while iterating" which the
 * snapshot path avoids by taking a snapshot copy under the deque's monitor.
 */
public final class SessionEventBuffer {

    private static final Logger log = LoggerFactory.getLogger(SessionEventBuffer.class);

    /** Default per-session cap. 5000 envelopes ~ 1-5MB JSON per session. */
    public static final int DEFAULT_PER_SESSION_CAPACITY = 5000;

    private final int capacity;
    private final ConcurrentHashMap<String, SessionLog> bySession = new ConcurrentHashMap<>();

    public SessionEventBuffer() {
        this(DEFAULT_PER_SESSION_CAPACITY);
    }

    public SessionEventBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0; got " + capacity);
        }
        this.capacity = capacity;
    }

    /**
     * Records {@code envelope} as the most recent event for {@code sessionId}.
     * Evicts the oldest envelope if the per-session cap is reached.
     */
    public void append(String sessionId, ObjectNode envelope) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(envelope, "envelope");
        var slot = bySession.computeIfAbsent(sessionId, k -> new SessionLog(sessionId, capacity));
        slot.append(envelope);
    }

    /**
     * Returns a snapshot of every envelope currently held for {@code sessionId},
     * in insertion order (oldest → newest). Empty list when the session is
     * unknown or has no recorded events. The list is a defensive copy so the
     * caller can iterate without holding the deque's monitor.
     */
    public List<ObjectNode> snapshot(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        var slot = bySession.get(sessionId);
        if (slot == null) {
            return List.of();
        }
        return slot.snapshot();
    }

    /**
     * Drops every envelope held for {@code sessionId}. Called when a session
     * leaves SessionManager so the buffer can't outlive the session's
     * lifetime in the daemon. No-op if the session is unknown.
     */
    public void clear(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        bySession.remove(sessionId);
    }

    /** Number of sessions currently tracked. Test helper. */
    int sessionCount() {
        return bySession.size();
    }

    private static final class SessionLog {
        private final String sessionId;
        private final int capacity;
        private final Deque<ObjectNode> envelopes;
        // First overflow per session is logged at WARN; subsequent evictions
        // stay silent so a runaway session doesn't drown the log.
        private boolean overflowLogged;

        SessionLog(String sessionId, int capacity) {
            this.sessionId = sessionId;
            this.capacity = capacity;
            this.envelopes = new ArrayDeque<>(Math.min(capacity, 256));
        }

        synchronized void append(ObjectNode envelope) {
            if (envelopes.size() >= capacity) {
                envelopes.pollFirst();
                if (!overflowLogged) {
                    log.warn("SessionEventBuffer overflow for session {} (cap={}); "
                                    + "evicting oldest envelopes. Snapshot will be partial.",
                            sessionId, capacity);
                    overflowLogged = true;
                }
            }
            envelopes.addLast(envelope);
        }

        synchronized List<ObjectNode> snapshot() {
            return new ArrayList<>(envelopes);
        }
    }
}

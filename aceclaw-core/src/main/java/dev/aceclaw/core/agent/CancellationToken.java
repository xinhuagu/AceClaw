package dev.aceclaw.core.agent;

import dev.aceclaw.core.llm.StreamSession;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-safe cancellation signal for aborting an agent turn.
 *
 * <p>The token is shared between the agent loop (which checks it at well-defined
 * checkpoints) and the cancel monitor thread (which sets it when the user
 * presses Ctrl+C). Optionally propagates cancellation to the active
 * {@link StreamSession} so an in-flight SSE stream is interrupted immediately.
 */
public final class CancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile StreamSession activeSession;

    /**
     * Requests cancellation. Idempotent: subsequent calls are no-ops.
     * If an active {@link StreamSession} is set, cancels it immediately.
     */
    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            var session = activeSession;
            if (session != null) {
                session.cancel();
            }
        }
    }

    /**
     * Returns whether cancellation has been requested.
     */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * Sets the active stream session. The agent loop calls this before
     * starting each LLM call so that {@link #cancel()} can propagate
     * to the SSE stream.
     *
     * <p>If the token is already cancelled when this is called, the
     * session is cancelled immediately (closes the race window where
     * cancel arrives between session creation and setActiveSession).
     *
     * @param session the stream session, or null to clear
     */
    public void setActiveSession(StreamSession session) {
        this.activeSession = session;
        if (session != null && cancelled.get()) {
            session.cancel();
        }
    }
}

package dev.aceclaw.core.llm;

/**
 * A handle to an active streaming response from the LLM.
 *
 * <p>Register an event handler, then start receiving events.
 * The session can be cancelled if no longer needed.
 */
public interface StreamSession {

    /**
     * Registers the event handler and begins dispatching stream events to it.
     * This method blocks until the stream completes, errors, or is cancelled.
     *
     * @param handler the callback handler for stream events
     */
    void onEvent(StreamEventHandler handler);

    /**
     * Cancels the stream if it is still in progress.
     */
    void cancel();
}

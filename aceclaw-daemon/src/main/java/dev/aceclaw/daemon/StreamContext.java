package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;

/**
 * Provides bidirectional streaming I/O during a long-running JSON-RPC request.
 *
 * <p>The daemon uses this to send intermediate notifications (streaming tokens,
 * permission requests) and read client responses (permission decisions) while
 * processing a single request such as {@code agent.prompt}.
 */
public interface StreamContext {

    /**
     * Sends a JSON-RPC notification to the client.
     *
     * @param method the notification method name (e.g. "stream.text")
     * @param params the notification parameters
     * @throws IOException if the write fails
     */
    void sendNotification(String method, Object params) throws IOException;

    /**
     * Reads the next JSON message from the client (blocking).
     *
     * <p>Used to receive responses such as {@code permission.response}
     * while the agent loop is waiting for user input.
     *
     * @return the parsed JSON message, or null if the connection was closed
     * @throws IOException if the read fails
     */
    JsonNode readMessage() throws IOException;

    /**
     * Reads the next JSON message from the client with a timeout budget in milliseconds.
     *
     * <p>Default implementation falls back to {@link #readMessage()} for contexts
     * that don't provide timeout-aware reads.
     *
     * @param timeoutMs maximum wait time in milliseconds
     * @return the parsed JSON message, or null if the connection was closed
     * @throws IOException if the read fails
     */
    default JsonNode readMessage(long timeoutMs) throws IOException {
        return readMessage();
    }
}

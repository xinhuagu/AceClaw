package dev.aceclaw.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Client that connects to the AceClaw daemon via Unix Domain Socket.
 *
 * <p>Maintains a control connection for session management and health checks,
 * and can open additional task connections for concurrent agent prompts.
 * All connections share a single request ID counter for global uniqueness.
 *
 * <p>Thread-safe: each {@link DaemonConnection} guards its own writes,
 * and the shared {@link AtomicLong} counter is inherently atomic.
 */
public final class DaemonClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DaemonClient.class);

    private static final Path DEFAULT_SOCKET = Path.of(
            System.getProperty("user.home"), ".aceclaw", "aceclaw.sock");

    private final Path socketPath;
    private final ObjectMapper objectMapper;
    private final AtomicLong requestIdCounter = new AtomicLong(1);

    /** The primary control connection used for session management and slash commands. */
    private volatile DaemonConnection controlConnection;

    /**
     * Creates a client targeting the default daemon socket at {@code ~/.aceclaw/aceclaw.sock}.
     */
    public DaemonClient() {
        this(DEFAULT_SOCKET);
    }

    /**
     * Creates a client targeting a specific socket path.
     *
     * @param socketPath path to the daemon Unix domain socket
     */
    public DaemonClient(Path socketPath) {
        this.socketPath = socketPath;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Connects the control connection to the daemon socket.
     *
     * @throws IOException if the connection cannot be established
     */
    public void connect() throws IOException {
        if (controlConnection != null && controlConnection.isOpen()) {
            log.debug("Already connected to daemon");
            return;
        }
        controlConnection = DaemonConnection.connect(socketPath, objectMapper, requestIdCounter);
        log.debug("Control connection established to {}", socketPath);
    }

    /**
     * Disconnects the control connection from the daemon socket.
     */
    public void disconnect() {
        var conn = controlConnection;
        if (conn != null) {
            conn.close();
            controlConnection = null;
            log.debug("Disconnected from daemon");
        }
    }

    /**
     * Opens a new independent connection to the daemon for a task.
     *
     * <p>Each task connection has its own line buffer and write lock,
     * enabling concurrent streaming without interference. Shares the
     * same request ID counter as the control connection.
     *
     * @return a new DaemonConnection for task-specific I/O
     * @throws IOException if the connection cannot be established
     */
    public DaemonConnection openTaskConnection() throws IOException {
        return DaemonConnection.connect(socketPath, objectMapper, requestIdCounter);
    }

    // -- Delegated methods (backward-compatible with existing callers) ----

    /**
     * Sends a JSON-RPC request on the control connection and waits for the response.
     *
     * @param method the JSON-RPC method name
     * @param params the method parameters (may be null)
     * @return the parsed JSON response node
     * @throws IOException          if I/O fails
     * @throws DaemonClientException if the response contains a JSON-RPC error
     */
    public JsonNode sendRequest(String method, JsonNode params) throws IOException, DaemonClientException {
        return controlConn().sendRequest(method, params);
    }

    /**
     * Sends a JSON-RPC notification on the control connection (no response expected).
     *
     * @param method the JSON-RPC method name
     * @param params the method parameters (may be null)
     * @throws IOException if I/O fails
     */
    public void sendNotification(String method, JsonNode params) throws IOException {
        controlConn().sendNotification(method, params);
    }

    /**
     * Reads the next line from the control connection (blocking).
     *
     * @return the next line, or null if the connection was closed
     * @throws IOException if I/O fails
     */
    public String readLine() throws IOException {
        return controlConn().readLine();
    }

    /**
     * Reads the next line from the control connection with a timeout.
     *
     * @param timeoutMs maximum time to wait in milliseconds
     * @return the next line, or null if timed out or connection closed
     * @throws IOException if I/O fails
     */
    public String readLine(long timeoutMs) throws IOException {
        return controlConn().readLine(timeoutMs);
    }

    /**
     * Writes a raw line to the control connection (guarded by write lock).
     *
     * @param line the line to write (without trailing newline)
     * @throws IOException if I/O fails
     */
    public void writeLine(String line) throws IOException {
        controlConn().writeLine(line);
    }

    /**
     * Returns the next request ID from the shared counter.
     *
     * @return the next unique request ID
     */
    public long nextRequestId() {
        return requestIdCounter.getAndIncrement();
    }

    /**
     * Returns whether the control connection is active.
     */
    public boolean isConnected() {
        var conn = controlConnection;
        return conn != null && conn.isOpen();
    }

    /**
     * Returns the shared ObjectMapper (for building params).
     */
    public ObjectMapper objectMapper() {
        return objectMapper;
    }

    @Override
    public void close() {
        disconnect();
    }

    // -- internal --------------------------------------------------------

    private DaemonConnection controlConn() throws IOException {
        var conn = controlConnection;
        if (conn == null || !conn.isOpen()) {
            throw new IOException("Not connected to daemon; call connect() first");
        }
        return conn;
    }

    /**
     * Exception thrown when a daemon RPC call fails.
     */
    public static final class DaemonClientException extends Exception {

        private final int code;

        public DaemonClientException(String message) {
            super(message);
            this.code = -1;
        }

        public DaemonClientException(int code, String message) {
            super(message);
            this.code = code;
        }

        /**
         * Returns the JSON-RPC error code, or -1 if not applicable.
         */
        public int code() {
            return code;
        }
    }
}

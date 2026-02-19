package dev.aceclaw.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Client that connects to the AceClaw daemon via Unix Domain Socket.
 *
 * <p>Sends JSON-RPC 2.0 requests as newline-delimited JSON and reads responses
 * in the same format. Thread-safe: all I/O is guarded by a lock so the client
 * can be used from virtual threads.
 */
public final class DaemonClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DaemonClient.class);

    private static final Path DEFAULT_SOCKET = Path.of(
            System.getProperty("user.home"), ".aceclaw", "aceclaw.sock");
    private static final int BUFFER_SIZE = 65536;

    private final Path socketPath;
    private final ObjectMapper objectMapper;
    private final AtomicLong requestIdCounter = new AtomicLong(1);
    private final ReentrantLock ioLock = new ReentrantLock();

    private volatile SocketChannel channel;

    /**
     * Persistent line buffer that preserves data across readLine() calls.
     * This is important for streaming: the daemon may send multiple
     * newline-delimited JSON messages in a single socket read.
     */
    private final StringBuilder lineBuffer = new StringBuilder();

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
        this.objectMapper = createObjectMapper();
    }

    /**
     * Connects to the daemon socket.
     *
     * @throws IOException if the connection cannot be established
     */
    public void connect() throws IOException {
        ioLock.lock();
        try {
            if (channel != null && channel.isOpen()) {
                log.debug("Already connected to daemon");
                return;
            }
            var address = UnixDomainSocketAddress.of(socketPath);
            channel = SocketChannel.open(StandardProtocolFamily.UNIX);
            channel.connect(address);
            log.debug("Connected to daemon at {}", socketPath);
        } finally {
            ioLock.unlock();
        }
    }

    /**
     * Disconnects from the daemon socket.
     */
    public void disconnect() {
        ioLock.lock();
        try {
            if (channel != null && channel.isOpen()) {
                try {
                    channel.close();
                } catch (IOException e) {
                    log.warn("Error closing daemon connection", e);
                }
            }
            channel = null;
            log.debug("Disconnected from daemon");
        } finally {
            ioLock.unlock();
        }
    }

    /**
     * Sends a JSON-RPC request and waits for the response.
     *
     * @param method the JSON-RPC method name
     * @param params the method parameters (may be null)
     * @return the parsed JSON response node
     * @throws IOException          if I/O fails
     * @throws DaemonClientException if the response contains a JSON-RPC error
     */
    public JsonNode sendRequest(String method, JsonNode params) throws IOException, DaemonClientException {
        ioLock.lock();
        try {
            ensureConnected();

            long id = requestIdCounter.getAndIncrement();
            ObjectNode request = objectMapper.createObjectNode();
            request.put("jsonrpc", "2.0");
            request.put("method", method);
            if (params != null) {
                request.set("params", params);
            }
            request.put("id", id);

            // Write request as newline-delimited JSON
            String json = objectMapper.writeValueAsString(request) + "\n";
            channel.write(ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)));
            log.debug("Sent request: method={}, id={}", method, id);

            // Read response
            String responseLine = readLine();
            if (responseLine == null) {
                throw new DaemonClientException("Daemon closed connection before responding");
            }

            JsonNode responseNode = objectMapper.readTree(responseLine);

            // Check for error
            if (responseNode.has("error") && !responseNode.get("error").isNull()) {
                JsonNode error = responseNode.get("error");
                int code = error.get("code").asInt();
                String message = error.get("message").asText();
                throw new DaemonClientException(code, message);
            }

            return responseNode.get("result");
        } finally {
            ioLock.unlock();
        }
    }

    /**
     * Sends a JSON-RPC notification (no response expected).
     *
     * @param method the JSON-RPC method name
     * @param params the method parameters (may be null)
     * @throws IOException if I/O fails
     */
    public void sendNotification(String method, JsonNode params) throws IOException {
        ioLock.lock();
        try {
            ensureConnected();

            ObjectNode notification = objectMapper.createObjectNode();
            notification.put("jsonrpc", "2.0");
            notification.put("method", method);
            if (params != null) {
                notification.set("params", params);
            }
            // No id field for notifications

            String json = objectMapper.writeValueAsString(notification) + "\n";
            channel.write(ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)));
            log.debug("Sent notification: method={}", method);
        } finally {
            ioLock.unlock();
        }
    }

    /**
     * Reads the next line from the daemon connection (blocking).
     *
     * <p>Uses a persistent buffer so that data arriving after a newline
     * is preserved for subsequent calls. This is essential for streaming
     * where multiple JSON messages may arrive in a single socket read.
     *
     * @return the next line, or null if the connection was closed
     * @throws IOException if I/O fails
     */
    public String readLine() throws IOException {
        // Check if a complete line is already in the buffer
        int newlineIdx = lineBuffer.indexOf("\n");
        if (newlineIdx != -1) {
            var line = lineBuffer.substring(0, newlineIdx).trim();
            lineBuffer.delete(0, newlineIdx + 1);
            return line.isEmpty() ? readLine() : line;
        }

        var buffer = ByteBuffer.allocate(BUFFER_SIZE);

        while (true) {
            buffer.clear();
            int bytesRead = channel.read(buffer);
            if (bytesRead == -1) {
                // Connection closed; return any partial content or null
                return lineBuffer.isEmpty() ? null : lineBuffer.toString().trim();
            }
            if (bytesRead == 0) continue;

            buffer.flip();
            String chunk = StandardCharsets.UTF_8.decode(buffer).toString();
            lineBuffer.append(chunk);

            newlineIdx = lineBuffer.indexOf("\n");
            if (newlineIdx != -1) {
                var line = lineBuffer.substring(0, newlineIdx).trim();
                lineBuffer.delete(0, newlineIdx + 1);
                if (!line.isEmpty()) {
                    return line;
                }
            }
        }
    }

    /**
     * Reads the next newline-delimited line with a timeout.
     *
     * @param timeoutMs maximum time to wait in milliseconds
     * @return the next line, or null if timed out or connection closed
     * @throws IOException if I/O fails
     */
    public String readLine(long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        // Check buffer first
        int newlineIdx = lineBuffer.indexOf("\n");
        if (newlineIdx != -1) {
            var line = lineBuffer.substring(0, newlineIdx).trim();
            lineBuffer.delete(0, newlineIdx + 1);
            return line.isEmpty() ? readLine(Math.max(0, deadline - System.currentTimeMillis())) : line;
        }

        channel.configureBlocking(false);
        try {
            var buffer = java.nio.ByteBuffer.allocate(BUFFER_SIZE);
            while (System.currentTimeMillis() < deadline) {
                buffer.clear();
                int bytesRead = channel.read(buffer);
                if (bytesRead == -1) {
                    return lineBuffer.isEmpty() ? null : lineBuffer.toString().trim();
                }
                if (bytesRead == 0) {
                    try { Thread.sleep(50); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    continue;
                }
                buffer.flip();
                lineBuffer.append(java.nio.charset.StandardCharsets.UTF_8.decode(buffer));
                newlineIdx = lineBuffer.indexOf("\n");
                if (newlineIdx != -1) {
                    var line = lineBuffer.substring(0, newlineIdx).trim();
                    lineBuffer.delete(0, newlineIdx + 1);
                    return line.isEmpty() ? readLine(Math.max(0, deadline - System.currentTimeMillis())) : line;
                }
            }
            return null; // timed out
        } finally {
            channel.configureBlocking(true);
        }
    }

    /**
     * Writes a raw line to the daemon socket (appending newline).
     *
     * <p>Used by the streaming REPL to send requests without holding the I/O lock
     * for the entire request-response cycle.
     *
     * @param line the line to write (without trailing newline)
     * @throws IOException if I/O fails
     */
    public void writeLine(String line) throws IOException {
        ensureConnected();
        var data = (line + "\n").getBytes(StandardCharsets.UTF_8);
        channel.write(ByteBuffer.wrap(data));
    }

    /**
     * Returns the next request ID. Used by the REPL to construct its own request JSON.
     *
     * @return the next unique request ID
     */
    public long nextRequestId() {
        return requestIdCounter.getAndIncrement();
    }

    /**
     * Returns whether the client is currently connected to the daemon.
     */
    public boolean isConnected() {
        var ch = channel;
        return ch != null && ch.isOpen() && ch.isConnected();
    }

    /**
     * Returns the underlying ObjectMapper (for building params).
     */
    public ObjectMapper objectMapper() {
        return objectMapper;
    }

    @Override
    public void close() {
        disconnect();
    }

    // -- internal --------------------------------------------------------

    private void ensureConnected() throws IOException {
        if (!isConnected()) {
            throw new IOException("Not connected to daemon; call connect() first");
        }
    }

    private static ObjectMapper createObjectMapper() {
        return new ObjectMapper();
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

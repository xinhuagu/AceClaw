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
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Wraps a single Unix Domain Socket connection with proper write synchronization.
 *
 * <p>Each connection has its own line buffer for reading and a write lock
 * to prevent concurrent writes from corrupting JSON on the wire.
 * Multiple connections can share a single {@link AtomicLong} request ID counter
 * to ensure globally unique request IDs.
 */
public final class DaemonConnection implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DaemonConnection.class);

    private static final int BUFFER_SIZE = 65536;

    private final SocketChannel channel;
    private final ObjectMapper objectMapper;
    private final AtomicLong requestIdCounter;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final StringBuilder lineBuffer = new StringBuilder();

    private DaemonConnection(SocketChannel channel, ObjectMapper objectMapper,
                             AtomicLong requestIdCounter) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.requestIdCounter = Objects.requireNonNull(requestIdCounter, "requestIdCounter");
    }

    /**
     * Opens a new connection to the daemon socket.
     *
     * @param socketPath       path to the daemon Unix domain socket
     * @param objectMapper     shared Jackson mapper
     * @param requestIdCounter shared request ID counter for global uniqueness
     * @return a connected DaemonConnection
     * @throws IOException if the connection cannot be established
     */
    public static DaemonConnection connect(Path socketPath, ObjectMapper objectMapper,
                                           AtomicLong requestIdCounter) throws IOException {
        Objects.requireNonNull(socketPath, "socketPath");
        var address = UnixDomainSocketAddress.of(socketPath);
        var ch = SocketChannel.open(StandardProtocolFamily.UNIX);
        ch.connect(address);
        log.debug("Opened connection to daemon at {}", socketPath);
        return new DaemonConnection(ch, objectMapper, requestIdCounter);
    }

    /**
     * Sends a JSON-RPC request and waits for the response.
     *
     * @param method the JSON-RPC method name
     * @param params the method parameters (may be null)
     * @return the parsed JSON response node
     * @throws IOException                          if I/O fails
     * @throws DaemonClient.DaemonClientException   if the response contains a JSON-RPC error
     */
    public JsonNode sendRequest(String method, JsonNode params)
            throws IOException, DaemonClient.DaemonClientException {
        writeLock.lock();
        try {
            long id = requestIdCounter.getAndIncrement();
            ObjectNode request = objectMapper.createObjectNode();
            request.put("jsonrpc", "2.0");
            request.put("method", method);
            if (params != null) {
                request.set("params", params);
            }
            request.put("id", id);

            writeRaw(objectMapper.writeValueAsString(request));
            log.debug("Sent request: method={}, id={}", method, id);
        } finally {
            writeLock.unlock();
        }

        // Read response (not under write lock — allows concurrent reads on other connections)
        String responseLine = readLine();
        if (responseLine == null) {
            throw new DaemonClient.DaemonClientException("Daemon closed connection before responding");
        }

        JsonNode responseNode = objectMapper.readTree(responseLine);
        if (responseNode.has("error") && !responseNode.get("error").isNull()) {
            JsonNode error = responseNode.get("error");
            int code = error.get("code").asInt();
            String message = error.get("message").asText();
            throw new DaemonClient.DaemonClientException(code, message);
        }
        return responseNode.get("result");
    }

    /**
     * Sends a JSON-RPC notification (no response expected).
     *
     * @param method the JSON-RPC method name
     * @param params the method parameters (may be null)
     * @throws IOException if I/O fails
     */
    public void sendNotification(String method, JsonNode params) throws IOException {
        writeLock.lock();
        try {
            ObjectNode notification = objectMapper.createObjectNode();
            notification.put("jsonrpc", "2.0");
            notification.put("method", method);
            if (params != null) {
                notification.set("params", params);
            }
            writeRaw(objectMapper.writeValueAsString(notification));
            log.debug("Sent notification: method={}", method);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Writes a raw JSON line to the socket (guarded by write lock).
     *
     * @param line the line to write (without trailing newline)
     * @throws IOException if I/O fails
     */
    public void writeLine(String line) throws IOException {
        writeLock.lock();
        try {
            writeRaw(line);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Returns the next unique request ID from the shared counter.
     */
    public long nextRequestId() {
        return requestIdCounter.getAndIncrement();
    }

    /**
     * Reads the next newline-delimited line (blocking).
     *
     * <p>Uses a persistent buffer so that data arriving after a newline
     * is preserved for subsequent calls.
     *
     * @return the next line, or null if the connection was closed
     * @throws IOException if I/O fails
     */
    public String readLine() throws IOException {
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
     * Reads the next line with a timeout.
     *
     * @param timeoutMs maximum time to wait in milliseconds
     * @return the next line, or null if timed out or connection closed
     * @throws IOException if I/O fails
     */
    public String readLine(long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;

        int newlineIdx = lineBuffer.indexOf("\n");
        if (newlineIdx != -1) {
            var line = lineBuffer.substring(0, newlineIdx).trim();
            lineBuffer.delete(0, newlineIdx + 1);
            return line.isEmpty()
                    ? readLine(Math.max(0, deadline - System.currentTimeMillis()))
                    : line;
        }

        channel.configureBlocking(false);
        try {
            var buffer = ByteBuffer.allocate(BUFFER_SIZE);
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
                lineBuffer.append(StandardCharsets.UTF_8.decode(buffer));
                newlineIdx = lineBuffer.indexOf("\n");
                if (newlineIdx != -1) {
                    var line = lineBuffer.substring(0, newlineIdx).trim();
                    lineBuffer.delete(0, newlineIdx + 1);
                    return line.isEmpty()
                            ? readLine(Math.max(0, deadline - System.currentTimeMillis()))
                            : line;
                }
            }
            return null;
        } finally {
            channel.configureBlocking(true);
        }
    }

    /**
     * Returns whether this connection is open.
     */
    public boolean isOpen() {
        return channel.isOpen() && channel.isConnected();
    }

    /**
     * Returns the shared ObjectMapper.
     */
    public ObjectMapper objectMapper() {
        return objectMapper;
    }

    @Override
    public void close() {
        try {
            if (channel.isOpen()) {
                channel.close();
            }
        } catch (IOException e) {
            log.warn("Error closing daemon connection", e);
        }
    }

    // -- internal --------------------------------------------------------

    /**
     * Writes a line to the socket without locking (caller must hold writeLock).
     */
    private void writeRaw(String line) throws IOException {
        var data = (line + "\n").getBytes(StandardCharsets.UTF_8);
        channel.write(ByteBuffer.wrap(data));
    }
}

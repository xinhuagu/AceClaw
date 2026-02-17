package dev.aceclaw.daemon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

/**
 * Bridges a UDS connection to the JSON-RPC request router.
 *
 * <p>Reads newline-delimited JSON from the socket, parses as JSON-RPC,
 * routes to the handler, and writes the response back.
 *
 * <p>For streaming methods (e.g. {@code agent.prompt}), creates a
 * {@link StreamContext} that allows the handler to send intermediate
 * notifications and read client messages during processing.
 */
public final class ConnectionBridge implements UdsListener.ConnectionHandler {

    private static final Logger log = LoggerFactory.getLogger(ConnectionBridge.class);
    private static final int BUFFER_SIZE = 65536;

    private final RequestRouter router;
    private final ObjectMapper objectMapper;

    public ConnectionBridge(RequestRouter router, ObjectMapper objectMapper) {
        this.router = router;
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(SocketChannel channel) {
        var buffer = ByteBuffer.allocate(BUFFER_SIZE);
        var lineBuilder = new StringBuilder();

        try {
            while (channel.isOpen()) {
                buffer.clear();
                int bytesRead = channel.read(buffer);
                if (bytesRead == -1) break; // Client disconnected
                if (bytesRead == 0) continue;

                buffer.flip();
                var chunk = StandardCharsets.UTF_8.decode(buffer).toString();
                lineBuilder.append(chunk);

                // Process complete lines (newline-delimited JSON)
                int newlineIdx;
                while ((newlineIdx = lineBuilder.indexOf("\n")) != -1) {
                    var line = lineBuilder.substring(0, newlineIdx).trim();
                    lineBuilder.delete(0, newlineIdx + 1);

                    if (line.isEmpty()) continue;

                    processLine(channel, line, lineBuilder);
                }
            }
        } catch (IOException e) {
            if (channel.isOpen()) {
                log.debug("Connection closed: {}", e.getMessage());
            }
        }
    }

    private void processLine(SocketChannel channel, String line, StringBuilder lineBuilder) {
        try {
            var request = objectMapper.readValue(line, JsonRpc.Request.class);

            Object response;
            if (request.method() != null && router.isStreamingMethod(request.method())) {
                // Create a StreamContext for bidirectional communication
                var context = new ChannelStreamContext(channel, lineBuilder, objectMapper);
                response = router.routeStreaming(request, context);
            } else {
                response = router.route(request);
            }

            if (response != null) {
                var json = objectMapper.writeValueAsString(response) + "\n";
                channel.write(ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)));
            }
        } catch (JsonProcessingException e) {
            log.warn("Invalid JSON-RPC request: {}", e.getMessage());
            sendError(channel, null, JsonRpc.PARSE_ERROR, "Parse error: " + e.getMessage());
        } catch (IOException e) {
            log.error("Failed to write response: {}", e.getMessage());
        }
    }

    private void sendError(SocketChannel channel, Object id, int code, String message) {
        try {
            var error = JsonRpc.ErrorResponse.of(id, code, message);
            var json = objectMapper.writeValueAsString(error) + "\n";
            channel.write(ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException e) {
            log.error("Failed to send error response: {}", e.getMessage());
        }
    }

    /**
     * StreamContext implementation backed by a SocketChannel.
     *
     * <p>Sends notifications by writing newline-delimited JSON to the channel.
     * Reads messages by consuming data from the channel (and any buffered data
     * in the connection's line builder).
     */
    private static final class ChannelStreamContext implements StreamContext {

        private final SocketChannel channel;
        private final StringBuilder lineBuilder;
        private final ObjectMapper objectMapper;

        ChannelStreamContext(SocketChannel channel, StringBuilder lineBuilder, ObjectMapper objectMapper) {
            this.channel = channel;
            this.lineBuilder = lineBuilder;
            this.objectMapper = objectMapper;
        }

        @Override
        public void sendNotification(String method, Object params) throws IOException {
            var notification = JsonRpc.Notification.of(method, params);
            var json = objectMapper.writeValueAsString(notification) + "\n";
            synchronized (channel) {
                channel.write(ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)));
            }
        }

        @Override
        public JsonNode readMessage() throws IOException {
            // First check if there is a complete line already buffered
            int newlineIdx = lineBuilder.indexOf("\n");
            if (newlineIdx != -1) {
                var line = lineBuilder.substring(0, newlineIdx).trim();
                lineBuilder.delete(0, newlineIdx + 1);
                if (!line.isEmpty()) {
                    return objectMapper.readTree(line);
                }
            }

            // Read from the channel until we get a complete line
            var buffer = ByteBuffer.allocate(BUFFER_SIZE);
            while (channel.isOpen()) {
                buffer.clear();
                int bytesRead = channel.read(buffer);
                if (bytesRead == -1) return null; // Connection closed
                if (bytesRead == 0) continue;

                buffer.flip();
                var chunk = StandardCharsets.UTF_8.decode(buffer).toString();
                lineBuilder.append(chunk);

                newlineIdx = lineBuilder.indexOf("\n");
                if (newlineIdx != -1) {
                    var line = lineBuilder.substring(0, newlineIdx).trim();
                    lineBuilder.delete(0, newlineIdx + 1);
                    if (!line.isEmpty()) {
                        return objectMapper.readTree(line);
                    }
                }
            }
            return null;
        }
    }
}

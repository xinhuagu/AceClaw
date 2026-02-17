package dev.chelava.daemon;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * JSON-RPC 2.0 protocol types for client-daemon communication.
 */
public final class JsonRpc {

    public static final String VERSION = "2.0";

    private JsonRpc() {}

    /**
     * A JSON-RPC request from a client.
     *
     * @param jsonrpc protocol version (must be "2.0")
     * @param method  RPC method name (e.g. "session.create", "agent.prompt")
     * @param params  method parameters (may be null)
     * @param id      request identifier (null for notifications)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Request(
            String jsonrpc,
            String method,
            JsonNode params,
            Object id
    ) {
        /**
         * Whether this is a notification (no response expected).
         */
        public boolean isNotification() {
            return id == null;
        }
    }

    /**
     * A successful JSON-RPC response.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Response(
            String jsonrpc,
            Object result,
            Object id
    ) {
        public static Response success(Object id, Object result) {
            return new Response(VERSION, result, id);
        }
    }

    /**
     * A JSON-RPC error response.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorResponse(
            String jsonrpc,
            Error error,
            Object id
    ) {
        public static ErrorResponse of(Object id, int code, String message) {
            return new ErrorResponse(VERSION, new Error(code, message, null), id);
        }

        public static ErrorResponse of(Object id, int code, String message, Object data) {
            return new ErrorResponse(VERSION, new Error(code, message, data), id);
        }
    }

    /**
     * JSON-RPC error object.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Error(
            int code,
            String message,
            Object data
    ) {}

    /**
     * A JSON-RPC notification from the daemon to a client (no id, no response expected).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Notification(
            String jsonrpc,
            String method,
            Object params
    ) {
        public static Notification of(String method, Object params) {
            return new Notification(VERSION, method, params);
        }
    }

    // Standard JSON-RPC error codes
    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;
}

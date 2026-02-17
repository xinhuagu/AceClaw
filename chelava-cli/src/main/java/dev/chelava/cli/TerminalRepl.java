package dev.chelava.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.completer.FileNameCompleter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Interactive REPL (Read-Eval-Print Loop) for the Chelava CLI.
 *
 * <p>Uses JLine3 for line editing, history, and tab completion. Sends user
 * input to the daemon as {@code agent.prompt} JSON-RPC requests and streams
 * back responses.
 *
 * <p>During streaming, the REPL processes intermediate notifications:
 * <ul>
 *   <li>{@code stream.text} - prints text deltas to the terminal</li>
 *   <li>{@code stream.tool_use} - displays tool invocation info</li>
 *   <li>{@code permission.request} - prompts the user for approval</li>
 *   <li>{@code stream.error} - displays stream errors</li>
 * </ul>
 */
public final class TerminalRepl {

    private static final Logger log = LoggerFactory.getLogger(TerminalRepl.class);

    private static final Path HISTORY_FILE = Path.of(
            System.getProperty("user.home"), ".chelava", "history");

    /** JSON-RPC error code for method not found. */
    private static final int METHOD_NOT_FOUND = -32601;

    private final DaemonClient client;
    private final String sessionId;

    private volatile boolean streaming = false;

    /** LineReader reference for use during permission prompts. */
    private volatile LineReader activeReader;

    /**
     * Creates a REPL connected to the given daemon client and session.
     *
     * @param client    connected daemon client
     * @param sessionId active session identifier
     */
    public TerminalRepl(DaemonClient client, String sessionId) {
        this.client = client;
        this.sessionId = sessionId;
    }

    /**
     * Runs the interactive REPL loop. Blocks until the user exits.
     */
    public void run() {
        try (Terminal terminal = TerminalBuilder.builder()
                .name("chelava")
                .system(true)
                .build()) {

            // Ensure history directory exists
            Files.createDirectories(HISTORY_FILE.getParent());

            LineReader reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .completer(new FileNameCompleter())
                    .variable(LineReader.HISTORY_FILE, HISTORY_FILE)
                    .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                    .build();

            activeReader = reader;

            PrintWriter out = terminal.writer();
            String prompt = "\u001B[36mchelava>\u001B[0m ";

            out.println("Type your prompt, or Ctrl+D to exit.");
            out.flush();

            // Install signal handler for Ctrl+C during streaming
            terminal.handle(Terminal.Signal.INT, _ -> {
                if (streaming) {
                    cancelStreaming(out);
                }
            });

            while (true) {
                String line;
                try {
                    line = reader.readLine(prompt);
                } catch (UserInterruptException e) {
                    // Ctrl+C at prompt: exit gracefully
                    out.println();
                    break;
                } catch (EndOfFileException e) {
                    // Ctrl+D: exit gracefully
                    out.println();
                    break;
                }

                if (line == null || line.isBlank()) {
                    continue;
                }

                // Handle multi-line input (backslash continuation)
                while (line.endsWith("\\")) {
                    line = line.substring(0, line.length() - 1);
                    try {
                        String continuation = reader.readLine("... ");
                        if (continuation == null) break;
                        line += "\n" + continuation;
                    } catch (UserInterruptException | EndOfFileException e) {
                        break;
                    }
                }

                processInput(out, line.trim());
            }

        } catch (IOException e) {
            log.error("Terminal error: {}", e.getMessage(), e);
            System.err.println("Terminal error: " + e.getMessage());
        }
    }

    // -- internal --------------------------------------------------------

    private void processInput(PrintWriter out, String input) {
        try {
            streaming = true;

            // Build agent.prompt params
            ObjectNode params = client.objectMapper().createObjectNode();
            params.put("sessionId", sessionId);
            params.put("prompt", input);

            // Send the request (writes the JSON line to the socket)
            long id = sendPromptRequest(params);

            // Enter streaming read loop: process notifications until the final response
            boolean receivedTextOutput = false;
            boolean done = false;
            while (!done) {
                String responseLine = client.readLine();
                if (responseLine == null) {
                    out.println("\n[Connection closed]");
                    out.flush();
                    break;
                }

                JsonNode message = client.objectMapper().readTree(responseLine);

                if (message.has("id") && !message.get("id").isNull()) {
                    // This is the final JSON-RPC response
                    done = true;
                    if (receivedTextOutput) {
                        out.println(); // End the streaming text output
                    }

                    if (message.has("error") && !message.get("error").isNull()) {
                        JsonNode error = message.get("error");
                        int code = error.get("code").asInt();
                        String errorMessage = error.get("message").asText();
                        if (code == METHOD_NOT_FOUND) {
                            out.println("\u001B[31m[Agent not available. Is the daemon configured correctly?]\u001B[0m");
                        } else {
                            out.printf("\u001B[31mError: %s\u001B[0m%n", errorMessage);
                        }
                    } else {
                        JsonNode result = message.get("result");
                        if (result != null && !receivedTextOutput) {
                            // Only show the result summary if we did not stream text
                            if (result.has("response")) {
                                var response = result.get("response").asText();
                                if (!response.isEmpty()) {
                                    out.println(response);
                                }
                            }
                        }
                        // Show usage info
                        if (result != null && result.has("usage")) {
                            var usage = result.get("usage");
                            out.printf("[tokens: %d in / %d out]%n",
                                    usage.path("inputTokens").asInt(0),
                                    usage.path("outputTokens").asInt(0));
                        }
                    }
                    out.flush();

                } else if (message.has("method")) {
                    // This is a notification
                    String method = message.get("method").asText();
                    JsonNode notifParams = message.get("params");

                    switch (method) {
                        case "stream.text" -> {
                            // Print text delta without newline, flush immediately
                            if (notifParams != null && notifParams.has("delta")) {
                                out.print(notifParams.get("delta").asText());
                                out.flush();
                                receivedTextOutput = true;
                            }
                        }

                        case "stream.tool_use" -> {
                            if (receivedTextOutput) {
                                out.println(); // End text line before tool info
                                receivedTextOutput = false;
                            }
                            if (notifParams != null) {
                                String toolName = notifParams.path("name").asText("unknown");
                                out.printf("\u001B[33m[tool: %s]\u001B[0m%n", toolName);
                                out.flush();
                            }
                        }

                        case "permission.request" -> {
                            if (receivedTextOutput) {
                                out.println();
                                receivedTextOutput = false;
                            }
                            handlePermissionRequest(out, notifParams);
                        }

                        case "stream.error" -> {
                            if (receivedTextOutput) {
                                out.println();
                                receivedTextOutput = false;
                            }
                            if (notifParams != null && notifParams.has("error")) {
                                out.printf("\u001B[31m[stream error: %s]\u001B[0m%n",
                                        notifParams.get("error").asText());
                                out.flush();
                            }
                        }

                        default -> {
                            log.debug("Ignoring unknown notification: {}", method);
                        }
                    }
                } else {
                    log.debug("Received unrecognized message: {}", responseLine);
                }
            }

        } catch (IOException e) {
            out.println("Connection error: " + e.getMessage());
            out.flush();
            log.error("I/O error during prompt: {}", e.getMessage(), e);
        } finally {
            streaming = false;
        }
    }

    /**
     * Sends the agent.prompt request and returns the request ID.
     */
    private long sendPromptRequest(ObjectNode params) throws IOException {
        long id = client.nextRequestId();
        ObjectNode request = client.objectMapper().createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("method", "agent.prompt");
        request.set("params", params);
        request.put("id", id);
        client.writeLine(client.objectMapper().writeValueAsString(request));
        return id;
    }

    /**
     * Handles a permission.request notification by prompting the user
     * and sending back a permission.response notification.
     */
    private void handlePermissionRequest(PrintWriter out, JsonNode params) {
        if (params == null) return;

        String tool = params.path("tool").asText("unknown");
        String description = params.path("description").asText("");
        String requestId = params.path("requestId").asText("");

        out.println();
        out.printf("\u001B[1;33m[Permission Required]\u001B[0m %s%n", description);
        out.printf("  Allow \u001B[1m%s\u001B[0m? (y)es / (n)o / (a)lways: ", tool);
        out.flush();

        boolean approved = false;
        boolean remember = false;

        try {
            // Read a single line of user input for the permission decision
            var reader = activeReader;
            if (reader != null) {
                String answer = reader.readLine("");
                if (answer != null) {
                    answer = answer.trim().toLowerCase();
                    switch (answer) {
                        case "y", "yes" -> approved = true;
                        case "a", "always" -> {
                            approved = true;
                            remember = true;
                        }
                        default -> approved = false;
                    }
                }
            }
        } catch (UserInterruptException | EndOfFileException e) {
            approved = false;
        }

        // Send permission.response back to daemon
        try {
            ObjectNode responseParams = client.objectMapper().createObjectNode();
            responseParams.put("requestId", requestId);
            responseParams.put("approved", approved);
            responseParams.put("remember", remember);
            client.sendNotification("permission.response", responseParams);

            if (approved) {
                out.printf("\u001B[32m[Approved%s]\u001B[0m%n", remember ? " (always)" : "");
            } else {
                out.printf("\u001B[31m[Denied]\u001B[0m%n");
            }
            out.flush();
        } catch (IOException e) {
            log.error("Failed to send permission response: {}", e.getMessage());
            out.println("[Failed to send permission response]");
            out.flush();
        }
    }

    private void cancelStreaming(PrintWriter out) {
        try {
            ObjectNode params = client.objectMapper().createObjectNode();
            params.put("sessionId", sessionId);
            client.sendNotification("agent.cancel", params);
            out.println("\n[Cancelled]");
            out.flush();
        } catch (IOException e) {
            log.warn("Failed to send cancel: {}", e.getMessage());
        }
    }
}

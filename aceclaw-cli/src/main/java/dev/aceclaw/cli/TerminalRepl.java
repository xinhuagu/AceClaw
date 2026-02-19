package dev.aceclaw.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jline.keymap.KeyMap;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
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
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import static dev.aceclaw.cli.TerminalTheme.*;

/**
 * Interactive REPL (Read-Eval-Print Loop) for the AceClaw CLI.
 *
 * <p>Uses JLine3 for line editing, history, and tab completion. Sends user
 * input to the daemon as {@code agent.prompt} JSON-RPC requests and streams
 * back responses with incremental markdown rendering.
 *
 * <p>During streaming, the REPL processes intermediate notifications:
 * <ul>
 *   <li>{@code stream.thinking} - displays thinking deltas as dim italic text</li>
 *   <li>{@code stream.text} - incrementally renders markdown paragraphs</li>
 *   <li>{@code stream.tool_use} - shows spinner during tool execution</li>
 *   <li>{@code permission.request} - prompts the user for approval with box-drawing UI</li>
 *   <li>{@code stream.error} - displays stream errors</li>
 * </ul>
 */
public final class TerminalRepl {

    private static final Logger log = LoggerFactory.getLogger(TerminalRepl.class);

    private static final Path HISTORY_FILE = Path.of(
            System.getProperty("user.home"), ".aceclaw", "history");

    /** JSON-RPC error code for method not found. */
    private static final int METHOD_NOT_FOUND = -32601;

    private static final String PROMPT_STR = PROMPT + "aceclaw>" + RESET + " ";
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final DaemonClient client;
    private final String sessionId;
    private final SessionInfo sessionInfo;
    private final TerminalMarkdownRenderer markdownRenderer;

    private volatile boolean streaming = false;

    /** Prevents duplicate cancel notifications on rapid Ctrl+C. */
    private volatile boolean cancelSent = false;

    /** LineReader reference for use during permission prompts. */
    private volatile LineReader activeReader;

    /** Active spinner (non-null when a tool is executing). */
    private volatile TerminalSpinner spinner;

    /** Cumulative token counters across turns. */
    private long totalInputTokens = 0;
    private long totalOutputTokens = 0;

    /** Latest input tokens from the most recent API call (= context consumed). */
    private long latestInputTokens = 0;

    /** Timestamp when the current prompt was sent (nanos). */
    private long promptStartNanos = 0;


    /**
     * Session metadata displayed in the startup banner and status line.
     */
    public record SessionInfo(String version, String model, String project,
                              int contextWindowTokens, String gitBranch) {
        public SessionInfo(String version, String model, String project,
                           int contextWindowTokens) {
            this(version, model, project, contextWindowTokens, null);
        }
    }

    /**
     * Creates a REPL connected to the given daemon client and session.
     *
     * @param client      connected daemon client
     * @param sessionId   active session identifier
     * @param sessionInfo session metadata for banner and status line
     */
    public TerminalRepl(DaemonClient client, String sessionId, SessionInfo sessionInfo) {
        this.client = client;
        this.sessionId = sessionId;
        this.sessionInfo = sessionInfo;
        this.markdownRenderer = new TerminalMarkdownRenderer();
    }

    /**
     * Runs the interactive REPL loop. Blocks until the user exits.
     */
    public void run() {
        try (Terminal terminal = TerminalBuilder.builder()
                .name("aceclaw")
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

            // Render startup banner
            renderBanner(out, terminal.getWidth());

            // Override Ctrl+L: clear screen + redraw status bar below prompt
            reader.getWidgets().put("aceclaw-clear-screen", () -> {
                reader.callWidget(LineReader.CLEAR_SCREEN);
                // After clear, re-print status below the prompt using save/restore cursor
                PrintWriter w = terminal.writer();
                w.print("\0337");                        // DEC save cursor
                w.print("\n\r\033[K" + buildStatusString()); // next line, clear it, print status
                w.print("\0338");                        // DEC restore cursor
                w.flush();
                return true;
            });
            reader.getKeyMaps().get(LineReader.MAIN)
                    .bind(new Reference("aceclaw-clear-screen"), KeyMap.ctrl('L'));

            // Install signal handler for Ctrl+C during streaming
            terminal.handle(Terminal.Signal.INT, _ -> {
                if (streaming) {
                    cancelStreaming(out);
                }
            });

            while (true) {
                String line;
                try {
                    // Print status on the line below, then move cursor back up.
                    // JLine3 renders the prompt on the current line; status stays below.
                    out.print("\n\r\033[K" + buildStatusString()); // next line: clear + status
                    out.print("\033[A\r");                         // cursor up + to line start
                    out.flush();
                    line = reader.readLine(PROMPT_STR);
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

                String trimmed = line.trim();
                if (trimmed.startsWith("/")) {
                    if (handleSlashCommand(out, trimmed)) {
                        break; // Graceful exit — allows try-with-resources cleanup
                    }
                } else {
                    processInput(out, trimmed);
                }
            }

        } catch (IOException e) {
            log.error("Terminal error: {}", e.getMessage(), e);
            System.err.println("Terminal error: " + e.getMessage());
        }
    }

    // -- Banner rendering ----------------------------------------------------

    private void renderBanner(PrintWriter out, int termWidth) {
        int innerWidth = Math.max(40, Math.min(termWidth - 4, 60));

        String titleLine = "  AceClaw  v" + sessionInfo.version();

        // Shorten the model name for display
        String modelDisplay = sessionInfo.model();
        String projectDisplay = fitWidth(sessionInfo.project(), innerWidth - 14);

        String modelLine = "  Model: " + modelDisplay;
        String projectLine = "  Project: " + projectDisplay;

        out.println();
        out.println(ACCENT + BOX_TOP_LEFT + hline(innerWidth) + BOX_TOP_RIGHT + RESET);
        out.println(ACCENT + BOX_VERTICAL + RESET + BOLD + padRight(titleLine, innerWidth) + ACCENT + BOX_VERTICAL + RESET);
        out.println(ACCENT + BOX_VERTICAL + RESET + MUTED + padRight(modelLine, innerWidth) + ACCENT + BOX_VERTICAL + RESET);
        out.println(ACCENT + BOX_VERTICAL + RESET + MUTED + padRight(projectLine, innerWidth) + ACCENT + BOX_VERTICAL + RESET);
        out.println(ACCENT + BOX_BOTTOM_LEFT + hline(innerWidth) + BOX_BOTTOM_RIGHT + RESET);
        out.println();
        out.flush();
    }

    // -- Status line (below prompt via ANSI cursor positioning) ----------------

    /**
     * Builds the colored status string (no trailing newline).
     * Printed on the line below the prompt using ANSI cursor movement.
     */
    private String buildStatusString() {
        var sb = new StringBuilder();

        sb.append(MUTED).append("\u2500").append(RESET).append(" ");
        sb.append(INFO).append(BOLD).append(sessionInfo.model()).append(RESET);

        String branch = sessionInfo.gitBranch();
        if (branch != null && !branch.isBlank()) {
            sb.append(MUTED).append(" \u2502 ").append(RESET);
            sb.append(SUCCESS).append("\u2387 ").append(branch).append(RESET);
        }

        int ctxWindow = sessionInfo.contextWindowTokens();
        if (ctxWindow > 0 && latestInputTokens > 0) {
            sb.append(MUTED).append(" \u2502 ").append(RESET);
            long pct = latestInputTokens * 100 / ctxWindow;
            sb.append(WARNING).append(formatTokenCount(latestInputTokens))
              .append("/").append(formatTokenCount(ctxWindow))
              .append(" (").append(pct).append("%)").append(RESET);
        }

        sb.append(MUTED).append(" \u2502 ").append(RESET);
        sb.append(MUTED).append(LocalTime.now().format(TIME_FMT)).append(RESET);

        return sb.toString();
    }

    private static String formatTokenCount(long tokens) {
        if (tokens < 1000) return String.valueOf(tokens);
        double k = tokens / 1000.0;
        if (k >= 100) return String.format("%.0fK", k);
        if (k >= 10) return String.format("%.0fK", k);
        return String.format("%.1fK", k);
    }

    // -- Slash commands -------------------------------------------------------

    /**
     * Handles slash commands entered at the REPL prompt.
     *
     * <p>Supported commands:
     * <ul>
     *   <li>{@code /help} — show available commands</li>
     *   <li>{@code /clear} — clear the screen</li>
     *   <li>{@code /compact} — trigger context compaction</li>
     *   <li>{@code /model} — show or switch the current model</li>
     *   <li>{@code /tools} — list available tools</li>
     *   <li>{@code /status} — show session status (tokens, model, context)</li>
     *   <li>{@code /exit} — exit the REPL</li>
     * </ul>
     */
    /**
     * @return true if the REPL should exit after this command
     */
    boolean handleSlashCommand(PrintWriter out, String input) {
        String[] parts = input.split("\\s+", 2);
        String command = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1].trim() : "";

        switch (command) {
            case "/help", "/?" -> {
                out.println();
                out.println(BOLD + "Available commands:" + RESET);
                out.println(INFO + "  /help" + RESET + "     Show this help message");
                out.println(INFO + "  /clear" + RESET + "    Clear the screen");
                out.println(INFO + "  /compact" + RESET + "  Trigger context compaction");
                out.println(INFO + "  /model" + RESET + "    Show current model (or /model <name> to switch)");
                out.println(INFO + "  /tools" + RESET + "    List available tools");
                out.println(INFO + "  /status" + RESET + "   Show session status");
                out.println(INFO + "  /exit" + RESET + "     Exit the REPL");
                out.println();
                out.flush();
            }

            case "/clear" -> {
                out.print("\033[2J\033[H"); // ANSI: clear screen + move cursor to top
                out.flush();
            }

            case "/compact" -> {
                out.println(MUTED + "Requesting context compaction..." + RESET);
                out.flush();
                sendRpcNotification("session.compact");
            }

            case "/model" -> {
                handleModelCommand(out, arg);
            }

            case "/tools" -> {
                out.println(MUTED + "Requesting tool list..." + RESET);
                out.flush();
                try {
                    long id = client.nextRequestId();
                    var request = client.objectMapper().createObjectNode();
                    request.put("jsonrpc", "2.0");
                    request.put("method", "tools.list");
                    request.put("id", id);
                    client.writeLine(client.objectMapper().writeValueAsString(request));

                    // Read with timeout to avoid hanging the REPL if daemon doesn't respond
                    String responseLine = client.readLine(5000);
                    if (responseLine == null) {
                        out.println(WARNING + "Timed out waiting for tool list from daemon" + RESET);
                    } else {
                        var response = client.objectMapper().readTree(responseLine);
                        if (response.has("result") && response.get("result").isArray()) {
                            out.println();
                            out.println(BOLD + "Available tools:" + RESET);
                            for (var tool : response.get("result")) {
                                String name = tool.path("name").asText();
                                String desc = tool.path("description").asText();
                                // Truncate description to first sentence
                                int dot = desc.indexOf('.');
                                if (dot > 0 && dot < 80) desc = desc.substring(0, dot + 1);
                                else if (desc.length() > 80) desc = desc.substring(0, 77) + "...";
                                out.printf("  %s%-16s%s %s%s%s%n", INFO, name, RESET, MUTED, desc, RESET);
                            }
                            out.println();
                        } else if (response.has("error")) {
                            out.println(WARNING + "tools.list not supported by daemon" + RESET);
                        }
                    }
                } catch (IOException e) {
                    out.println(ERROR + "Failed to list tools: " + e.getMessage() + RESET);
                }
                out.flush();
            }

            case "/status" -> {
                out.println();
                out.println(BOLD + "Session Status" + RESET);
                out.printf("  %sModel:%s       %s%n", MUTED, RESET, sessionInfo.model());
                out.printf("  %sProject:%s     %s%n", MUTED, RESET, sessionInfo.project());
                if (sessionInfo.gitBranch() != null) {
                    out.printf("  %sGit branch:%s  %s%n", MUTED, RESET, sessionInfo.gitBranch());
                }
                out.printf("  %sContext:%s     %s / %s (%d%%)%n", MUTED, RESET,
                        formatTokenCount(latestInputTokens),
                        formatTokenCount(sessionInfo.contextWindowTokens()),
                        sessionInfo.contextWindowTokens() > 0
                                ? latestInputTokens * 100 / sessionInfo.contextWindowTokens() : 0);
                out.printf("  %sTotal usage:%s %s in / %s out%n", MUTED, RESET,
                        formatTokenCount(totalInputTokens),
                        formatTokenCount(totalOutputTokens));
                out.println();
                out.flush();
            }

            case "/exit", "/quit" -> {
                out.println(MUTED + "Goodbye!" + RESET);
                out.flush();
                return true;
            }

            default -> {
                out.println(WARNING + "Unknown command: " + command +
                        ". Type /help for available commands." + RESET);
                out.flush();
            }
        }
        return false;
    }

    /**
     * Handles the /model command: lists available models and allows interactive switching.
     * If an argument is provided, switches directly to that model.
     */
    private void handleModelCommand(PrintWriter out, String arg) {
        if (client == null) {
            out.println(WARNING + "Not connected to daemon." + RESET);
            out.flush();
            return;
        }
        try {
            // Fetch model list from daemon
            long id = client.nextRequestId();
            var request = client.objectMapper().createObjectNode();
            request.put("jsonrpc", "2.0");
            request.put("method", "model.list");
            request.put("id", id);
            client.writeLine(client.objectMapper().writeValueAsString(request));

            String responseLine = client.readLine(5000);
            if (responseLine == null) {
                out.println(WARNING + "Timed out waiting for model list from daemon" + RESET);
                out.flush();
                return;
            }

            var response = client.objectMapper().readTree(responseLine);
            if (response.has("error")) {
                out.println(WARNING + "model.list not supported by daemon" + RESET);
                out.flush();
                return;
            }

            var result = response.get("result");
            String currentModel = result.path("currentModel").asText("");
            String provider = result.path("provider").asText("");
            var modelsNode = result.get("models");

            // If arg provided, switch directly
            if (!arg.isEmpty()) {
                switchModel(out, arg);
                return;
            }

            // Display current model info
            out.println();
            out.println(BOLD + "Model Selection" + RESET + MUTED + " (" + provider + ")" + RESET);
            out.println();

            if (modelsNode == null || !modelsNode.isArray() || modelsNode.isEmpty()) {
                out.println(INFO + "  Current: " + BOLD + currentModel + RESET);
                out.println(MUTED + "  (Model listing not supported by this provider. " +
                        "Use /model <name> to switch directly.)" + RESET);
                out.println();
                out.flush();
                return;
            }

            // Build numbered list
            var models = new java.util.ArrayList<String>();
            for (var m : modelsNode) {
                models.add(m.asText());
            }

            for (int i = 0; i < models.size(); i++) {
                String m = models.get(i);
                boolean isCurrent = m.equals(currentModel);
                out.printf("  %s%2d)%s %s%s%s%s%n",
                        MUTED, i + 1, RESET,
                        isCurrent ? BOLD + INFO : "",
                        m,
                        isCurrent ? " \u2190 current" : "",
                        RESET);
            }
            out.println();
            out.print(MUTED + "  Select model (number or name, Enter to cancel): " + RESET);
            out.flush();

            // Read selection
            var reader = activeReader;
            if (reader == null) return;

            String selection;
            try {
                selection = reader.readLine("");
            } catch (org.jline.reader.UserInterruptException | org.jline.reader.EndOfFileException e) {
                out.println();
                return;
            }

            if (selection == null || selection.isBlank()) {
                out.println(MUTED + "  Cancelled." + RESET);
                out.flush();
                return;
            }

            // Resolve selection: number or model name
            String selectedModel;
            try {
                int num = Integer.parseInt(selection.trim());
                if (num < 1 || num > models.size()) {
                    out.println(WARNING + "  Invalid selection." + RESET);
                    out.flush();
                    return;
                }
                selectedModel = models.get(num - 1);
            } catch (NumberFormatException e) {
                selectedModel = selection.trim();
            }

            if (selectedModel.equals(currentModel)) {
                out.println(MUTED + "  Already using " + currentModel + RESET);
                out.flush();
                return;
            }

            switchModel(out, selectedModel);

        } catch (IOException e) {
            out.println(ERROR + "Failed to list models: " + e.getMessage() + RESET);
            out.flush();
        }
    }

    /**
     * Sends a model.switch RPC call to the daemon.
     */
    private void switchModel(PrintWriter out, String modelId) {
        try {
            long id = client.nextRequestId();
            var request = client.objectMapper().createObjectNode();
            request.put("jsonrpc", "2.0");
            request.put("method", "model.switch");
            var params = client.objectMapper().createObjectNode();
            params.put("model", modelId);
            request.set("params", params);
            request.put("id", id);
            client.writeLine(client.objectMapper().writeValueAsString(request));

            String responseLine = client.readLine(5000);
            if (responseLine == null) {
                out.println(WARNING + "Timed out waiting for model switch response" + RESET);
                out.flush();
                return;
            }

            var response = client.objectMapper().readTree(responseLine);
            if (response.has("error")) {
                var error = response.get("error");
                out.println(ERROR + "Failed to switch model: " + error.path("message").asText() + RESET);
            } else {
                out.println(SUCCESS + "  \u2713 Switched to " + BOLD + modelId + RESET);
            }
            out.flush();
        } catch (IOException e) {
            out.println(ERROR + "Failed to switch model: " + e.getMessage() + RESET);
            out.flush();
        }
    }

    /**
     * Sends a JSON-RPC notification to the daemon (no response expected).
     */
    private void sendRpcNotification(String method) {
        try {
            var params = client.objectMapper().createObjectNode();
            params.put("sessionId", sessionId);
            client.sendNotification(method, params);
        } catch (IOException e) {
            log.warn("Failed to send {} notification: {}", method, e.getMessage());
        }
    }

    // -- Input processing ----------------------------------------------------

    private void processInput(PrintWriter out, String input) {
        try {
            streaming = true;
            cancelSent = false;

            // Build agent.prompt params
            ObjectNode params = client.objectMapper().createObjectNode();
            params.put("sessionId", sessionId);
            params.put("prompt", input);

            // Send the request (writes the JSON line to the socket)
            promptStartNanos = System.nanoTime();
            long id = sendPromptRequest(params);

            // Start thinking spinner immediately — visible while waiting for LLM
            spinner = new TerminalSpinner(out, TerminalSpinner.Style.MOON);
            spinner.start("Thinking...");

            // Enter streaming read loop: process notifications until the final response
            var textBuffer = new StringBuilder();
            boolean receivedTextOutput = false;
            boolean wasThinking = false;
            boolean inCodeFence = false;
            boolean done = false;
            while (!done) {
                String responseLine = client.readLine();
                if (responseLine == null) {
                    stopSpinner();
                    flushMarkdown(out, textBuffer);
                    out.println("\n[Connection closed]");
                    out.flush();
                    break;
                }

                JsonNode message = client.objectMapper().readTree(responseLine);

                if (message.has("id") && !message.get("id").isNull()) {
                    // This is the final JSON-RPC response
                    done = true;
                    stopSpinner();

                    // Render any buffered markdown text
                    if (receivedTextOutput) {
                        flushMarkdown(out, textBuffer);
                    }

                    if (message.has("error") && !message.get("error").isNull()) {
                        JsonNode error = message.get("error");
                        int code = error.get("code").asInt();
                        String errorMessage = error.get("message").asText();
                        if (code == METHOD_NOT_FOUND) {
                            out.println(ERROR + "[Agent not available. Is the daemon configured correctly?]" + RESET);
                        } else {
                            out.printf("%sError: %s%s%n", ERROR, errorMessage, RESET);
                        }
                    } else {
                        JsonNode result = message.get("result");
                        if (result != null && !receivedTextOutput) {
                            // Only show the result summary if we did not stream text
                            if (result.has("response")) {
                                var response = result.get("response").asText();
                                if (!response.isEmpty()) {
                                    markdownRenderer.render(response, out);
                                }
                            }
                        }
                        // Update token counters and show response summary
                        if (result != null && result.has("usage")) {
                            var usage = result.get("usage");
                            int turnIn = usage.path("inputTokens").asInt(0);
                            int turnOut = usage.path("outputTokens").asInt(0);
                            totalInputTokens += turnIn;
                            totalOutputTokens += turnOut;
                            latestInputTokens = turnIn;

                            // Response time
                            long elapsedMs = (System.nanoTime() - promptStartNanos) / 1_000_000;
                            String elapsed = elapsedMs >= 1000
                                    ? String.format("%.1fs", elapsedMs / 1000.0)
                                    : elapsedMs + "ms";

                            // Compact info line below the response
                            out.println();
                            out.printf("%s%s  %d in / %d out  %s%s%n",
                                    MUTED, elapsed, turnIn, turnOut,
                                    sessionInfo.contextWindowTokens() > 0
                                            ? "context " + formatTokenCount(turnIn) + "/" + formatTokenCount(sessionInfo.contextWindowTokens())
                                            : "",
                                    RESET);
                        }
                    }
                    out.flush();

                } else if (message.has("method")) {
                    // This is a notification
                    String method = message.get("method").asText();
                    JsonNode notifParams = message.get("params");

                    switch (method) {
                        case "stream.thinking" -> {
                            if (notifParams != null && notifParams.has("delta")) {
                                String delta = notifParams.get("delta").asText();
                                stopSpinner();
                                out.print(THINKING + delta + RESET);
                                out.flush();
                                wasThinking = true;
                            }
                        }

                        case "stream.text" -> {
                            // Incremental paragraph-level markdown rendering
                            if (notifParams != null && notifParams.has("delta")) {
                                String delta = notifParams.get("delta").asText();
                                stopSpinner();

                                // Transition from thinking to response text
                                if (wasThinking) {
                                    out.println();
                                    out.println();
                                    wasThinking = false;
                                }

                                textBuffer.append(delta);
                                receivedTextOutput = true;

                                // Track code fence state to avoid splitting inside fenced blocks
                                for (int i = 0; i < delta.length(); i++) {
                                    if (i + 2 < delta.length()
                                            && delta.charAt(i) == '`'
                                            && delta.charAt(i + 1) == '`'
                                            && delta.charAt(i + 2) == '`') {
                                        inCodeFence = !inCodeFence;
                                    }
                                }

                                // Render complete paragraph blocks (delimited by \n\n) if not in a code fence
                                if (!inCodeFence) {
                                    int boundary;
                                    while ((boundary = textBuffer.indexOf("\n\n")) != -1) {
                                        // Include the double newline in the block
                                        String block = textBuffer.substring(0, boundary + 2);
                                        textBuffer.delete(0, boundary + 2);
                                        markdownRenderer.render(block, out);
                                        out.flush();
                                    }
                                }
                            }
                        }

                        case "stream.tool_use" -> {
                            if (receivedTextOutput) {
                                flushMarkdown(out, textBuffer);
                                inCodeFence = false;
                                receivedTextOutput = false;
                            }
                            if (wasThinking) {
                                out.println();
                                wasThinking = false;
                            }
                            stopSpinner();
                            if (notifParams != null) {
                                String toolName = notifParams.path("name").asText("unknown");
                                String verb = TerminalSpinner.verbForTool(toolName);
                                spinner = new TerminalSpinner(out);
                                spinner.start(verb + " " + toolName + "...");
                            }
                        }

                        case "permission.request" -> {
                            if (receivedTextOutput) {
                                flushMarkdown(out, textBuffer);
                                inCodeFence = false;
                                receivedTextOutput = false;
                            }
                            if (wasThinking) {
                                out.println();
                                wasThinking = false;
                            }
                            stopSpinner();
                            handlePermissionRequest(out, notifParams);
                        }

                        case "stream.error" -> {
                            if (receivedTextOutput) {
                                flushMarkdown(out, textBuffer);
                                inCodeFence = false;
                                receivedTextOutput = false;
                            }
                            stopSpinner();
                            if (notifParams != null && notifParams.has("error")) {
                                out.printf("%s[stream error: %s]%s%n",
                                        ERROR, notifParams.get("error").asText(), RESET);
                                out.flush();
                            }
                        }

                        case "stream.cancelled" -> {
                            // Server acknowledged cancellation; stop any active spinners
                            stopSpinner();
                            log.debug("Received stream.cancelled notification");
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
            stopSpinner();
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
     * Uses box-drawing characters for a polished permission UI.
     */
    private void handlePermissionRequest(PrintWriter out, JsonNode params) {
        if (params == null) return;

        String tool = params.path("tool").asText("unknown");
        String description = params.path("description").asText("");
        String requestId = params.path("requestId").asText("");

        int boxWidth = 50;

        out.println();
        out.println(PERMISSION + " " + BOX_LIGHT_TOP_LEFT + BOX_LIGHT_HORIZONTAL
                + " Permission Required " + hlineLight(boxWidth - 22) + RESET);
        out.println(PERMISSION + " " + BOX_LIGHT_VERTICAL + RESET + " " + description);
        out.println(PERMISSION + " " + BOX_LIGHT_VERTICAL + RESET);
        out.printf("%s %s%s (%sy%s) Allow  (%sn%s) Deny  (%sa%s) Always: ",
                PERMISSION, BOX_LIGHT_VERTICAL, RESET,
                APPROVED, RESET,
                DENIED, RESET,
                WARNING, RESET);
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

        // Close the box
        out.println(PERMISSION + " " + BOX_LIGHT_BOTTOM_LEFT + hlineLight(boxWidth) + RESET);

        // Send permission.response back to daemon
        try {
            ObjectNode responseParams = client.objectMapper().createObjectNode();
            responseParams.put("requestId", requestId);
            responseParams.put("approved", approved);
            responseParams.put("remember", remember);
            client.sendNotification("permission.response", responseParams);

            if (approved) {
                out.printf("%s%s Approved%s%s%n", APPROVED, CHECKMARK,
                        remember ? " (always)" : "", RESET);
            } else {
                out.printf("%sDenied%s%n", DENIED, RESET);
            }
            out.flush();
        } catch (IOException e) {
            log.error("Failed to send permission response: {}", e.getMessage());
            out.println("[Failed to send permission response]");
            out.flush();
        }
    }

    /**
     * Renders buffered markdown text and clears the buffer.
     */
    private void flushMarkdown(PrintWriter out, StringBuilder buffer) {
        if (buffer.isEmpty()) return;
        markdownRenderer.render(buffer.toString(), out);
        out.flush();
        buffer.setLength(0);
    }

    /**
     * Stops the active spinner if one is running. Clears the spinner line silently.
     */
    private void stopSpinner() {
        var s = spinner;
        if (s != null && s.isSpinning()) {
            s.clear();
            spinner = null;
        }
    }

    private void cancelStreaming(PrintWriter out) {
        if (cancelSent) return; // Prevent duplicate cancels on rapid Ctrl+C
        cancelSent = true;
        try {
            stopSpinner();
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

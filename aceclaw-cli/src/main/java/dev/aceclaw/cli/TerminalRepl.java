package dev.aceclaw.cli;

import com.fasterxml.jackson.databind.JsonNode;
import org.jline.keymap.KeyMap;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.completer.FileNameCompleter;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static dev.aceclaw.cli.TerminalTheme.*;

/**
 * Interactive REPL (Read-Eval-Print Loop) for the AceClaw CLI.
 *
 * <p>Uses JLine3 for line editing, history, and tab completion. Sends user
 * input to the daemon as {@code agent.prompt} JSON-RPC requests and streams
 * back responses with incremental markdown rendering.
 *
 * <p>Supports a dual-channel architecture: each agent task runs on its own
 * {@link DaemonConnection} and virtual thread via {@link TaskManager}.
 * Permission requests are routed through {@link PermissionBridge} to the
 * main REPL thread. Tasks can be sent to background ({@code /bg}) and
 * brought back ({@code /fg}).
 */
public final class TerminalRepl {

    private static final Logger log = LoggerFactory.getLogger(TerminalRepl.class);

    private static final Path HISTORY_FILE = Path.of(
            System.getProperty("user.home"), ".aceclaw", "history");

    private static final String PROMPT_STR = PROMPT + "aceclaw>" + RESET + " ";
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Pattern ANSI_CSI_PATTERN =
            Pattern.compile("\\u001B\\[[0-?]*[ -/]*[@-~]");

    private final DaemonClient client;
    private final String sessionId;
    private final SessionInfo sessionInfo;
    private final TerminalMarkdownRenderer markdownRenderer;
    private final TaskManager taskManager;
    private final PermissionBridge permissionBridge;
    private final Object uiRenderLock = new Object();
    private final AtomicBoolean permissionInterruptRequested = new AtomicBoolean(false);
    private int previousStatusLineCount;

    /** Tracks the effective model, updated after successful model switches. */
    private volatile String effectiveModel;

    /** LineReader reference for use during permission prompts. */
    private volatile LineReader activeReader;

    /** Terminal reference for raw mode during foreground wait. */
    private volatile Terminal activeTerminal;
    /** True while the main REPL thread is blocked in {@code readLine}. */
    private volatile boolean readingPrompt;

    /** Cumulative token counters across turns. */
    private long totalInputTokens = 0;
    private long totalOutputTokens = 0;

    /** Latest input tokens from the most recent API call (= context consumed). */
    private long latestInputTokens = 0;

    /** Timestamp when the current prompt was sent (nanos). */
    private long promptStartNanos = 0;

    /** Current foreground output sink (for Ctrl+C spinner cleanup). */
    private volatile ForegroundOutputSink activeForegroundSink;

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
        this.effectiveModel = sessionInfo.model();
        this.markdownRenderer = new TerminalMarkdownRenderer();
        this.taskManager = new TaskManager();
        this.permissionBridge = new PermissionBridge();
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
            activeTerminal = terminal;

            PrintWriter out = terminal.writer();

            // Register callback to auto-push background task output above prompt
            taskManager.setOnTaskComplete(handle -> pushBackgroundCompletion(handle, reader));
            permissionBridge.setRequestListener(req -> onPermissionRequested(req, reader));

            // Render startup banner
            renderBanner(out, terminal.getWidth());

            // Override Ctrl+L: clear screen + redraw status bar below prompt
            reader.getWidgets().put("aceclaw-clear-screen", () -> {
                reader.callWidget(LineReader.CLEAR_SCREEN);
                synchronized (uiRenderLock) {
                    previousStatusLineCount = 0;
                    PrintWriter w = terminal.writer();
                    renderStatusPanel(w, true);
                    w.flush();
                }
                return true;
            });
            reader.getKeyMaps().get(LineReader.MAIN)
                    .bind(new Reference("aceclaw-clear-screen"), KeyMap.ctrl('L'));

            // Install Ctrl+C handler: cancel foreground task or exit REPL
            terminal.handle(Terminal.Signal.INT, _ -> {
                if (taskManager.hasForegroundTask()) {
                    cancelForegroundTask(out);
                }
                // If no foreground task, JLine throws UserInterruptException from readLine()
            });

            while (true) {
                // 1. Check pending permission requests from task threads
                drainPermissions(out, reader);

                // 2. Check if any background tasks completed while we were idle
                notifyCompletedBackgroundTasks(out);

                String line;
                try {
                    // Print status on the line below, then move cursor back up.
                    synchronized (uiRenderLock) {
                        renderStatusPanel(out, true);
                        out.flush();
                    }
                    readingPrompt = true;
                    try {
                        line = reader.readLine(PROMPT_STR);
                    } finally {
                        readingPrompt = false;
                    }
                } catch (UserInterruptException e) {
                    // Permission requests can intentionally interrupt prompt input
                    // so approval appears immediately as a popup flow.
                    if (permissionInterruptRequested.getAndSet(false) || permissionBridge.hasPending()) {
                        out.println();
                        drainPermissions(out, reader);
                        continue;
                    }
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
                    if (handleSlashCommand(out, trimmed, reader)) {
                        break;
                    }
                } else {
                    submitAndWait(out, reader, trimmed);
                }
            }

        } catch (IOException e) {
            log.error("Terminal error: {}", e.getMessage(), e);
            System.err.println("Terminal error: " + e.getMessage());
        }
    }

    // -- Task submission and foreground wait ---------------------------------

    /**
     * Submits a new agent task.
     *
     * <p>Output streams to the terminal in real-time. While streaming, the terminal
     * is in raw mode and user keypresses are detected: pressing any key auto-backgrounds
     * the task and returns to the prompt so the user can type a new instruction.
     *
     * <p>If the task completes before the user types anything, the completion summary
     * is rendered inline (synchronous UX, same as before).
     *
     * <p>If auto-backgrounded, the task continues silently in a
     * {@link BackgroundOutputBuffer}. When it finishes, {@link #pushBackgroundCompletion}
     * uses JLine's {@code printAbove()} to display the result above the prompt.
     */
    private void submitAndWait(PrintWriter out, LineReader reader, String input) {
        try {
            var conn = client.openTaskConnection();
            var fgSink = new ForegroundOutputSink(out, markdownRenderer);
            activeForegroundSink = fgSink;

            promptStartNanos = System.nanoTime();

            var handle = taskManager.submit(input, conn, sessionId, fgSink, permissionBridge);
            taskManager.setForeground(handle.taskId());

            // Start thinking spinner
            fgSink.startThinkingSpinner();

            // Block until task completes OR user starts typing
            waitForForeground(out, reader);

            // If the task completed (not auto-backgrounded), render inline
            if (handle.isTerminal()) {
                renderTaskCompletion(out, handle);
                handle.markNotified();
            }
            // If auto-backgrounded, pushBackgroundCompletion() handles display later

            taskManager.clearForeground();
            activeForegroundSink = null;

            // Connection is closed by TaskManager.handleTaskComplete() when the task ends

        } catch (IOException e) {
            out.println("Connection error: " + e.getMessage());
            out.flush();
            log.error("Failed to open task connection: {}", e.getMessage(), e);
        }
    }

    /**
     * Polls foreground task completion, permission requests, and user keypresses.
     *
     * <p>The terminal is placed in raw mode so individual keypresses are detected
     * without waiting for Enter. If the user presses any key (except Ctrl+C),
     * the foreground task is automatically backgrounded and this method returns,
     * allowing the main loop to enter {@code readLine()} where the character
     * becomes the first character of the user's new input.
     *
     * <p>Ctrl+C cancels the foreground task. EOF (-1) breaks the wait.
     */
    private void waitForForeground(PrintWriter out, LineReader reader) {
        var terminal = activeTerminal;
        if (terminal == null) {
            // Fallback: simple polling without keypress detection
            simplePollForeground(out, reader);
            return;
        }

        // Enter raw mode: ICANON off (char-at-a-time), ECHO off
        Attributes savedAttrs = terminal.getAttributes();
        Attributes rawAttrs = new Attributes(savedAttrs);
        rawAttrs.setLocalFlag(Attributes.LocalFlag.ICANON, false);
        rawAttrs.setLocalFlag(Attributes.LocalFlag.ECHO, false);
        rawAttrs.setControlChar(Attributes.ControlChar.VMIN, 0);
        rawAttrs.setControlChar(Attributes.ControlChar.VTIME, 1);
        terminal.setAttributes(rawAttrs);

        try {
            while (taskManager.hasForegroundTask()) {
                // 1. Handle permission requests
                var permReq = permissionBridge.pollPending(10, TimeUnit.MILLISECONDS);
                if (permReq != null) {
                    // Restore terminal for interactive permission dialog
                    terminal.setAttributes(savedAttrs);
                    handlePermissionFromBridge(out, reader, permReq);
                    terminal.setAttributes(rawAttrs);
                    continue;
                }

                // 2. Check if user pressed a key (1ms peek — non-blocking)
                int ch = terminal.reader().peek(1);
                if (ch == -1) {
                    // EOF
                    break;
                } else if (ch == 3) {
                    // Ctrl+C — cancel foreground task, consume the character
                    terminal.reader().read(1);
                    cancelForegroundTask(out);
                    break;
                } else if (ch >= 0) {
                    // User started typing — auto-background the task.
                    // The character stays in NonBlockingReader's buffer
                    // and will be picked up by readLine() in the main loop.
                    autoBackground(out);
                    break;
                }
                // ch == -2 (READ_EXPIRED): no input, continue polling
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            log.debug("I/O error during foreground wait: {}", e.getMessage());
        } finally {
            terminal.setAttributes(savedAttrs);
        }
    }

    /**
     * Fallback polling without keypress detection (used when terminal is unavailable).
     */
    private void simplePollForeground(PrintWriter out, LineReader reader) {
        while (taskManager.hasForegroundTask()) {
            try {
                var permReq = permissionBridge.pollPending(50, TimeUnit.MILLISECONDS);
                if (permReq != null) {
                    handlePermissionFromBridge(out, reader, permReq);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Automatically sends the foreground task to background when the user starts typing.
     * Called from {@link #waitForForeground} when a keypress is detected.
     */
    private void autoBackground(PrintWriter out) {
        var fgHandle = taskManager.foregroundTask();
        if (fgHandle == null || !fgHandle.isRunning()) return;

        var oldSink = fgHandle.swapOutputSink(new BackgroundOutputBuffer());
        if (oldSink instanceof ForegroundOutputSink fgSink) {
            fgSink.stopSpinner();
        }
        taskManager.clearForeground();
        activeForegroundSink = null;

        out.println();
        out.printf("%sTask #%s auto-backgrounded. Result will appear when ready.%s%n",
                MUTED, fgHandle.taskId(), RESET);
        out.flush();
    }

    /**
     * Renders the token usage summary after a task completes.
     */
    private void renderTaskCompletion(PrintWriter out, TaskHandle handle) {
        JsonNode message = handle.result();
        if (message == null) return;

        JsonNode result = message.get("result");
        if (result != null && result.has("usage")) {
            var usage = result.get("usage");
            int turnIn = usage.path("inputTokens").asInt(0);
            int turnOut = usage.path("outputTokens").asInt(0);
            totalInputTokens += turnIn;
            totalOutputTokens += turnOut;
            latestInputTokens = turnIn;

            long elapsedMs = (System.nanoTime() - promptStartNanos) / 1_000_000;
            String elapsed = elapsedMs >= 1000
                    ? String.format("%.1fs", elapsedMs / 1000.0)
                    : elapsedMs + "ms";

            out.println();
            out.printf("%s%s  %d in / %d out  %s%s%n",
                    MUTED, elapsed, turnIn, turnOut,
                    sessionInfo.contextWindowTokens() > 0
                            ? "context " + formatTokenCount(turnIn) + "/"
                              + formatTokenCount(sessionInfo.contextWindowTokens())
                            : "",
                    RESET);
            out.flush();
        }
    }

    // -- Permission handling (from bridge) -----------------------------------

    /**
     * Handles a permission request routed through the bridge.
     * Prompts the user and submits the answer back.
     */
    private void handlePermissionFromBridge(PrintWriter out, LineReader reader,
                                            PermissionBridge.PermissionRequest req) {
        int boxWidth = 50;

        out.println();
        out.println(PERMISSION + " " + BOX_LIGHT_TOP_LEFT + BOX_LIGHT_HORIZONTAL
                + " Permission Required " + hlineLight(boxWidth - 22) + RESET);
        out.println(PERMISSION + " " + BOX_LIGHT_VERTICAL + RESET + " " + req.description());
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
        } catch (UserInterruptException | EndOfFileException e) {
            approved = false;
        }

        out.println(PERMISSION + " " + BOX_LIGHT_BOTTOM_LEFT + hlineLight(boxWidth) + RESET);

        if (approved) {
            out.printf("%s%s Approved%s%s%n", APPROVED, CHECKMARK,
                    remember ? " (always)" : "", RESET);
        } else {
            out.printf("%sDenied%s%n", DENIED, RESET);
        }
        out.flush();

        permissionBridge.submitAnswer(req.requestId(),
                new PermissionBridge.PermissionAnswer(approved, remember));
        permissionInterruptRequested.set(false);
        redrawStatusPanelBelowPrompt(reader);
    }

    /**
     * Drains any pending permission requests (non-blocking).
     */
    private void drainPermissions(PrintWriter out, LineReader reader) {
        while (permissionBridge.hasPending()) {
            try {
                var req = permissionBridge.pollPending(0, TimeUnit.MILLISECONDS);
                if (req != null) {
                    handlePermissionFromBridge(out, reader, req);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    // -- Ctrl+C cancellation ------------------------------------------------

    private void cancelForegroundTask(PrintWriter out) {
        var sink = activeForegroundSink;
        if (sink != null) {
            sink.stopSpinner();
        }
        taskManager.cancelForeground();
        out.println("\n[Cancelled]");
        out.flush();
    }

    // -- Background task auto-push (via printAbove) -------------------------

    /**
     * Called from the task's virtual thread when it completes.
     * Uses JLine's thread-safe {@code printAbove()} to display results
     * above the current prompt without waiting for user input.
     */
    private void pushBackgroundCompletion(TaskHandle handle, LineReader reader) {
        // Skip foreground tasks — they're handled by waitForForeground()
        if (handle.taskId().equals(taskManager.foregroundTaskId())) return;
        // Only auto-push once
        if (!handle.markNotified()) return;

        try {
            var sb = new StringBuilder();

            // Header
            String stateColor = switch (handle.state()) {
                case COMPLETED -> SUCCESS;
                case FAILED -> ERROR;
                case CANCELLED -> WARNING;
                case RUNNING -> "";
            };
            sb.append(MUTED).append("--- bg task #").append(handle.taskId()).append(" ")
              .append(stateColor).append(handle.state().name().toLowerCase()).append(RESET)
              .append(MUTED).append(" ---").append(RESET).append("\n");

            // Extract content: prefer buffered text, fall back to final result.response
            var sink = handle.outputSink();
            String textContent = null;
            if (sink instanceof BackgroundOutputBuffer bgBuffer && bgBuffer.size() > 0) {
                textContent = bgBuffer.extractTextContent();
            }
            if (textContent == null || textContent.isBlank()) {
                var message = handle.result();
                if (message != null) {
                    textContent = message.path("result").path("response").asText("");
                }
            }

            // Render through markdown
            if (textContent != null && !textContent.isBlank()) {
                var sw = new StringWriter();
                var pw = new PrintWriter(sw);
                new TerminalMarkdownRenderer().render(textContent, pw);
                pw.flush();
                sb.append(sw);
            }

            String output = sb.toString();
            if (!output.isBlank()) {
                // printAbove is thread-safe — displays above current prompt, redraws prompt
                reader.printAbove(AttributedString.fromAnsi(output));
                redrawStatusPanelBelowPrompt(reader);
            }
        } catch (Exception e) {
            log.debug("Failed to push background task output: {}", e.getMessage());
        }
    }

    // -- Background task notifications (fallback at prompt) -------------------

    private void notifyCompletedBackgroundTasks(PrintWriter out) {
        for (var handle : taskManager.list()) {
            if (!handle.isTerminal()) continue;
            if (handle.taskId().equals(taskManager.foregroundTaskId())) continue;
            if (!handle.markNotified()) continue;  // already shown — skip

            String stateLabel = switch (handle.state()) {
                case COMPLETED -> SUCCESS + "completed" + RESET;
                case FAILED -> ERROR + "failed" + RESET;
                case CANCELLED -> WARNING + "cancelled" + RESET;
                case RUNNING -> "";
            };

            out.println();
            out.printf("%s--- bg task #%s %s ---%s%n", MUTED, handle.taskId(), stateLabel, RESET);

            // Render buffered text content (safe extraction — no spinner side effects)
            var sink = handle.outputSink();
            String textContent = null;
            if (sink instanceof BackgroundOutputBuffer bgBuffer && bgBuffer.size() > 0) {
                textContent = bgBuffer.extractTextContent();
            }
            if (textContent == null || textContent.isBlank()) {
                var message = handle.result();
                if (message != null) {
                    textContent = message.path("result").path("response").asText("");
                }
            }
            if (textContent != null && !textContent.isBlank()) {
                markdownRenderer.render(textContent, out);
                out.flush();
            }

            // Show completion summary (token usage, elapsed time)
            renderTaskCompletion(out, handle);
        }
        out.flush();
    }

    // -- Banner rendering ----------------------------------------------------

    private void renderBanner(PrintWriter out, int termWidth) {
        int innerWidth = Math.max(40, Math.min(termWidth - 4, 60));

        String titleLine = "  AceClaw  v" + sessionInfo.version();
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

    // -- Status line ---------------------------------------------------------

    private String buildStatusString() {
        var sb = new StringBuilder();

        sb.append(MUTED).append("\u2500").append(RESET).append(" ");
        sb.append(INFO).append(BOLD).append(effectiveModel).append(RESET);

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

        // Show running task count if > 0
        int running = taskManager.runningCount();
        if (running > 0) {
            sb.append(MUTED).append(" \u2502 ").append(RESET);
            sb.append(INFO).append(running).append(" task")
              .append(running > 1 ? "s" : "").append(RESET);
        }

        int pendingPermissions = permissionBridge.pendingCount();
        if (pendingPermissions > 0) {
            sb.append(MUTED).append(" \u2502 ").append(RESET);
            sb.append(WARNING).append("\uD83D\uDD10 ")
              .append(pendingPermissions).append(" permission")
              .append(pendingPermissions > 1 ? "s" : "").append(RESET);
        }

        sb.append(MUTED).append(" \u2502 ").append(RESET);
        sb.append(MUTED).append(LocalTime.now().format(TIME_FMT)).append(RESET);

        return sb.toString();
    }

    /**
     * Renders the prompt status panel below the current cursor.
     *
     * <p>The first line is the compact global status; following lines show
     * currently running tasks so concurrent background activity is visible.
     *
     * @param restoreCursorByUp if true, moves cursor back up by rendered line count
     */
    private void renderStatusPanel(PrintWriter out, boolean restoreCursorByUp) {
        var lines = buildStatusPanelLines();
        int currentLineCount = lines.size();
        int renderLineCount = Math.max(currentLineCount, previousStatusLineCount);
        if (renderLineCount == 0) return;
        int terminalWidth = activeTerminal != null ? activeTerminal.getWidth() : 120;
        int maxWidth = Math.max(20, terminalWidth - 1);

        for (int i = 0; i < renderLineCount; i++) {
            out.print("\n\r\033[K");
            if (i < currentLineCount) {
                out.print(clampStatusLine(lines.get(i), maxWidth));
            }
        }

        if (restoreCursorByUp) {
            out.print("\033[" + renderLineCount + "A\r");
        }
        previousStatusLineCount = currentLineCount;
    }

    /**
     * Re-renders the prompt status panel after asynchronous printAbove output.
     *
     * <p>Without this, JLine redraws only the prompt line after printAbove, and
     * our custom status panel (rendered below the prompt) disappears until the
     * next explicit prompt redraw.
     */
    private void redrawStatusPanelBelowPrompt(LineReader reader) {
        var terminal = activeTerminal;
        if (terminal == null) return;
        if (reader == null || reader != activeReader) return;
        if (taskManager.hasForegroundTask()) return;

        synchronized (uiRenderLock) {
            PrintWriter out = terminal.writer();
            renderStatusPanel(out, true);
            out.flush();
        }
        forcePromptRedisplay(reader);
    }

    /**
     * Forces JLine to redraw the editable prompt line so cursor returns
     * to prompt+buffer position (instead of column 0) after async UI writes.
     */
    private void forcePromptRedisplay(LineReader reader) {
        try {
            reader.callWidget(LineReader.REDRAW_LINE);
        } catch (Exception e) {
            log.debug("Failed to call REDRAW_LINE: {}", e.getMessage());
        }
        try {
            reader.callWidget(LineReader.REDISPLAY);
        } catch (Exception e) {
            log.debug("Failed to call REDISPLAY: {}", e.getMessage());
        }
    }

    /**
     * Called when a background/side task requests permission.
     *
     * <p>Shows an immediate non-blocking notice above the prompt so the user
     * knows why a task appears stuck, then refreshes the status panel which
     * includes pending permission entries.
     */
    private void onPermissionRequested(PermissionBridge.PermissionRequest request, LineReader reader) {
        if (reader == null) return;
        try {
            String note = WARNING + "[Permission required] " + RESET
                    + "task #" + request.taskId() + " \u2192 "
                    + fitWidth(request.description(), 90)
                    + MUTED + "  (opening popup...)" + RESET;
            reader.printAbove(AttributedString.fromAnsi(note));
        } catch (Exception e) {
            log.debug("Failed to print permission notice: {}", e.getMessage());
        }
        interruptPromptForPermissionPopup();
        redrawStatusPanelBelowPrompt(reader);
    }

    /**
     * Interrupts blocking prompt input so permission approval can open immediately.
     */
    private void interruptPromptForPermissionPopup() {
        if (!readingPrompt) return;
        if (taskManager.hasForegroundTask()) return;
        if (!permissionInterruptRequested.compareAndSet(false, true)) return;

        var terminal = activeTerminal;
        if (terminal == null) return;
        try {
            terminal.raise(Terminal.Signal.INT);
        } catch (Exception e) {
            permissionInterruptRequested.set(false);
            log.debug("Failed to interrupt prompt for permission popup: {}", e.getMessage());
        }
    }

    /**
     * Builds the status panel lines shown below the prompt.
     */
    private List<String> buildStatusPanelLines() {
        var lines = new ArrayList<String>();
        lines.add(buildStatusString());

        var runningTasks = taskManager.list().stream()
                .filter(TaskHandle::isRunning)
                .toList();
        if (!runningTasks.isEmpty()) {
            final int maxVisible = 4;
            int from = Math.max(0, runningTasks.size() - maxVisible);
            var visible = runningTasks.subList(from, runningTasks.size());

            String fgId = taskManager.foregroundTaskId();
            for (var task : visible) {
                String elapsed = formatDuration(Duration.between(task.startedAt(), Instant.now()));
                String prefix = task.taskId().equals(fgId) ? "[fg] " : "";
                String summary = fitWidth(task.promptSummary(), 52);

                lines.add(MUTED + "  \u2514 " + RESET
                        + WARNING + "\u23F3" + RESET + " "
                        + INFO + "#" + task.taskId() + RESET + " "
                        + prefix + summary
                        + MUTED + "  " + elapsed + RESET);
            }

            int hidden = runningTasks.size() - visible.size();
            if (hidden > 0) {
                lines.add(MUTED + "    ... +" + hidden + " more running task(s)" + RESET);
            }
        }

        var pending = permissionBridge.pendingSnapshot();
        if (!pending.isEmpty()) {
            int maxPermVisible = 2;
            for (int i = 0; i < Math.min(maxPermVisible, pending.size()); i++) {
                var req = pending.get(i);
                String detail = fitWidth(req.description(), 58);
                lines.add(MUTED + "  \u2514 " + RESET
                        + WARNING + "\uD83D\uDD10" + RESET + " "
                        + INFO + "#" + req.taskId() + RESET + " "
                        + detail);
            }
            if (pending.size() > maxPermVisible) {
                lines.add(MUTED + "    ... +" + (pending.size() - maxPermVisible)
                        + " more permission request(s)" + RESET);
            }
        }

        return lines;
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
     * Handles slash commands. Returns true if the REPL should exit.
     */
    boolean handleSlashCommand(PrintWriter out, String input, LineReader reader) {
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
                out.println(INFO + "  /tasks" + RESET + "    List all tasks with status");
                out.println(INFO + "  /bg" + RESET + "       Send foreground task to background");
                out.println(INFO + "  /fg" + RESET + "       Bring background task to foreground (/fg [id])");
                out.println(INFO + "  /cancel" + RESET + "   Cancel a task (/cancel [id])");
                out.println(INFO + "  /exit" + RESET + "     Exit the REPL");
                out.println();
                out.flush();
            }

            case "/clear" -> {
                out.print("\033[2J\033[H");
                out.flush();
            }

            case "/compact" -> {
                out.println(MUTED + "Requesting context compaction..." + RESET);
                out.flush();
                sendRpcNotification("session.compact");
            }

            case "/model" -> handleModelCommand(out, arg);

            case "/tools" -> handleToolsCommand(out);

            case "/status" -> {
                out.println();
                out.println(BOLD + "Session Status" + RESET);
                out.printf("  %sModel:%s       %s%n", MUTED, RESET, effectiveModel);
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
                out.printf("  %sTasks:%s       %d running%n", MUTED, RESET,
                        taskManager.runningCount());
                out.println();
                out.flush();
            }

            case "/tasks" -> handleTasksCommand(out);

            case "/bg" -> handleBgCommand(out);

            case "/fg" -> handleFgCommand(out, reader, arg);

            case "/cancel" -> handleCancelCommand(out, arg);

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

    // -- /tasks command ------------------------------------------------------

    private void handleTasksCommand(PrintWriter out) {
        var tasks = taskManager.list();
        if (tasks.isEmpty()) {
            out.println(MUTED + "No tasks." + RESET);
            out.flush();
            return;
        }

        out.println();
        out.println(BOLD + "Tasks:" + RESET);
        String fgId = taskManager.foregroundTaskId();
        for (var handle : tasks) {
            String stateColor = switch (handle.state()) {
                case RUNNING -> INFO;
                case COMPLETED -> SUCCESS;
                case FAILED -> ERROR;
                case CANCELLED -> WARNING;
            };
            String stateLabel = handle.state().name().toLowerCase();
            boolean isFg = handle.taskId().equals(fgId);
            String elapsed = formatDuration(Duration.between(handle.startedAt(), Instant.now()));

            out.printf("  %s#%s%s %s%-10s%s %s%s%s  %s%s%s%n",
                    BOLD, handle.taskId(), RESET,
                    stateColor, stateLabel, RESET,
                    isFg ? BOLD + "[fg] " : "",
                    handle.promptSummary(),
                    RESET,
                    MUTED, elapsed, RESET);
        }
        out.println();
        out.flush();
    }

    // -- /bg command ---------------------------------------------------------

    private void handleBgCommand(PrintWriter out) {
        var fgHandle = taskManager.foregroundTask();
        if (fgHandle == null || !fgHandle.isRunning()) {
            out.println(WARNING + "No foreground task to background." + RESET);
            out.flush();
            return;
        }

        // Atomically swap the output sink to a background buffer.
        // The TaskStreamReader reads from handle.outputSink(), so subsequent
        // events will be buffered silently instead of rendering to terminal.
        var oldSink = fgHandle.swapOutputSink(new BackgroundOutputBuffer());
        if (oldSink instanceof ForegroundOutputSink fgSink) {
            fgSink.stopSpinner();
        }
        taskManager.clearForeground();
        activeForegroundSink = null;

        out.printf("%sTask #%s sent to background.%s%n", MUTED, fgHandle.taskId(), RESET);
        out.flush();
    }

    // -- /fg command ---------------------------------------------------------

    private void handleFgCommand(PrintWriter out, LineReader reader, String arg) {
        if (taskManager.hasForegroundTask()) {
            out.println(WARNING + "A task is already in the foreground. Use /bg first." + RESET);
            out.flush();
            return;
        }

        TaskHandle target = null;
        if (!arg.isEmpty()) {
            target = taskManager.get(arg);
            if (target == null) {
                out.println(WARNING + "Task #" + arg + " not found." + RESET);
                out.flush();
                return;
            }
        } else {
            // Find the most recent running background task
            var tasks = taskManager.list();
            for (int i = tasks.size() - 1; i >= 0; i--) {
                if (tasks.get(i).isRunning()) {
                    target = tasks.get(i);
                    break;
                }
            }
            if (target == null) {
                out.println(WARNING + "No running background tasks." + RESET);
                out.flush();
                return;
            }
        }

        if (!target.isRunning()) {
            out.println(WARNING + "Task #" + target.taskId() + " is not running ("
                    + target.state().name().toLowerCase() + ")." + RESET);
            out.flush();
            return;
        }

        out.printf("%sBringing task #%s to foreground...%s%n", MUTED, target.taskId(), RESET);
        out.flush();

        // Create new foreground sink and swap it in atomically
        var fgSink = new ForegroundOutputSink(out, markdownRenderer);
        var oldSink = target.swapOutputSink(fgSink);
        activeForegroundSink = fgSink;
        taskManager.setForeground(target.taskId());

        // Replay buffered events if the task was backgrounded
        if (oldSink instanceof BackgroundOutputBuffer bgBuffer) {
            bgBuffer.replay(fgSink);
        }

        waitForForeground(out, reader);
        renderTaskCompletion(out, target);
        taskManager.clearForeground();
        activeForegroundSink = null;
    }

    // -- /cancel command -----------------------------------------------------

    private void handleCancelCommand(PrintWriter out, String arg) {
        if (!arg.isEmpty()) {
            var handle = taskManager.get(arg);
            if (handle == null) {
                out.println(WARNING + "Task #" + arg + " not found." + RESET);
            } else if (!handle.isRunning()) {
                out.println(WARNING + "Task #" + arg + " is not running." + RESET);
            } else {
                taskManager.cancel(arg);
                out.println(MUTED + "Cancelling task #" + arg + "..." + RESET);
            }
        } else {
            // Cancel foreground task
            if (taskManager.hasForegroundTask()) {
                cancelForegroundTask(out);
            } else {
                out.println(WARNING + "No foreground task to cancel. Use /cancel <id>." + RESET);
            }
        }
        out.flush();
    }

    // -- /tools command (extracted from old handleSlashCommand) ---------------

    private void handleToolsCommand(PrintWriter out) {
        out.println(MUTED + "Requesting tool list..." + RESET);
        out.flush();
        try {
            long id = client.nextRequestId();
            var request = client.objectMapper().createObjectNode();
            request.put("jsonrpc", "2.0");
            request.put("method", "tools.list");
            request.put("id", id);
            client.writeLine(client.objectMapper().writeValueAsString(request));

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

    // -- /model command (unchanged from original) ----------------------------

    private void handleModelCommand(PrintWriter out, String arg) {
        if (client == null) {
            out.println(WARNING + "Not connected to daemon." + RESET);
            out.flush();
            return;
        }
        try {
            if (!arg.isEmpty()) {
                switchModel(out, arg);
                return;
            }

            long id = client.nextRequestId();
            var request = client.objectMapper().createObjectNode();
            request.put("jsonrpc", "2.0");
            request.put("method", "model.list");
            var listParams = client.objectMapper().createObjectNode();
            listParams.put("sessionId", sessionId);
            request.set("params", listParams);
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
            if (result == null || !result.isObject()) {
                out.println(WARNING + "Invalid model.list response from daemon" + RESET);
                out.flush();
                return;
            }
            String currentModel = result.path("currentModel").asText("");
            String provider = result.path("provider").asText("");
            var modelsNode = result.get("models");

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

            var lineReader = activeReader;
            if (lineReader == null) return;

            String selection;
            try {
                selection = lineReader.readLine("");
            } catch (UserInterruptException | EndOfFileException e) {
                out.println();
                return;
            }

            if (selection == null || selection.isBlank()) {
                out.println(MUTED + "  Cancelled." + RESET);
                out.flush();
                return;
            }

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

    private void switchModel(PrintWriter out, String modelId) {
        try {
            long id = client.nextRequestId();
            var request = client.objectMapper().createObjectNode();
            request.put("jsonrpc", "2.0");
            request.put("method", "model.switch");
            var params = client.objectMapper().createObjectNode();
            params.put("model", modelId);
            params.put("sessionId", sessionId);
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
                effectiveModel = modelId;
                out.println(SUCCESS + "  \u2713 Switched to " + BOLD + modelId + RESET);
            }
            out.flush();
        } catch (IOException e) {
            out.println(ERROR + "Failed to switch model: " + e.getMessage() + RESET);
            out.flush();
        }
    }

    // -- Helpers -------------------------------------------------------------

    private void sendRpcNotification(String method) {
        try {
            var params = client.objectMapper().createObjectNode();
            params.put("sessionId", sessionId);
            client.sendNotification(method, params);
        } catch (IOException e) {
            log.warn("Failed to send {} notification: {}", method, e.getMessage());
        }
    }

    private static String formatDuration(Duration d) {
        long secs = d.getSeconds();
        if (secs < 60) return secs + "s";
        long mins = secs / 60;
        if (mins < 60) return mins + "m " + (secs % 60) + "s";
        long hours = mins / 60;
        return hours + "h " + (mins % 60) + "m";
    }

    /**
     * Ensures status-panel lines never soft-wrap, otherwise cursor restoration drifts
     * and typing can land on the status row instead of the prompt row.
     */
    private static String clampStatusLine(String line, int maxWidth) {
        if (line == null || line.isEmpty()) return "";
        if (maxWidth <= 0) return "";
        String plain = ANSI_CSI_PATTERN.matcher(line).replaceAll("");
        if (displayWidth(plain) <= maxWidth) {
            return line;
        }
        return fitWidth(plain, maxWidth);
    }
}

package dev.aceclaw.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.InfoCmp;
import org.jline.utils.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
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
    private static final Pattern CONTINUE_INTENT_PATTERN =
            Pattern.compile("^(?i)(continue|resume)\\b(?:\\s*(.*))?$");
    private static final Duration TASK_STALLED_AFTER = Duration.ofSeconds(45);
    private static final Duration TASK_TIMEOUT_AFTER = Duration.ofSeconds(180);
    private static final Duration LEARNING_STATUS_REFRESH = Duration.ofSeconds(5);
    private static final Duration CRON_STATUS_REFRESH = Duration.ofSeconds(5);
    private static final boolean PROMPT_STATUS_PANEL_ENABLED =
            Boolean.parseBoolean(System.getenv().getOrDefault("ACECLAW_PROMPT_STATUS_PANEL", "true"));
    private static final boolean CRON_STATUS_EXPANDED =
            Boolean.parseBoolean(System.getenv().getOrDefault("ACECLAW_CRON_STATUS_EXPANDED", "false"));
    private static final int MAX_RESUME_HINT_LEN = 200;
    private static final String CL_METRICS_DIR = ".aceclaw/metrics/continuous-learning";
    private static final String CL_REPLAY_REPORT = "replay-latest.json";
    private static final String CL_RELEASE_STATE = "skill-release-state.json";
    private static final String CL_CANDIDATES = ".aceclaw/memory/candidates.jsonl";
    private static final Path HOME_CANDIDATES_PATH = Path.of(
            System.getProperty("user.home"), ".aceclaw", "memory", "candidates.jsonl");

    private final DaemonClient client;
    private final String sessionId;
    private final SessionInfo sessionInfo;
    private final TerminalMarkdownRenderer markdownRenderer;
    private final TaskManager taskManager;
    private final PermissionBridge permissionBridge;
    private final ObjectMapper statusMapper;
    private final ConcurrentLinkedQueue<UiEvent> uiEvents = new ConcurrentLinkedQueue<>();
    private final Deque<UiNotice> uiNoticeBuffer = new ArrayDeque<>();
    private final List<String> pendingPrintAbove = new ArrayList<>();
    /** Fixed status panel height so JLine's Status widget never resizes its scroll region. */
    private static final int FIXED_STATUS_LINE_COUNT = 10;
    private final Object uiRenderLock = new Object();
    private final AtomicBoolean permissionInterruptRequested = new AtomicBoolean(false);
    private final AtomicBoolean uiRenderRequested = new AtomicBoolean(true);

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
    /** Cached continuous-learning status line; refreshed periodically. */
    private volatile String cachedLearningStatusLine;
    private volatile Instant cachedLearningStatusAt = Instant.EPOCH;
    /** Cached cron status snapshot from daemon RPC. */
    private volatile CronStatusSnapshot cachedCronStatus;
    private volatile Instant cachedCronStatusAt = Instant.EPOCH;
    /** Last non-trivial user task prompt, used by /continue fallback. */
    private volatile String lastTaskPrompt;
    private final ResumeCheckpointStore resumeCheckpointStore;
    private final String clientInstanceId;
    private final String workspaceHash;

    /** Current foreground output sink (for Ctrl+C spinner cleanup). */
    private volatile ForegroundOutputSink activeForegroundSink;
    /** JLine status panel renderer. */
    private volatile Status promptStatus;
    /** Last consumed scheduler event sequence from daemon. */
    private volatile long schedulerEventSeq;

    private sealed interface UiEvent permits UiNoticeEvent, UiPrintAboveEvent {}

    private record UiNoticeEvent(String ansiText) implements UiEvent {}
    private record UiPrintAboveEvent(String ansiText) implements UiEvent {}
    private record UiNotice(Instant at, String text) {}

    private record CronJobStatus(
            String id,
            String name,
            String kind,
            boolean enabled,
            Instant nextFireAt,
            Instant lastRunAt,
            String description
    ) {}

    private record CronStatusSnapshot(
            boolean schedulerRunning,
            boolean jobRunning,
            String currentJobId,
            Instant currentJobStartedAt,
            List<CronJobStatus> jobs
    ) {}

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

    private enum TaskRuntimeState {
        ACTIVE,
        WAITING_PERMISSION,
        STALLED,
        TIMEOUT
    }

    private record TaskRuntimeInfo(
            TaskRuntimeState state,
            String shortState,
            String label,
            String color,
            String icon
    ) {}

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
        this.statusMapper = new ObjectMapper();
        this.clientInstanceId = resolveClientInstanceId();
        this.workspaceHash = hashWorkspace(sessionInfo.project());
        this.resumeCheckpointStore = new ResumeCheckpointStore(Path.of(System.getProperty("user.home"), ".aceclaw"));
    }

    /**
     * Runs the interactive REPL loop. Blocks until the user exits.
     */
    public void run() {
        var stopStatusTicker = new AtomicBoolean(false);
        var stopUiRenderer = new AtomicBoolean(false);
        Thread statusTicker = null;
        Thread uiRenderer = null;
        DaemonConnection schedulerEventConnection = null;
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
            if (PROMPT_STATUS_PANEL_ENABLED) {
                promptStatus = Status.getStatus(terminal);
            }

            PrintWriter out = terminal.writer();

            try {
                schedulerEventConnection = client.openTaskConnection();
                schedulerEventSeq = bootstrapSchedulerEventSeq(schedulerEventConnection);
            } catch (Exception e) {
                schedulerEventConnection = null;
                log.debug("Failed to initialize scheduler event polling: {}", e.getMessage());
            }
            statusTicker = startStatusTicker(stopStatusTicker, reader, schedulerEventConnection);
            uiRenderer = startUiRenderer(stopUiRenderer, reader);

            // Register callback to auto-push background task output above prompt
            taskManager.setOnTaskComplete(handle -> {
                recordResumeCheckpointOnComplete(handle);
                pushBackgroundCompletion(handle, reader);
            });
            permissionBridge.setRequestListener(req -> onPermissionRequested(req, reader));

            // Render startup banner
            renderBanner(out, terminal.getWidth());

            // Override Ctrl+L: clear screen + redraw status bar below prompt
            reader.getWidgets().put("aceclaw-clear-screen", () -> {
                reader.callWidget(LineReader.CLEAR_SCREEN);
                requestUiRender();
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
                    readingPrompt = true;
                    requestUiRender();
                    try {
                        line = reader.readLine(PROMPT_STR);
                    } finally {
                        readingPrompt = false;
                        ensureCursorVisible();
                    }
                } catch (UserInterruptException e) {
                    ensureCursorVisible();
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
                    ensureCursorVisible();
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
                    var continueArg = parseContinueIntent(trimmed);
                    if (continueArg != null) {
                        handleContinueCommand(out, reader, continueArg);
                    } else {
                        submitAndWait(out, reader, trimmed);
                    }
                }
            }

        } catch (IOException e) {
            log.error("Terminal error: {}", e.getMessage(), e);
            System.err.println("Terminal error: " + e.getMessage());
        } finally {
            stopStatusTicker.set(true);
            stopUiRenderer.set(true);
            if (statusTicker != null) {
                statusTicker.interrupt();
            }
            if (uiRenderer != null) {
                uiRenderer.interrupt();
            }
            if (schedulerEventConnection != null) {
                schedulerEventConnection.close();
            }
            if (promptStatus != null) {
                try {
                    promptStatus.update(List.of());
                } catch (Exception e) {
                    log.debug("Failed to clear prompt status: {}", e.getMessage());
                }
            }
        }
    }

    private Thread startStatusTicker(AtomicBoolean stopFlag, LineReader reader, DaemonConnection schedulerEventConn) {
        return Thread.ofVirtual()
                .name("aceclaw-status-ticker")
                .start(() -> {
                    while (!stopFlag.get()) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }

                        pollAndRenderSchedulerEvents(schedulerEventConn, reader);
                        pollCronStatus(schedulerEventConn);
                    }
                });
    }

    private Thread startUiRenderer(AtomicBoolean stopFlag, LineReader reader) {
        return Thread.ofVirtual()
                .name("aceclaw-ui-renderer")
                .start(() -> {
                    long lastClockSecond = -1L;
                    while (!stopFlag.get()) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }

                        boolean hadUiEvent = drainUiEventsIntoState();

                        // Flush print-above texts (full cron output) above the prompt.
                        // When printAbove fires, skip status render for this tick to avoid
                        // conflicting scroll region adjustments (printAbove temporarily
                        // modifies the scroll region; immediate Status.update() corrupts it).
                        boolean didPrintAbove = false;
                        if (!pendingPrintAbove.isEmpty() && readingPrompt) {
                            for (String text : pendingPrintAbove) {
                                try {
                                    reader.printAbove(AttributedString.fromAnsi(text));
                                } catch (Exception e) {
                                    log.debug("Failed to printAbove: {}", e.getMessage());
                                }
                            }
                            pendingPrintAbove.clear();
                            didPrintAbove = true;
                        }

                        long currentSecond = System.currentTimeMillis() / 1000L;
                        boolean clockTick = currentSecond != lastClockSecond;
                        if (clockTick) {
                            lastClockSecond = currentSecond;
                        }

                        boolean shouldRender = (hadUiEvent || uiRenderRequested.getAndSet(false) || clockTick)
                                && readingPrompt
                                && !taskManager.hasForegroundTask()
                                && PROMPT_STATUS_PANEL_ENABLED
                                && !didPrintAbove;
                        if (!shouldRender) {
                            continue;
                        }
                        renderStatusFrame(reader);
                    }
                });
    }

    private long bootstrapSchedulerEventSeq(DaemonConnection conn)
            throws IOException, DaemonClient.DaemonClientException {
        var params = client.objectMapper().createObjectNode();
        params.put("afterSeq", Long.MAX_VALUE);
        params.put("limit", 1);
        JsonNode result = conn.sendRequest("scheduler.events.poll", params);
        return Math.max(0L, result.path("nextSeq").asLong(0L));
    }

    private void pollAndRenderSchedulerEvents(DaemonConnection conn, LineReader reader) {
        if (conn == null) return;
        try {
            var params = client.objectMapper().createObjectNode();
            params.put("afterSeq", schedulerEventSeq);
            params.put("limit", 20);
            JsonNode result = conn.sendRequest("scheduler.events.poll", params);
            schedulerEventSeq = Math.max(schedulerEventSeq, result.path("nextSeq").asLong(schedulerEventSeq));
            JsonNode events = result.path("events");
            if (!events.isArray() || events.isEmpty()) {
                return;
            }

            for (JsonNode event : events) {
                String type = event.path("type").asText("");
                if ("completed".equals(type)) {
                    // Full multi-line output printed above prompt
                    String fullOutput = renderSchedulerEventNote(event);
                    if (fullOutput != null) {
                        enqueueUiPrintAbove(fullOutput);
                    }
                    // Short notice for status bar
                    String jobId = event.path("jobId").asText("unknown");
                    long durationMs = event.path("durationMs").asLong(-1L);
                    String shortNote = SUCCESS + "cron completed" + RESET + " [" + jobId + "]"
                            + (durationMs > 0 ? " in " + durationMs + "ms" : "");
                    enqueueUiNotice(shortNote);
                } else {
                    String note = renderSchedulerEventNote(event);
                    if (note != null) {
                        enqueueUiNotice(note);
                    }
                }
            }
            requestUiRender();
        } catch (Exception e) {
            log.debug("Failed to poll scheduler events: {}", e.getMessage());
        }
    }

    private void pollCronStatus(DaemonConnection conn) {
        if (conn == null) return;
        Instant now = Instant.now();
        if (cachedCronStatus != null
                && Duration.between(cachedCronStatusAt, now).compareTo(CRON_STATUS_REFRESH) < 0) {
            return;
        }
        try {
            JsonNode result = conn.sendRequest("scheduler.cron.status", client.objectMapper().createObjectNode());
            boolean schedulerRunning = result.path("schedulerRunning").asBoolean(false);
            boolean jobRunning = result.path("jobRunning").asBoolean(false);
            String currentJobId = result.path("currentJobId").asText("");
            if (currentJobId.isBlank()) currentJobId = null;
            Instant currentJobStartedAt = parseInstant(result.path("currentJobStartedAt").asText(""));
            var jobs = new ArrayList<CronJobStatus>();
            JsonNode arr = result.path("jobs");
            if (arr.isArray()) {
                for (JsonNode j : arr) {
                    jobs.add(new CronJobStatus(
                            j.path("id").asText(""),
                            j.path("name").asText(""),
                            j.path("kind").asText("scheduled"),
                            j.path("enabled").asBoolean(true),
                            parseInstant(j.path("nextFireAt").asText("")),
                            parseInstant(j.path("lastRunAt").asText("")),
                            j.path("description").asText("")
                    ));
                }
            }
            cachedCronStatus = new CronStatusSnapshot(
                    schedulerRunning, jobRunning, currentJobId, currentJobStartedAt, List.copyOf(jobs));
            cachedCronStatusAt = now;
            requestUiRender();
        } catch (Exception e) {
            log.debug("Failed to poll scheduler.cron.status: {}", e.getMessage());
        }
    }

    private String renderSchedulerEventNote(JsonNode event) {
        String type = event.path("type").asText("");
        String jobId = event.path("jobId").asText("unknown");
        return switch (type) {
            case "triggered" -> {
                String ts = event.path("timestamp").asText("");
                yield MUTED + "[cron running] " + jobId
                        + (ts.isBlank() ? "" : " @ " + ts) + RESET;
            }
            case "completed" -> {
                var sb = new StringBuilder();
                sb.append(MUTED).append("--- ").append(SUCCESS).append("cron completed")
                        .append(RESET).append(MUTED).append(" [").append(jobId).append("] ---")
                        .append(RESET).append("\n");

                String summary = sanitizeCronSummary(event.path("summary").asText(""));
                if (!summary.isBlank()) {
                    var sw = new StringWriter();
                    var pw = new PrintWriter(sw);
                    new TerminalMarkdownRenderer().render(summary, pw);
                    pw.flush();
                    sb.append(sw);
                } else {
                    long durationMs = event.path("durationMs").asLong(-1L);
                    if (durationMs > 0) {
                        sb.append(MUTED).append("(completed in ").append(durationMs).append("ms)").append(RESET).append("\n");
                    }
                    sb.append(WARNING)
                            .append("No textual summary returned. ")
                            .append("The job may have completed via tool calls only.")
                            .append(RESET)
                            .append("\n");
                }
                yield sb.toString();
            }
            case "failed" -> {
                String err = event.path("error").asText("unknown error");
                yield ERROR + "[cron failed] " + RESET + jobId + " - " + err;
            }
            case "skipped" -> {
                String reason = event.path("reason").asText("");
                yield WARNING + "[cron skipped] " + RESET + jobId
                        + (reason.isBlank() ? "" : " - " + reason);
            }
            default -> null;
        };
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
            String effectiveInput = input == null ? "" : input.trim();
            if (!effectiveInput.isBlank()) {
                lastTaskPrompt = effectiveInput;
            }
            var conn = client.openTaskConnection();
            var fgSink = new ForegroundOutputSink(out, markdownRenderer);
            activeForegroundSink = fgSink;

            promptStartNanos = System.nanoTime();

            var handle = taskManager.submit(effectiveInput, conn, sessionId, fgSink, permissionBridge);
            taskManager.setForeground(handle.taskId());
            resumeCheckpointStore.recordTaskSubmitted(
                    sessionId,
                    handle.taskId(),
                    workspaceHash,
                    "cli",
                    clientInstanceId,
                    effectiveInput,
                    true
            );

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
        resumeCheckpointStore.markForeground(sessionId, fgHandle.taskId(), false);
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
        permissionBridge.consumeResolvedAnswer(req.requestId());
        var task = taskManager.get(req.taskId());
        if (task != null) {
            task.clearWaitingPermission();
        }
        permissionInterruptRequested.set(false);
        requestUiRender();
    }

    /**
     * Drains any pending permission requests (non-blocking).
     */
    private void drainPermissions(PrintWriter out, LineReader reader) {
        while (permissionBridge.hasPending()) {
            try {
                var req = permissionBridge.pollPending(0, TimeUnit.MILLISECONDS);
                if (req != null) {
                    var resolved = permissionBridge.consumeResolvedAnswer(req.requestId());
                    if (resolved != null) {
                        var task = taskManager.get(req.taskId());
                        if (task != null) {
                            task.clearWaitingPermission();
                        }
                        String note = resolved.approved()
                                ? MUTED + "[Permission auto-approved] " + RESET
                                  + "task #" + req.taskId() + " -> " + fitWidth(req.description(), 90)
                                : WARNING + "[Permission auto-denied] " + RESET
                                  + "task #" + req.taskId() + " -> " + fitWidth(req.description(), 90);
                        enqueueUiNotice(note);
                        permissionInterruptRequested.set(false);
                        requestUiRender();
                        continue;
                    }
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
                enqueueUiNotice(output);
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

        sb.append(MUTED).append("-").append(RESET).append(" ");
        sb.append(INFO).append(BOLD).append(effectiveModel).append(RESET);

        String branch = sessionInfo.gitBranch();
        if (branch != null && !branch.isBlank()) {
            sb.append(MUTED).append(" | ").append(RESET);
            sb.append(SUCCESS).append("branch=")
                    .append(fitWidth(branch, 28))
                    .append(RESET);
        }

        int ctxWindow = sessionInfo.contextWindowTokens();
        if (ctxWindow > 0 && latestInputTokens > 0) {
            sb.append(MUTED).append(" | ").append(RESET);
            long pct = latestInputTokens * 100 / ctxWindow;
            sb.append(WARNING).append(formatTokenCount(latestInputTokens))
              .append("/").append(formatTokenCount(ctxWindow))
              .append(" (").append(pct).append("%)").append(RESET);
        }

        // Show running task count if > 0
        int running = taskManager.runningCount();
        if (running > 0) {
            sb.append(MUTED).append(" | ").append(RESET);
            sb.append(INFO).append(running).append(" task")
              .append(running > 1 ? "s" : "").append(RESET);
        }

        int pendingPermissions = permissionBridge.pendingCount();
        if (pendingPermissions > 0) {
            sb.append(MUTED).append(" | ").append(RESET);
            sb.append(WARNING).append("permissions=")
              .append(pendingPermissions)
              .append(" permission")
              .append(pendingPermissions > 1 ? "s" : "").append(RESET);
        }

        sb.append(MUTED).append(" | ").append(RESET);
        sb.append(MUTED).append(LocalTime.now().format(TIME_FMT)).append(RESET);

        return sb.toString();
    }

    private void requestUiRender() {
        uiRenderRequested.set(true);
    }

    private void enqueueUiNotice(String ansiText) {
        if (ansiText == null || ansiText.isBlank()) return;
        uiEvents.add(new UiNoticeEvent(ansiText));
        requestUiRender();
    }

    private void enqueueUiPrintAbove(String ansiText) {
        if (ansiText == null || ansiText.isBlank()) return;
        uiEvents.add(new UiPrintAboveEvent(ansiText));
        requestUiRender();
    }

    private boolean drainUiEventsIntoState() {
        boolean changed = false;
        UiEvent event;
        while ((event = uiEvents.poll()) != null) {
            switch (event) {
                case UiNoticeEvent notice -> {
                    addUiNotice(notice.ansiText());
                    changed = true;
                }
                case UiPrintAboveEvent pae -> {
                    pendingPrintAbove.add(pae.ansiText());
                    changed = true;
                }
            }
        }
        return changed;
    }

    private void addUiNotice(String text) {
        String normalized = sanitizeCronSummary(text);
        if (normalized.isBlank()) return;
        String singleLine = normalized.replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        synchronized (uiRenderLock) {
            uiNoticeBuffer.addLast(new UiNotice(Instant.now(), singleLine));
            while (uiNoticeBuffer.size() > 4) {
                uiNoticeBuffer.removeFirst();
            }
        }
    }

    private void renderStatusFrame(LineReader reader) {
        if (reader == null || reader != activeReader) return;
        if (taskManager.hasForegroundTask()) return;
        if (!PROMPT_STATUS_PANEL_ENABLED) return;
        var terminal = activeTerminal;
        if (terminal == null) return;

        synchronized (uiRenderLock) {
            if (promptStatus == null) {
                promptStatus = Status.getStatus(terminal);
            }
            int terminalWidth = Math.max(20, terminal.getWidth() - 1);
            var lines = buildStatusPanelLines();
            if (!uiNoticeBuffer.isEmpty()) {
                lines.add(MUTED + "  + notices" + RESET);
                UiNotice latest = null;
                for (UiNotice note : uiNoticeBuffer) {
                    latest = note;
                }
                if (latest != null) {
                    lines.add(MUTED + "    - " + RESET + fitWidth(latest.text(), 72));
                }
            }
            // Pad to fixed line count so JLine's Status widget never resizes
            while (lines.size() < FIXED_STATUS_LINE_COUNT) {
                lines.add("");
            }

            int safeWidth = Math.max(20, terminalWidth - 12);
            var rendered = lines.stream()
                    .map(AttributedString::fromAnsi)
                    .map(as -> clampAttributedLine(as, safeWidth, terminalWidth))
                    .toList();
            promptStatus.update(rendered);
        }
    }

    private void ensureCursorVisible() {
        Terminal terminal = activeTerminal;
        if (terminal == null) {
            return;
        }
        try {
            terminal.puts(InfoCmp.Capability.cursor_visible);
            terminal.flush();
            return;
        } catch (Exception e) {
            log.debug("Failed to force cursor_visible capability: {}", e.getMessage());
        }
        try {
            PrintWriter writer = terminal.writer();
            writer.print("\u001B[?25h");
            writer.flush();
        } catch (Exception e) {
            log.debug("Failed to force ANSI cursor show: {}", e.getMessage());
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
        var task = taskManager.get(request.taskId());
        if (task != null) {
            task.markWaitingPermission(request.description());
        }
        requestUiRender();
        interruptPromptForPermissionPopup();
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
     * Output is capped to {@code FIXED_STATUS_LINE_COUNT - 2} to reserve
     * room for the notices section added by {@link #renderStatusFrame}.
     */
    private List<String> buildStatusPanelLines() {
        int budget = FIXED_STATUS_LINE_COUNT - 2;
        var lines = new ArrayList<String>();
        lines.add(buildStatusString());
        Instant now = Instant.now();

        String learningStatus = buildContinuousLearningStatusLine();
        if (learningStatus == null || learningStatus.isBlank()) {
            lines.add(MUTED + "  |- " + RESET
                    + INFO + "learning" + RESET
                    + MUTED + " | unavailable" + RESET);
        } else {
            lines.add(learningStatus.replaceFirst("  `- ", "  |- "));
        }

        var cron = cachedCronStatus;
        if (cron == null) {
            lines.add(MUTED + "  |- " + RESET
                    + INFO + "cron" + RESET
                    + MUTED + " | loading..." + RESET);
        } else {
            var visibleJobs = cron.jobs().stream()
                    .filter(job -> !("one-shot".equals(job.kind()) && job.lastRunAt() != null))
                    .toList();
            long hbCount = visibleJobs.stream().filter(j -> "heartbeat-longterm".equals(j.kind())).count();
            long oneShotCount = visibleJobs.stream().filter(j -> "one-shot".equals(j.kind())).count();
            long scheduledCount = visibleJobs.stream().filter(j -> "scheduled".equals(j.kind())).count();
            String schedulerState = cron.schedulerRunning() ? "on" : "off";
            String runningState = cron.jobRunning() ? "running" : "idle";
            lines.add(MUTED + "  |- " + RESET
                    + INFO + "cron" + RESET
                    + MUTED + " | scheduler=" + schedulerState
                    + " | state=" + runningState
                    + " | jobs=" + visibleJobs.size()
                    + " (s:" + scheduledCount + ",h:" + hbCount + ",o:" + oneShotCount + ")"
                    + RESET);

            String runningJobId = cron.jobRunning() ? cron.currentJobId() : null;
            if (runningJobId != null && !runningJobId.isBlank()) {
                String elapsed = cron.currentJobStartedAt() == null
                        ? "running"
                        : formatDuration(Duration.between(cron.currentJobStartedAt(), now));
                lines.add(MUTED + "  |   - " + RESET
                        + WARNING + "running " + RESET
                        + fitWidth(runningJobId, 28)
                        + MUTED + " * " + elapsed + RESET);
            }

            boolean expandDetails = cron.jobRunning() || CRON_STATUS_EXPANDED;
            if (expandDetails) {
                int maxCronVisible = 4;
                int shown = 0;
                for (CronJobStatus job : visibleJobs) {
                    if (runningJobId != null && runningJobId.equals(job.id())) {
                        continue; // already shown in the dedicated running line
                    }
                    if (shown >= maxCronVisible) break;
                    String enabled = job.enabled() ? "enabled" : "disabled";
                    String next = formatCronNext(now, cron, job);
                    String kind = job.kind();
                    String desc = fitWidth(job.description() == null ? "" : job.description(), 24);
                    lines.add(MUTED + "  |   - " + job.id() + " [" + kind + "] "
                            + enabled + " next=" + next + " :: " + desc + RESET);
                    shown++;
                }
                if (visibleJobs.size() > maxCronVisible) {
                    lines.add(MUTED + "  |   ... +" + (visibleJobs.size() - maxCronVisible)
                            + " more cron job(s)" + RESET);
                }
            }
        }

        var runningTasks = taskManager.list().stream()
                .filter(TaskHandle::isRunning)
                .toList();
        var pending = permissionBridge.pendingSnapshot();
        lines.add(MUTED + "  `- " + RESET
                + INFO + "tasks" + RESET
                + MUTED + " | running=" + runningTasks.size()
                + " | permissions=" + pending.size() + RESET);

        if (!runningTasks.isEmpty()) {
            final int maxVisible = 4;
            int from = Math.max(0, runningTasks.size() - maxVisible);
            var visible = runningTasks.subList(from, runningTasks.size());
            String fgId = taskManager.foregroundTaskId();
            for (var task : visible) {
                String elapsed = formatDuration(Duration.between(task.startedAt(), now));
                String prefix = task.taskId().equals(fgId) ? "[fg] " : "";
                String summary = fitWidth(task.promptSummary(), 52);
                var runtime = deriveRuntimeInfo(task, now);
                String runtimeLabel = fitWidth(runtime.label(), 24);

                lines.add(MUTED + "      - " + RESET
                        + runtime.color() + runtime.shortState() + RESET + " "
                        + INFO + "#" + task.taskId() + RESET + " "
                        + prefix + summary
                        + MUTED + "  " + runtimeLabel + " * " + elapsed + RESET);
            }
            int hidden = runningTasks.size() - visible.size();
            if (hidden > 0) {
                lines.add(MUTED + "      ... +" + hidden + " more running task(s)" + RESET);
            }
        }

        if (!pending.isEmpty()) {
            int maxPermVisible = 2;
            for (int i = 0; i < Math.min(maxPermVisible, pending.size()); i++) {
                var req = pending.get(i);
                String detail = fitWidth(req.description(), 58);
                lines.add(MUTED + "      - " + RESET
                        + WARNING + "permission" + RESET + " "
                        + INFO + "#" + req.taskId() + RESET + " "
                        + detail);
            }
            if (pending.size() > maxPermVisible) {
                lines.add(MUTED + "      ... +" + (pending.size() - maxPermVisible)
                        + " more permission request(s)" + RESET);
            }
        }

        if (lines.size() > budget) {
            lines.subList(budget, lines.size()).clear();
        }
        return lines;
    }

    private String buildContinuousLearningStatusLine() {
        Instant now = Instant.now();
        if (cachedLearningStatusLine != null
                && Duration.between(cachedLearningStatusAt, now).compareTo(LEARNING_STATUS_REFRESH) < 0) {
            return cachedLearningStatusLine;
        }
        String next = computeContinuousLearningStatusLine();
        cachedLearningStatusLine = next;
        cachedLearningStatusAt = now;
        return next;
    }

    private String computeContinuousLearningStatusLine() {
        if (sessionInfo.project() == null || sessionInfo.project().isBlank()) {
            return null;
        }
        final Path projectRoot;
        try {
            projectRoot = Path.of(sessionInfo.project());
        } catch (Exception e) {
            log.debug("Invalid project path for learning status: {}", e.getMessage());
            return null;
        }

        Path replayPath = projectRoot.resolve(CL_METRICS_DIR).resolve(CL_REPLAY_REPORT);
        Path releaseStatePath = projectRoot.resolve(CL_METRICS_DIR).resolve(CL_RELEASE_STATE);
        Path candidatesPath = resolveCandidatesPath(projectRoot);

        String replaySummary = replayStatusSummary(replayPath);
        String candidateSummary = candidateStatusSummary(candidatesPath);
        String releaseSummary = releaseStatusSummary(releaseStatePath);
        if (replaySummary == null && candidateSummary == null && releaseSummary == null) {
            return null;
        }

        var sb = new StringBuilder();
        sb.append(MUTED).append("  `- ").append(RESET)
                .append(INFO).append("learning").append(RESET);
        if (replaySummary != null) {
            sb.append(MUTED).append(" | ").append(RESET)
                    .append(MUTED).append("replay=").append(replaySummary).append(RESET);
        }
        if (candidateSummary != null) {
            sb.append(MUTED).append(" | ").append(RESET)
                    .append(MUTED).append("candidates=").append(candidateSummary).append(RESET);
        }
        if (releaseSummary != null) {
            sb.append(MUTED).append(" | ").append(RESET)
                    .append(MUTED).append("release=").append(releaseSummary).append(RESET);
        }
        return sb.toString();
    }

    private Path resolveCandidatesPath(Path projectRoot) {
        Path projectCandidates = projectRoot.resolve(CL_CANDIDATES);
        if (Files.isRegularFile(projectCandidates)) {
            return projectCandidates;
        }
        return HOME_CANDIDATES_PATH;
    }

    private String replayStatusSummary(Path replayPath) {
        Objects.requireNonNull(replayPath, "replayPath");
        if (!Files.isRegularFile(replayPath)) return null;
        try {
            JsonNode root = statusMapper.readTree(replayPath.toFile());
            if (root == null) return "pending";
            JsonNode metrics = root.path("metrics");
            JsonNode promotionRate = metrics.path("promotion_rate");
            JsonNode demotionRate = metrics.path("demotion_rate");
            JsonNode antiPatternFpRate = metrics.path("anti_pattern_false_positive_rate");
            JsonNode rollbackRate = metrics.path("rollback_rate");
            if (isMeasuredNumber(promotionRate)
                    && isMeasuredNumber(demotionRate)
                    && isMeasuredNumber(antiPatternFpRate)
                    && isMeasuredNumber(rollbackRate)) {
                return String.format(
                        java.util.Locale.ROOT,
                        "measured:p=%.2f,d=%.2f,fp=%.2f,r=%.2f",
                        promotionRate.path("value").asDouble(),
                        demotionRate.path("value").asDouble(),
                        antiPatternFpRate.path("value").asDouble(),
                        rollbackRate.path("value").asDouble());
            }
            JsonNode tokenErr = metrics.path("token_estimation_error_ratio_max");
            String status = tokenErr.path("status").asText("");
            JsonNode valueNode = tokenErr.path("value");
            if (status.isBlank() || valueNode.isMissingNode() || valueNode.isNull() || !valueNode.isNumber()) {
                return "pending";
            }
            return status + ":" + String.format("%.2f", valueNode.asDouble());
        } catch (Exception e) {
            log.debug("Failed to parse replay report: {}", e.getMessage());
            return "read-error";
        }
    }

    private static boolean isMeasuredNumber(JsonNode metric) {
        if (metric == null || metric.isMissingNode()) {
            return false;
        }
        if (!"measured".equalsIgnoreCase(metric.path("status").asText(""))) {
            return false;
        }
        JsonNode value = metric.path("value");
        return value.isNumber();
    }

    private String candidateStatusSummary(Path candidatesPath) {
        Objects.requireNonNull(candidatesPath, "candidatesPath");
        if (!Files.isRegularFile(candidatesPath)) return null;
        int total = 0;
        int promoted = 0;
        int shadow = 0;
        int demoted = 0;
        try (var lines = Files.lines(candidatesPath)) {
            var it = lines.iterator();
            while (it.hasNext()) {
                String line = it.next();
                if (line == null || line.isBlank()) continue;
                total++;
                JsonNode node = statusMapper.readTree(line);
                if (node == null) continue;
                String state = node.path("state").asText("");
                if ("PROMOTED".equals(state)) promoted++;
                if ("SHADOW".equals(state)) shadow++;
                if ("DEMOTED".equals(state)) demoted++;
            }
        } catch (Exception e) {
            log.debug("Failed to parse candidates.jsonl: {}", e.getMessage());
            return "read-error";
        }
        return total + "(p:" + promoted + ",s:" + shadow + ",d:" + demoted + ")";
    }

    private String releaseStatusSummary(Path releaseStatePath) {
        Objects.requireNonNull(releaseStatePath, "releaseStatePath");
        if (!Files.isRegularFile(releaseStatePath)) return null;
        int shadow = 0;
        int canary = 0;
        int active = 0;
        try {
            JsonNode root = statusMapper.readTree(releaseStatePath.toFile());
            if (root == null) return "none";
            JsonNode releases = root.path("releases");
            if (!releases.isArray()) return "none";
            for (JsonNode release : releases) {
                String stage = release.path("stage").asText("").toUpperCase();
                switch (stage) {
                    case "SHADOW" -> shadow++;
                    case "CANARY" -> canary++;
                    case "ACTIVE" -> active++;
                    default -> {
                    }
                }
            }
            return "s:" + shadow + ",c:" + canary + ",a:" + active;
        } catch (Exception e) {
            log.debug("Failed to parse release state: {}", e.getMessage());
            return "read-error";
        }
    }

    private TaskRuntimeInfo deriveRuntimeInfo(TaskHandle task, Instant now) {
        if (task.waitingPermission()) {
            String detail = task.permissionDetail();
            String label = "awaiting permission";
            if (detail != null && !detail.isBlank()) {
                label += ": " + fitWidth(detail, 28);
            }
            return new TaskRuntimeInfo(
                    TaskRuntimeState.WAITING_PERMISSION,
                    "wait_perm",
                    label,
                    WARNING,
                    "\uD83D\uDD10");
        }

        Instant lastActivity = task.lastActivityAt();
        if (lastActivity == null) {
            lastActivity = task.startedAt();
        }
        Duration idle = Duration.between(lastActivity, now);
        if (idle.isNegative()) {
            idle = Duration.ZERO;
        }

        if (idle.compareTo(TASK_TIMEOUT_AFTER) >= 0) {
            return new TaskRuntimeInfo(
                    TaskRuntimeState.TIMEOUT,
                    "timeout",
                    "timeout suspected (" + formatDuration(idle) + ")",
                    ERROR,
                    "\u23F1");
        }
        if (idle.compareTo(TASK_STALLED_AFTER) >= 0) {
            return new TaskRuntimeInfo(
                    TaskRuntimeState.STALLED,
                    "stalled",
                    "no activity " + formatDuration(idle),
                    WARNING,
                    "\u26A0");
        }

        String activity = task.activityLabel();
        if (activity == null || activity.isBlank()) {
            activity = "running";
        }
        return new TaskRuntimeInfo(
                TaskRuntimeState.ACTIVE,
                "running",
                activity,
                INFO,
                "\u23F3");
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
                out.println(INFO + "  /project" + RESET + "  Show current session project");
                out.println(INFO + "  /tasks" + RESET + "    List all tasks with status");
                out.println(INFO + "  /bg" + RESET + "       Send foreground task to background");
                out.println(INFO + "  /fg" + RESET + "       Bring background task to foreground (/fg [id])");
                out.println(INFO + "  /continue" + RESET + " Continue from the last task context");
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

            case "/project" -> {
                out.println();
                out.println(BOLD + "Session Project" + RESET);
                out.printf("  %sProject:%s %s%n", MUTED, RESET, sessionInfo.project());
                out.println();
                out.flush();
            }

            case "/tasks" -> handleTasksCommand(out);

            case "/bg" -> handleBgCommand(out);

            case "/fg" -> handleFgCommand(out, reader, arg);

            case "/continue", "/resume" -> handleContinueCommand(out, reader, arg);

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

    private void handleContinueCommand(PrintWriter out, LineReader reader, String arg) {
        if (client == null) {
            out.println(WARNING + "Not connected to daemon." + RESET);
            out.flush();
            return;
        }
        var route = resumeCheckpointStore.routeForContinue(sessionId, workspaceHash, clientInstanceId);
        if (route.checkpoint() == null) {
            log.info("resume.fallback sessionId={} reason=no-checkpoint", sessionId);
            String previous = lastTaskPrompt;
            if (previous == null || previous.isBlank()) {
                out.println(WARNING + "No previous task context to continue." + RESET);
                out.flush();
                return;
            }
            String prompt = buildLegacyContinuationPrompt(previous, arg);
            submitAndWait(out, reader, prompt);
            return;
        }
        if (route.ambiguous()) {
            log.info("resume.fallback sessionId={} reason=ambiguous-route", sessionId);
            out.println(WARNING + "Multiple resumable tasks match. Use /tasks and continue with more context." + RESET);
            out.flush();
            return;
        }
        log.info("resume.detected sessionId={} clientInstanceId={}", sessionId, clientInstanceId);
        log.info("resume.bound_task sessionId={} taskId={} route={}",
                route.checkpoint().sessionId(), route.checkpoint().taskId(), route.route());
        String prompt = ResumeCheckpointStore.buildResumePrompt(route.checkpoint(), arg);
        log.info("resume.injected sessionId={} taskId={}",
                route.checkpoint().sessionId(), route.checkpoint().taskId());
        submitAndWait(out, reader, prompt);
    }

    private static String buildLegacyContinuationPrompt(String previousPrompt, String additionalInstruction) {
        String prev = previousPrompt == null ? "" : previousPrompt.trim();
        String extra = additionalInstruction == null ? "" : additionalInstruction.trim();
        if (extra.isBlank()) {
            return """
                    Continue the previous task with the same goal, constraints, and output style.
                    Previous user request:
                    %s
                    """.formatted(prev).trim();
        }
        return """
                Continue the previous task with the same goal, constraints, and output style.
                Previous user request:
                %s

                Additional instruction:
                %s
                """.formatted(prev, extra).trim();
    }

    private String parseContinueIntent(String trimmedInput) {
        if (trimmedInput == null) {
            return null;
        }
        var matcher = CONTINUE_INTENT_PATTERN.matcher(trimmedInput.trim());
        if (!matcher.matches()) {
            return null;
        }
        return matcher.group(2) == null ? "" : matcher.group(2).trim();
    }

    private void recordResumeCheckpointOnComplete(TaskHandle handle) {
        var status = switch (handle.state()) {
            case CANCELLED -> ResumeCheckpointStore.Status.CANCELLED;
            case COMPLETED, FAILED -> ResumeCheckpointStore.Status.PAUSED;
            case RUNNING -> {
                log.warn("Task {} completion callback received while state=RUNNING; checkpointing as PAUSED defensively",
                        handle.taskId());
                yield ResumeCheckpointStore.Status.PAUSED;
            }
        };
        String currentStep = switch (handle.state()) {
            case COMPLETED -> "Last turn completed; task may still be resumable.";
            case FAILED -> "Last turn failed; resume from the last blocker and continue.";
            case CANCELLED -> "Task was cancelled by user.";
            case RUNNING -> "Task running.";
        };
        String resumeHint = extractResumeHint(handle.result(), handle.activityLabel());
        resumeCheckpointStore.recordTaskCompletion(
                sessionId,
                handle.taskId(),
                status,
                currentStep,
                resumeHint,
                handle.recentToolEventsSnapshot()
        );
    }

    private static String extractResumeHint(JsonNode message, String fallback) {
        if (message != null) {
            var text = message.path("result").path("response").asText("");
            if (!text.isBlank()) {
                String normalized = text.strip();
                int idx = normalized.indexOf('\n');
                String firstLine = idx > 0 ? normalized.substring(0, idx).trim() : normalized;
                return truncateHint(firstLine);
            }
            var error = message.path("error").path("message").asText("");
            if (!error.isBlank()) {
                return truncateHint(error.trim());
            }
        }
        return truncateHint(fallback == null ? "" : fallback);
    }

    private static String truncateHint(String value) {
        if (value == null) {
            return "";
        }
        if (value.length() <= MAX_RESUME_HINT_LEN) {
            return value;
        }
        return value.substring(0, MAX_RESUME_HINT_LEN) + "...";
    }

    private static String resolveClientInstanceId() {
        String fromEnv = System.getenv("ACECLAW_CLIENT_INSTANCE_ID");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        return "cli-default";
    }

    private static String hashWorkspace(String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            return "workspace-unknown";
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var bytes = digest.digest(projectPath.getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return "workspace-unknown";
        }
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
        Instant now = Instant.now();
        for (var handle : tasks) {
            String stateColor = switch (handle.state()) {
                case RUNNING -> INFO;
                case COMPLETED -> SUCCESS;
                case FAILED -> ERROR;
                case CANCELLED -> WARNING;
            };
            String stateLabel = handle.state().name().toLowerCase();
            String runtimeLabel = "";
            if (handle.isRunning()) {
                var runtime = deriveRuntimeInfo(handle, now);
                if (runtime.state() != TaskRuntimeState.ACTIVE) {
                    stateLabel = runtime.shortState();
                }
                runtimeLabel = runtime.label();
            }
            boolean isFg = handle.taskId().equals(fgId);
            String elapsed = formatDuration(Duration.between(handle.startedAt(), now));

            out.printf("  %s#%s%s %s%-12s%s %s%s%s  %s%s%s%n",
                    BOLD, handle.taskId(), RESET,
                    stateColor, stateLabel, RESET,
                    isFg ? BOLD + "[fg] " : "",
                    handle.promptSummary(),
                    RESET,
                    MUTED, elapsed, RESET);
            if (!runtimeLabel.isBlank()) {
                out.printf("      %s%s%s%n", MUTED, runtimeLabel, RESET);
            }
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
        resumeCheckpointStore.markForeground(sessionId, fgHandle.taskId(), false);
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
        resumeCheckpointStore.markForeground(sessionId, target.taskId(), true);

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

    private static String formatCronNext(Instant now, CronStatusSnapshot cron, CronJobStatus job) {
        if (job == null) return "n/a";
        if (cron != null && cron.jobRunning() && cron.currentJobId() != null
                && cron.currentJobId().equals(job.id())) {
            return "running";
        }
        Instant next = job.nextFireAt();
        if (next == null) return "n/a";
        Duration delta = Duration.between(now, next);
        if (delta.isNegative()) {
            // If stale/behind, show absolute only to avoid giant misleading "N hours" values.
            return TIME_FMT.format(java.time.LocalDateTime.ofInstant(
                    next, java.time.ZoneId.systemDefault()));
        }
        long days = delta.toDays();
        if (days >= 7) {
            return TIME_FMT.format(java.time.LocalDateTime.ofInstant(
                    next, java.time.ZoneId.systemDefault()));
        }
        return formatDuration(delta) + " @ "
                + TIME_FMT.format(java.time.LocalDateTime.ofInstant(next, java.time.ZoneId.systemDefault()));
    }

    private static String sanitizeCronSummary(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String noAnsi = ANSI_CSI_PATTERN.matcher(raw).replaceAll("");
        StringBuilder sb = new StringBuilder(noAnsi.length());
        for (int i = 0; i < noAnsi.length(); ) {
            int cp = noAnsi.codePointAt(i);
            i += Character.charCount(cp);
            // Keep newline/tab/printable chars, drop other control chars (including ESC).
            if (cp == '\n' || cp == '\t' || cp >= 0x20) {
                sb.appendCodePoint(cp);
            }
        }
        return sb.toString().strip();
    }

    private static Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Instant.parse(raw);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Clamps an {@link AttributedString} to fit within the terminal width,
     * preserving ANSI styling. Uses JLine's own width calculation for consistency.
     *
     * @param as         the styled string to clamp
     * @param safeWidth  maximum visible columns for content (truncate if exceeded)
     * @param clearWidth total columns to pad to (clears leftover from previous renders)
     */
    private static AttributedString clampAttributedLine(AttributedString as,
                                                         int safeWidth, int clearWidth) {
        if (as == null) return new AttributedString("");
        int visibleWidth = as.columnLength();
        if (visibleWidth <= safeWidth) {
            // Fits — pad to clearWidth to overwrite previous longer content
            if (visibleWidth >= clearWidth) return as;
            var sb = new AttributedStringBuilder();
            sb.append(as);
            sb.append(" ".repeat(clearWidth - visibleWidth));
            return sb.toAttributedString();
        }
        // Too long — truncate and pad
        var sb = new AttributedStringBuilder();
        sb.append(as.columnSubSequence(0, Math.max(0, safeWidth - 3)));
        sb.append("...");
        int truncatedWidth = sb.toAttributedString().columnLength();
        if (truncatedWidth < clearWidth) {
            sb.append(" ".repeat(clearWidth - truncatedWidth));
        }
        return sb.toAttributedString();
    }
}

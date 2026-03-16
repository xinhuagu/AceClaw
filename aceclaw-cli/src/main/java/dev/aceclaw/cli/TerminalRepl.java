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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private static final Duration SKILL_DRAFT_REFRESH = Duration.ofSeconds(5);
    private static final Duration CRON_STATUS_REFRESH = Duration.ofSeconds(5);
    /** Slightly shorter than the bridge timeout so user answers win the race near deadline. */
    private static final long PERMISSION_MODAL_TIMEOUT_MS =
            TaskStreamReader.CLIENT_PERMISSION_WAIT_TIMEOUT_MS - 5_000L;
    private static final boolean PROMPT_STATUS_PANEL_ENABLED =
            Boolean.parseBoolean(System.getenv().getOrDefault("ACECLAW_PROMPT_STATUS_PANEL", "true"));
    private static final boolean CRON_STATUS_EXPANDED =
            Boolean.parseBoolean(System.getenv().getOrDefault("ACECLAW_CRON_STATUS_EXPANDED", "false"));
    private static final int MAX_RESUME_HINT_LEN = 200;
    private static final String CL_METRICS_DIR = ".aceclaw/metrics/continuous-learning";
    private static final String CL_REPLAY_REPORT = "replay-latest.json";
    private static final String CL_RELEASE_STATE = "skill-release-state.json";
    private static final String CL_VALIDATION_AUDIT = "skill-draft-validation-audit.jsonl";
    private static final String CL_CANDIDATES = ".aceclaw/memory/candidates.jsonl";
    private static final String CL_DRAFTS_DIR = ".aceclaw/skills-drafts";
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
    private static final int MAX_PENDING_PRINT_ABOVE = 32;
    private final Deque<String> pendingPrintAbove = new ArrayDeque<>();
    /** Fixed status panel height so JLine's Status widget never resizes its scroll region. */
    private static final int FIXED_STATUS_LINE_COUNT = 9;
    /** Soft cap (in display columns) for daemon-provided fields rendered inside the status panel. */
    private static final int STATUS_PANEL_FIELD_MAX_COLS = 80;
    /** Prevent /context detail from dumping arbitrarily large prompt sections into the terminal. */
    private static final int MAX_CONTEXT_DETAIL_CHARS = 12_000;
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
    /** Current interactive mode of the main console. */
    private volatile ConsoleMode consoleMode = ConsoleMode.NORMAL_INPUT;
    /** The permission request currently owning the terminal, if any. */
    private volatile String activePermissionRequestId;

    /** Central, thread-safe tracker for context usage across turns. */
    private volatile ContextMonitor contextMonitor;

    /** Timestamp when the current prompt was sent (nanos). */
    private long promptStartNanos = 0;
    /** Cached continuous-learning status line; refreshed periodically. */
    private volatile String cachedLearningStatusLine;
    private volatile Instant cachedLearningStatusAt = Instant.EPOCH;
    private volatile SkillDraftSnapshot cachedSkillDraftSnapshot;
    private volatile Instant cachedSkillDraftSnapshotAt = Instant.EPOCH;
    /** Cached cron status snapshot from daemon RPC. */
    private volatile CronStatusSnapshot cachedCronStatus;
    private volatile Instant cachedCronStatusAt = Instant.EPOCH;
    /** Cached git branch name, refreshed periodically. */
    private volatile String cachedGitBranch;
    private volatile Instant cachedGitBranchAt = Instant.EPOCH;
    private static final Duration GIT_BRANCH_REFRESH = Duration.ofSeconds(5);
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
    /** Last consumed deferred event sequence from daemon. */
    private volatile long deferEventSeq;
    /** Last consumed skill draft event sequence from daemon. */
    private volatile long skillDraftEventSeq;

    private sealed interface UiEvent permits UiNoticeEvent, UiPrintAboveEvent {}

    private record UiNoticeEvent(String ansiText) implements UiEvent {}
    private record UiPrintAboveEvent(String ansiText) implements UiEvent {}
    private record UiNotice(Instant at, String text) {}
    private record ForegroundPause(TaskHandle handle, ForegroundOutputSink sink,
                                   BackgroundOutputBuffer buffer) {}
    private record PermissionDecision(boolean approved, boolean remember, boolean timedOut) {}

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
    ) {
        private CronStatusSnapshot {
            jobs = jobs != null ? List.copyOf(jobs) : List.of();
        }
    }

    private record SkillValidationStatus(
            String verdict,
            List<String> reasons
    ) {
        private SkillValidationStatus {
            verdict = verdict == null || verdict.isBlank() ? "pending" : verdict;
            reasons = reasons != null ? List.copyOf(reasons) : List.of();
        }
    }

    private record SkillReleaseStatus(
            String stage,
            boolean paused
    ) {
        private SkillReleaseStatus {
            stage = stage == null || stage.isBlank() ? "draft" : stage;
        }
    }

    private record SkillDraftRecord(
            String skillName,
            String description,
            String draftPath,
            String candidateId,
            String allowedTools,
            boolean disableModelInvocation,
            String validationVerdict,
            List<String> validationReasons,
            String releaseStage,
            boolean paused,
            boolean manualReviewNeeded
    ) {
        private SkillDraftRecord {
            skillName = skillName == null ? "" : skillName;
            description = description == null ? "" : description;
            draftPath = draftPath == null ? "" : draftPath;
            candidateId = candidateId == null ? "" : candidateId;
            allowedTools = allowedTools == null ? "" : allowedTools;
            validationVerdict = validationVerdict == null || validationVerdict.isBlank()
                    ? "pending" : validationVerdict;
            validationReasons = validationReasons != null ? List.copyOf(validationReasons) : List.of();
            releaseStage = releaseStage == null || releaseStage.isBlank() ? "draft" : releaseStage;
        }
    }

    private record SkillDraftEvent(
            String type,
            String trigger,
            String skillName,
            String draftPath,
            String candidateId,
            String verdict,
            String releaseStage,
            boolean paused,
            List<String> reasons
    ) {
        private SkillDraftEvent {
            type = type == null ? "" : type;
            trigger = trigger == null ? "manual" : trigger;
            skillName = skillName == null ? "" : skillName;
            draftPath = draftPath == null ? "" : draftPath;
            candidateId = candidateId == null ? "" : candidateId;
            verdict = verdict == null ? "" : verdict;
            releaseStage = releaseStage == null ? "" : releaseStage;
            reasons = reasons != null ? List.copyOf(reasons) : List.of();
        }
    }

    private record SkillDraftSnapshot(
            Map<String, SkillDraftRecord> drafts
    ) {
        private SkillDraftSnapshot {
            drafts = drafts != null ? Map.copyOf(drafts) : Map.of();
        }
    }

    private enum ConsoleMode {
        NORMAL_INPUT,
        FOREGROUND_WAIT,
        PERMISSION_MODAL
    }

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
        this.contextMonitor = new ContextMonitor(sessionInfo.contextWindowTokens());
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
                deferEventSeq = bootstrapDeferEventSeq(schedulerEventConnection);
                skillDraftEventSeq = bootstrapSkillDraftEventSeq(schedulerEventConnection);
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

            // Override Ctrl+L: clear screen + reset status widget + redraw
            reader.getWidgets().put("aceclaw-clear-screen", () -> {
                reader.callWidget(LineReader.CLEAR_SCREEN);
                // Reset JLine's Status widget so it recalculates its scroll
                // region from the cleared screen state. Without this, the
                // widget writes to stale positions after clear.
                synchronized (uiRenderLock) {
                    if (promptStatus != null) {
                        promptStatus.reset();
                    }
                }
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
                    consoleMode = ConsoleMode.NORMAL_INPUT;
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
                        pollAndRenderDeferredEvents(schedulerEventConn, reader);
                        pollAndRenderSkillDraftEvents(schedulerEventConn);
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
                        if (!pendingPrintAbove.isEmpty() && readingPrompt && !isPermissionModalActive()) {
                            for (String text : pendingPrintAbove) {
                                // Strip trailing blank lines before splitting to avoid
                                // empty printAbove() calls that produce visible blank lines.
                                String[] lines = text.stripTrailing().split("\n", -1);
                                for (String line : lines) {
                                    try {
                                        reader.printAbove(AttributedString.fromAnsi(line));
                                    } catch (Exception e) {
                                        log.debug("Failed to printAbove: {}", e.getMessage());
                                    }
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
                                && !isPermissionModalActive()
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
                    sb.append(sw.toString().stripTrailing());
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

    private long bootstrapDeferEventSeq(DaemonConnection conn)
            throws IOException, DaemonClient.DaemonClientException {
        var params = client.objectMapper().createObjectNode();
        params.put("afterSeq", Long.MAX_VALUE);
        params.put("limit", 1);
        try {
            JsonNode result = conn.sendRequest("deferred.events.poll", params);
            return Math.max(0L, result.path("nextSeq").asLong(0L));
        } catch (Exception e) {
            log.debug("deferred.events.poll not available (daemon may not support it): {}", e.getMessage());
            return 0L;
        }
    }

    private long bootstrapSkillDraftEventSeq(DaemonConnection conn)
            throws IOException, DaemonClient.DaemonClientException {
        var params = client.objectMapper().createObjectNode();
        params.put("afterSeq", Long.MAX_VALUE);
        params.put("limit", 1);
        try {
            JsonNode result = conn.sendRequest("skill.draft.events.poll", params);
            return Math.max(0L, result.path("nextSeq").asLong(0L));
        } catch (Exception e) {
            log.debug("skill.draft.events.poll not available (daemon may not support it): {}", e.getMessage());
            return 0L;
        }
    }

    private void pollAndRenderDeferredEvents(DaemonConnection conn, LineReader reader) {
        if (conn == null) return;
        try {
            var params = client.objectMapper().createObjectNode();
            params.put("afterSeq", deferEventSeq);
            params.put("limit", 20);
            JsonNode result = conn.sendRequest("deferred.events.poll", params);
            deferEventSeq = Math.max(deferEventSeq, result.path("nextSeq").asLong(deferEventSeq));
            JsonNode events = result.path("events");
            if (!events.isArray() || events.isEmpty()) {
                return;
            }

            for (JsonNode event : events) {
                String type = event.path("type").asText("");
                if ("completed".equals(type)) {
                    String fullOutput = renderDeferredEventNote(event);
                    if (fullOutput != null) {
                        enqueueUiPrintAbove(fullOutput);
                    }
                    String actionId = event.path("actionId").asText("unknown");
                    long durationMs = event.path("durationMs").asLong(-1L);
                    String shortNote = SUCCESS + "deferred completed" + RESET + " [" + actionId + "]"
                            + (durationMs > 0 ? " in " + durationMs + "ms" : "");
                    enqueueUiNotice(shortNote);
                } else {
                    String note = renderDeferredEventNote(event);
                    if (note != null) {
                        enqueueUiNotice(note);
                    }
                }
            }
            requestUiRender();
        } catch (Exception e) {
            log.debug("Failed to poll deferred events: {}", e.getMessage());
        }
    }

    private void pollAndRenderSkillDraftEvents(DaemonConnection conn) {
        if (conn == null) return;
        try {
            var params = client.objectMapper().createObjectNode();
            params.put("afterSeq", skillDraftEventSeq);
            params.put("limit", 20);
            JsonNode result = conn.sendRequest("skill.draft.events.poll", params);
            skillDraftEventSeq = Math.max(skillDraftEventSeq, result.path("nextSeq").asLong(skillDraftEventSeq));
            JsonNode events = result.path("events");
            if (!events.isArray() || events.isEmpty()) {
                return;
            }
            for (JsonNode event : events) {
                renderSkillDraftEventNotice(parseSkillDraftEvent(event));
            }
            requestUiRender();
        } catch (Exception e) {
            log.debug("Failed to poll skill.draft.events: {}", e.getMessage());
        }
    }

    private String renderDeferredEventNote(JsonNode event) {
        String type = event.path("type").asText("");
        String actionId = event.path("actionId").asText("unknown");
        return switch (type) {
            case "scheduled" -> {
                String goal = sanitizeCronSummary(event.path("goal").asText(""));
                String runAt = event.path("runAt").asText("");
                String goalSnippet = goal.length() > 60 ? goal.substring(0, 57) + "..." : goal;
                yield MUTED + "[deferred scheduled] " + actionId
                        + " \"" + goalSnippet + "\""
                        + (runAt.isBlank() ? "" : " @ " + runAt) + RESET;
            }
            case "triggered" -> {
                String ts = event.path("timestamp").asText("");
                yield MUTED + "[deferred running] " + actionId
                        + (ts.isBlank() ? "" : " @ " + ts) + RESET;
            }
            case "completed" -> {
                var sb = new StringBuilder();
                sb.append(MUTED).append("--- ").append(SUCCESS).append("deferred completed")
                        .append(RESET).append(MUTED).append(" [").append(actionId).append("] ---")
                        .append(RESET).append("\n");

                String summary = sanitizeCronSummary(event.path("summary").asText(""));
                if (!summary.isBlank()) {
                    var sw = new StringWriter();
                    var pw = new PrintWriter(sw);
                    new TerminalMarkdownRenderer().render(summary, pw);
                    pw.flush();
                    sb.append(sw.toString().stripTrailing());
                } else {
                    long durationMs = event.path("durationMs").asLong(-1L);
                    if (durationMs > 0) {
                        sb.append(MUTED).append("(completed in ").append(durationMs).append("ms)").append(RESET).append("\n");
                    }
                    sb.append(WARNING)
                            .append("No textual summary returned. ")
                            .append("The action may have completed via tool calls only.")
                            .append(RESET)
                            .append("\n");
                }
                yield sb.toString();
            }
            case "failed" -> {
                String err = sanitizeCronSummary(event.path("error").asText("unknown error"));
                int attempt = event.path("attempt").asInt(0);
                int maxAttempts = event.path("maxAttempts").asInt(0);
                yield ERROR + "[deferred failed] " + RESET + actionId + " - " + err
                        + (maxAttempts > 0 ? " (attempt " + attempt + "/" + maxAttempts + ")" : "");
            }
            case "expired" -> {
                yield WARNING + "[deferred expired] " + RESET + actionId;
            }
            case "cancelled" -> {
                String reason = sanitizeCronSummary(event.path("reason").asText(""));
                yield WARNING + "[deferred cancelled] " + RESET + actionId
                        + (reason.isBlank() ? "" : " - " + reason);
            }
            case "rescheduled" -> {
                String reason = sanitizeCronSummary(event.path("reason").asText(""));
                int delay = event.path("delaySeconds").asInt(0);
                String newRunAt = event.path("newRunAt").asText("");
                yield MUTED + "[deferred rescheduled] " + actionId
                        + " - re-check in " + delay + "s"
                        + (reason.isBlank() ? "" : " (" + reason + ")")
                        + (newRunAt.isBlank() ? "" : " @ " + newRunAt) + RESET;
            }
            case "queued" -> {
                String reason = sanitizeCronSummary(event.path("reason").asText(""));
                yield MUTED + "[deferred queued] " + actionId
                        + (reason.isBlank() ? "" : " - " + reason) + RESET;
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
            int ctxWindow = sessionInfo != null ? sessionInfo.contextWindowTokens() : 0;
            var fgSink = new ForegroundOutputSink(out, markdownRenderer, activeTerminal, contextMonitor, this::requestUiRender);
            activeForegroundSink = fgSink;

            promptStartNanos = System.nanoTime();

            var handle = taskManager.submit(effectiveInput, conn, sessionId, fgSink, permissionBridge, ctxWindow);
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

            // Suspend JLine's Status widget so its scroll region doesn't go
            // stale while ForegroundOutputSink writes directly to the terminal.
            suspendStatusPanel();

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

            // Restore JLine's Status widget so it recalculates scroll region
            // based on the terminal's current state after task output.
            restoreStatusPanel();

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
        consoleMode = ConsoleMode.FOREGROUND_WAIT;

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
            if (consoleMode != ConsoleMode.PERMISSION_MODAL) {
                consoleMode = ConsoleMode.NORMAL_INPUT;
            }
            terminal.setAttributes(savedAttrs);
        }
    }

    /**
     * Fallback polling without keypress detection (used when terminal is unavailable).
     */
    private void simplePollForeground(PrintWriter out, LineReader reader) {
        consoleMode = ConsoleMode.FOREGROUND_WAIT;
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
        if (consoleMode != ConsoleMode.PERMISSION_MODAL) {
            consoleMode = ConsoleMode.NORMAL_INPUT;
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
            fgSink.detach();
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

            // Use the per-call liveInputTokens for context display (actual context occupation).
            // The JSON-RPC result's inputTokens is cumulative across all API calls in the turn,
            // NOT the per-call value — using it would cause erratic usage % jumps.
            // If no streaming usage was received, keep the monitor's existing per-call value (0 means "no update").
            long perCallContext = handle.liveInputTokens();
            contextMonitor.recordTurnComplete(turnIn, turnOut, perCallContext);
            contextMonitor.checkThresholds(log);

            // For display, use the monitor's authoritative value which handles the
            // perCallContext=0 case by preserving the last known streaming value.
            long displayContext = contextMonitor.currentContextTokens();

            long elapsedMs = (System.nanoTime() - promptStartNanos) / 1_000_000;
            String elapsed = elapsedMs >= 1000
                    ? String.format("%.1fs", elapsedMs / 1000.0)
                    : elapsedMs + "ms";

            out.println();
            out.printf("%s%s  %d in / %d out  %s%s%n",
                    MUTED, elapsed, turnIn, turnOut,
                    sessionInfo.contextWindowTokens() > 0
                            ? "context " + formatTokenCount(displayContext) + "/"
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
        // Inner width excludes the left and right border characters (│ ... │)
        int innerWidth = 54;
        var pausedForeground = pauseForegroundRendering();
        beginPermissionModal(req);

        try {
            // Truncate description to fit inside the box (null-safe, CJK-aware)
            String desc = fitWidth(req.description(), innerWidth);

            String title = " Permission Required ";
            // Top border inner fill: 1 (leading ─) + title + topFill = innerWidth + 1
            int topFill = innerWidth - title.length();
            String P = PERMISSION;  // shorthand for color prefix

            out.println();
            // ┌─ Permission Required ──────────────────────────────┐
            out.println(P + BOX_LIGHT_TOP_LEFT + BOX_LIGHT_HORIZONTAL
                    + title + hlineLight(topFill) + BOX_LIGHT_TOP_RIGHT + RESET);
            // │ <description>                                      │
            out.println(P + BOX_LIGHT_VERTICAL + RESET
                    + " " + padRight(desc, innerWidth) + P + BOX_LIGHT_VERTICAL + RESET);
            int queued = permissionBridge.pendingCount();
            if (queued > 0) {
                out.println(P + BOX_LIGHT_VERTICAL + RESET
                        + " " + padRight(queued + " more request(s) queued", innerWidth)
                        + P + BOX_LIGHT_VERTICAL + RESET);
            }
            // │                                                    │
            out.println(P + BOX_LIGHT_VERTICAL
                    + " ".repeat(innerWidth + 1) + BOX_LIGHT_VERTICAL + RESET);
            // │ (y) Allow  (n) Deny  (a) Always                   │
            String choicesText = "(y) Allow  (n) Deny  (a) Always";
            out.println(P + BOX_LIGHT_VERTICAL + RESET
                    + " " + padRight(choicesText, innerWidth) + P + BOX_LIGHT_VERTICAL + RESET);
            // └────────────────────────────────────────────────────┘
            out.println(P + BOX_LIGHT_BOTTOM_LEFT
                    + hlineLight(innerWidth + 1) + BOX_LIGHT_BOTTOM_RIGHT + RESET);
            // User input below the box
            out.print(PERMISSION + " > " + RESET);
            out.flush();

            var answer = readPermissionAnswer(reader);
            boolean approved = answer.approved();
            boolean remember = answer.remember();

            if (answer.timedOut()) {
                out.printf("%sTimed out%s%n", WARNING, RESET);
            } else if (approved) {
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
        } finally {
            permissionInterruptRequested.set(false);
            endPermissionModal(req);
            resumeForegroundRendering(pausedForeground);
            requestUiRender();
        }
    }

    private PermissionDecision readPermissionAnswer(LineReader reader) {
        var terminal = activeTerminal != null ? activeTerminal : reader.getTerminal();
        if (terminal == null) {
            return new PermissionDecision(false, false, true);
        }

        Attributes savedAttrs = terminal.getAttributes();
        Attributes rawAttrs = new Attributes(savedAttrs);
        rawAttrs.setLocalFlag(Attributes.LocalFlag.ICANON, false);
        rawAttrs.setLocalFlag(Attributes.LocalFlag.ECHO, false);
        rawAttrs.setControlChar(Attributes.ControlChar.VMIN, 0);
        rawAttrs.setControlChar(Attributes.ControlChar.VTIME, 1);

        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PERMISSION_MODAL_TIMEOUT_MS);
        terminal.setAttributes(rawAttrs);
        try {
            while (true) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    return new PermissionDecision(false, false, true);
                }

                int timeoutMs = (int) Math.max(1L, Math.min(250L,
                        TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
                int ch = terminal.reader().peek(timeoutMs);
                if (ch == -2) {
                    continue;
                }
                if (ch < 0) {
                    return new PermissionDecision(false, false, false);
                }

                terminal.reader().read(1);
                switch (Character.toLowerCase((char) ch)) {
                    case 'y':
                        discardBufferedPermissionInput(terminal);
                        return new PermissionDecision(true, false, false);
                    case 'a':
                        discardBufferedPermissionInput(terminal);
                        return new PermissionDecision(true, true, false);
                    case 'n':
                    case 'q':
                    case 3:
                        discardBufferedPermissionInput(terminal);
                        return new PermissionDecision(false, false, false);
                    case '\r':
                    case '\n':
                        continue;
                    default:
                        terminal.puts(InfoCmp.Capability.bell);
                        terminal.flush();
                }
            }
        } catch (IOException e) {
            log.debug("Permission modal input failed: {}", e.getMessage());
            return new PermissionDecision(false, false, false);
        } finally {
            terminal.setAttributes(savedAttrs);
            ensureCursorVisible();
        }
    }

    private void discardBufferedPermissionInput(Terminal terminal) throws IOException {
        while (terminal.reader().peek(1) >= 0) {
            terminal.reader().read(1);
        }
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

    private void beginPermissionModal(PermissionBridge.PermissionRequest req) {
        activePermissionRequestId = req.requestId();
        consoleMode = ConsoleMode.PERMISSION_MODAL;
    }

    private void endPermissionModal(PermissionBridge.PermissionRequest req) {
        if (req != null && Objects.equals(activePermissionRequestId, req.requestId())) {
            activePermissionRequestId = null;
        }
        consoleMode = taskManager.hasForegroundTask()
                ? ConsoleMode.FOREGROUND_WAIT
                : ConsoleMode.NORMAL_INPUT;
    }

    private boolean isPermissionModalActive() {
        return consoleMode == ConsoleMode.PERMISSION_MODAL && activePermissionRequestId != null;
    }

    private ForegroundPause pauseForegroundRendering() {
        var handle = taskManager.foregroundTask();
        if (handle == null || !handle.isRunning()) {
            return null;
        }
        var sink = handle.outputSink();
        if (!(sink instanceof ForegroundOutputSink fgSink)) {
            return null;
        }

        fgSink.detach();
        var buffer = new BackgroundOutputBuffer();
        var previous = handle.swapOutputSink(buffer);
        if (!(previous instanceof ForegroundOutputSink previousFgSink)) {
            handle.swapOutputSink(previous);
            return null;
        }
        return new ForegroundPause(handle, previousFgSink, buffer);
    }

    private void resumeForegroundRendering(ForegroundPause pause) {
        if (pause == null) {
            return;
        }
        if (!pause.handle().isRunning()) {
            return;
        }
        if (pause.handle().outputSink() != pause.buffer()) {
            return;
        }
        var previous = pause.handle().swapOutputSink(pause.sink());
        if (previous == pause.buffer()) {
            pause.buffer().replay(pause.sink());
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
            // Record turn usage in ContextMonitor (background tasks skip renderTaskCompletion)
            JsonNode bgResult = handle.result();
            if (bgResult != null) {
                JsonNode bgUsageResult = bgResult.get("result");
                if (bgUsageResult != null && bgUsageResult.has("usage")) {
                    var bgUsage = bgUsageResult.get("usage");
                    int bgTurnIn = bgUsage.path("inputTokens").asInt(0);
                    int bgTurnOut = bgUsage.path("outputTokens").asInt(0);
                    long bgPerCall = handle.liveInputTokens();
                    contextMonitor.recordTurnComplete(bgTurnIn, bgTurnOut, bgPerCall);
                    contextMonitor.checkThresholds(log);
                }
            }

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
                sb.append(sw.toString().stripTrailing());
            }

            String output = sb.toString();
            if (!output.isBlank()) {
                // Full output printed above the prompt (like cron completion)
                enqueueUiPrintAbove(output);
                // Short notice in status bar
                String stateLabel = handle.state().name().toLowerCase();
                String summary = fitWidth(handle.promptSummary(), 40);
                enqueueUiNotice(stateColor + "bg #" + handle.taskId()
                        + " " + stateLabel + RESET + " " + summary);
            }
        } catch (Exception e) {
            log.debug("Failed to push background task output: {}", e.getMessage());
        }
    }

    // -- Background task notifications (fallback at prompt) -------------------

    private void notifyCompletedBackgroundTasks(PrintWriter out) {
        if (isPermissionModalActive()) {
            return;
        }
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

    /**
     * Returns the current git branch, refreshing from disk at most every 5 seconds.
     * Falls back to the startup value from {@code SessionInfo} if detection fails.
     */
    private String currentGitBranch() {
        Instant now = Instant.now();
        if (cachedGitBranch != null
                && Duration.between(cachedGitBranchAt, now).compareTo(GIT_BRANCH_REFRESH) < 0) {
            return cachedGitBranch;
        }
        String project = sessionInfo.project();
        if (project == null || project.isBlank()) {
            return sessionInfo.gitBranch();
        }
        try {
            var pb = new ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD");
            pb.directory(Path.of(project).toFile());
            pb.redirectErrorStream(true);
            var process = pb.start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            int exitCode = process.waitFor();
            if (exitCode == 0 && !output.isBlank()) {
                cachedGitBranch = output;
            } else {
                cachedGitBranch = sessionInfo.gitBranch();
            }
        } catch (Exception e) {
            cachedGitBranch = sessionInfo.gitBranch();
        }
        cachedGitBranchAt = now;
        return cachedGitBranch;
    }

    private String buildStatusString() {
        var sb = new StringBuilder();

        sb.append(PURPLE).append(ICON_PRIMARY).append(RESET).append(" ");
        sb.append(INFO).append(BOLD).append(effectiveModel).append(RESET);

        String branch = currentGitBranch();
        if (branch != null && !branch.isBlank()) {
            sb.append(MUTED).append(" | ").append(RESET);
            sb.append(SUCCESS).append("branch=")
                    .append(fitWidth(branch, 28))
                    .append(RESET);
        }

        int ctxWindow = sessionInfo.contextWindowTokens();
        if (ctxWindow > 0) {
            long displayTokens = effectiveContextTokens();
            sb.append(MUTED).append(" | ").append(RESET);
            sb.append(buildContextBar(displayTokens, ctxWindow));
            String pressureBadge = buildContextPressureBadge();
            if (pressureBadge != null) {
                sb.append(MUTED).append(" | ").append(RESET);
                sb.append(pressureBadge);
            }
            String compactionBadge = buildCompactionBadge();
            if (compactionBadge != null) {
                sb.append(MUTED).append(" | ").append(RESET);
                sb.append(compactionBadge);
            }
        }

        // Show running task count if > 0
        int running = taskManager.runningCount();
        if (running > 0) {
            sb.append(MUTED).append(" | ").append(RESET);
            sb.append(INFO).append(running).append(" task")
              .append(running > 1 ? "s" : "").append(RESET);
        }

        int pendingPermissions = permissionBridge.pendingCount();
        if (isPermissionModalActive() || pendingPermissions > 0) {
            sb.append(MUTED).append(" | ").append(RESET);
            if (isPermissionModalActive()) {
                sb.append(WARNING).append("permission-modal").append(RESET);
                if (pendingPermissions > 0) {
                    sb.append(MUTED).append(" (+").append(pendingPermissions).append(" queued)").append(RESET);
                }
            } else {
                sb.append(WARNING).append("permissions=")
                  .append(pendingPermissions)
                  .append(" permission")
                  .append(pendingPermissions > 1 ? "s" : "").append(RESET);
            }
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
                    if (pendingPrintAbove.size() >= MAX_PENDING_PRINT_ABOVE) {
                        pendingPrintAbove.removeFirst();
                    }
                    pendingPrintAbove.addLast(pae.ansiText());
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
            int terminalHeight = Math.max(6, terminal.getHeight());
            // Never use more than 1/3 of the terminal for status
            int maxLines = Math.min(FIXED_STATUS_LINE_COUNT, terminalHeight / 3);

            var lines = buildStatusPanelLines();
            // Separator line above the status panel
            lines.addFirst(MUTED + "─".repeat(terminalWidth) + RESET);
            if (!uiNoticeBuffer.isEmpty()) {
                lines.add(MUTED + "  " + ICON_NOTICES + " notices" + RESET);
                UiNotice latest = null;
                for (UiNotice note : uiNoticeBuffer) {
                    latest = note;
                }
                if (latest != null) {
                    lines.add(MUTED + "    " + ICON_ITEM + " " + RESET
                            + normalizeStatusPanelField(latest.text()));
                }
            }
            // Truncate if over budget, then pad to fixed count
            if (lines.size() > maxLines) {
                lines.subList(maxLines, lines.size()).clear();
            }
            while (lines.size() < maxLines) {
                lines.add("");
            }

            int safeWidth = terminalWidth;
            var rendered = lines.stream()
                    .map(AttributedString::fromAnsi)
                    .map(as -> clampAttributedLine(as, safeWidth, terminalWidth))
                    .toList();

            // Let JLine's Status.update() handle cursor positioning internally.
            // Do NOT wrap with SCP/RCP (\u001B[s / \u001B[u) as that conflicts
            // with JLine's own cursor management and corrupts cursor position.
            promptStatus.update(rendered);
        }
        ensureCursorVisible();
    }

    /**
     * Suspends JLine's Status widget before a foreground task takes over the terminal.
     * This tears down the scroll region so direct writes don't corrupt JLine's state.
     */
    private void suspendStatusPanel() {
        synchronized (uiRenderLock) {
            if (promptStatus != null) {
                try {
                    promptStatus.suspend();
                } catch (Exception e) {
                    log.debug("Failed to suspend status panel: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * Restores JLine's Status widget after a foreground task completes.
     * This re-establishes the scroll region based on the terminal's current state.
     */
    private void restoreStatusPanel() {
        synchronized (uiRenderLock) {
            if (promptStatus != null) {
                try {
                    promptStatus.restore();
                } catch (Exception e) {
                    log.debug("Failed to restore status panel: {}", e.getMessage());
                }
            }
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
     * <p>Marks the task as waiting for permission, queues a short notice, and
     * interrupts the main prompt so the permission modal can take over the
     * terminal on the main REPL thread.
     */
    private void onPermissionRequested(PermissionBridge.PermissionRequest request, LineReader reader) {
        if (reader == null) return;
        var task = taskManager.get(request.taskId());
        if (task != null) {
            task.markWaitingPermission(request.description());
        }
        String notice = WARNING + "[Permission] " + RESET
                + "task #" + request.taskId() + " -> "
                + fitWidth(request.description(), 100);
        enqueueUiNotice(notice);
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
     *
     * <p>Each section (learning, cron, tasks) renders its header immediately
     * followed by its own detail lines, so items always appear under the
     * correct section. {@code renderStatusFrame} handles final truncation
     * and padding to {@link #FIXED_STATUS_LINE_COUNT}.
     */
    private List<String> buildStatusPanelLines() {
        var lines = new ArrayList<String>();
        Instant now = Instant.now();

        // -- 1. Header --
        lines.add(buildStatusString());

        // -- 2. Learning (header only) --
        String learningStatus = buildContinuousLearningStatusLine();
        if (learningStatus == null || learningStatus.isBlank()) {
            lines.add(MUTED + "  " + TREE_BRANCH + " " + ICON_LEARNING + " " + RESET
                    + INFO + "learning" + RESET
                    + MUTED + " | unavailable" + RESET);
        } else {
            lines.add(learningStatus);
        }

        // -- 3. Cron (header + registered jobs inline) --
        var cron = cachedCronStatus;
        if (cron == null) {
            lines.add(MUTED + "  " + TREE_BRANCH + " " + ICON_CRON + " " + RESET
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
            lines.add(MUTED + "  " + TREE_BRANCH + " " + ICON_CRON + " " + RESET
                    + INFO + "cron" + RESET
                    + MUTED + " | scheduler=" + schedulerState
                    + " | state=" + runningState
                    + " | jobs=" + visibleJobs.size()
                    + " (s:" + scheduledCount + ",h:" + hbCount + ",o:" + oneShotCount + ")"
                    + RESET);

            // Running job (highlighted)
            String runningJobId = cron.jobRunning() ? cron.currentJobId() : null;
            if (runningJobId != null && !runningJobId.isBlank()) {
                String elapsed = cron.currentJobStartedAt() == null
                        ? "running"
                        : formatDuration(Duration.between(cron.currentJobStartedAt(), now));
                lines.add(MUTED + "  " + TREE_PIPE_SPACE + " " + ICON_ITEM + " " + RESET
                        + WARNING + "running " + RESET
                        + fitWidth(runningJobId, 28)
                        + MUTED + " * " + elapsed + RESET);
            }

            // All registered jobs (always shown, max 3)
            int maxCronVisible = 3;
            int shown = 0;
            for (CronJobStatus job : visibleJobs) {
                if (runningJobId != null && runningJobId.equals(job.id())) {
                    continue; // already shown in the running line
                }
                if (shown >= maxCronVisible) break;
                String enabled = job.enabled() ? "enabled" : "disabled";
                String next = formatCronNext(now, cron, job);
                String kind = job.kind();
                String desc = normalizeStatusPanelField(job.description());
                lines.add(MUTED + "  " + TREE_PIPE_SPACE + " " + ICON_ITEM + " "
                        + job.id() + " [" + kind + "] "
                        + enabled + " next=" + next + " :: " + desc + RESET);
                shown++;
            }
            String finalRunningJobId = runningJobId;
            int remaining = (int) visibleJobs.stream()
                    .filter(j -> finalRunningJobId == null || !finalRunningJobId.equals(j.id()))
                    .count() - shown;
            if (remaining > 0) {
                lines.add(MUTED + "  " + TREE_PIPE_SPACE + "   +" + remaining
                        + " more cron job(s)" + RESET);
            }
        }

        // -- 4. Tasks (header + running tasks inline) --
        var runningTasks = taskManager.list().stream()
                .filter(TaskHandle::isRunning)
                .toList();
        var pending = permissionBridge.pendingSnapshot();
        lines.add(MUTED + "  " + TREE_BRANCH + " " + ICON_TASKS + " " + RESET
                + INFO + "tasks" + RESET
                + MUTED + " | running=" + runningTasks.size()
                + " | permissions=" + pending.size() + RESET);

        if (!runningTasks.isEmpty()) {
            int maxVisible = 2;
            int from = Math.max(0, runningTasks.size() - maxVisible);
            var visible = runningTasks.subList(from, runningTasks.size());
            String fgId = taskManager.foregroundTaskId();
            for (var task : visible) {
                String elapsed = formatDuration(Duration.between(task.startedAt(), now));
                String prefix = task.taskId().equals(fgId) ? "[fg] " : "";
                String summary = normalizeStatusPanelField(task.promptSummary());
                var runtime = deriveRuntimeInfo(task, now);
                String runtimeLabel = normalizeStatusPanelField(runtime.label());

                lines.add(MUTED + "       " + ICON_ITEM + " " + RESET
                        + runtime.color() + runtime.shortState() + RESET + " "
                        + INFO + "#" + task.taskId() + RESET + " "
                        + prefix + summary
                        + MUTED + "  " + runtimeLabel + " * " + elapsed + RESET);
            }
            int hidden = runningTasks.size() - visible.size();
            if (hidden > 0) {
                lines.add(MUTED + "         +" + hidden + " more running task(s)" + RESET);
            }
        }

        if (!pending.isEmpty()) {
            int maxPermVisible = 2;
            for (int i = 0; i < Math.min(maxPermVisible, pending.size()); i++) {
                var req = pending.get(i);
                String detail = normalizeStatusPanelField(req.description());
                lines.add(MUTED + "       " + ICON_ITEM + " " + RESET
                        + WARNING + "permission" + RESET + " "
                        + INFO + "#" + req.taskId() + RESET + " "
                        + detail);
            }
            if (pending.size() > maxPermVisible) {
                lines.add(MUTED + "         +" + (pending.size() - maxPermVisible)
                        + " more permission request(s)" + RESET);
            }
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
        String draftSummary = skillDraftStatusSummary(projectRoot);
        if (replaySummary == null && candidateSummary == null && releaseSummary == null && draftSummary == null) {
            return null;
        }

        var sb = new StringBuilder();
        sb.append(MUTED).append("  ").append(TREE_BRANCH).append(" ").append(ICON_LEARNING).append(" ").append(RESET)
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
        if (draftSummary != null) {
            sb.append(MUTED).append(" | ").append(RESET)
                    .append(MUTED).append("drafts=").append(draftSummary).append(RESET);
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

    private String skillDraftStatusSummary(Path projectRoot) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        var snapshot = getSkillDraftSnapshot(projectRoot);
        if (snapshot == null || snapshot.drafts().isEmpty()) {
            return null;
        }
        int pending = 0;
        int pass = 0;
        int hold = 0;
        int block = 0;
        for (var draft : snapshot.drafts().values()) {
            switch (draft.validationVerdict()) {
                case "pass" -> pass++;
                case "hold" -> hold++;
                case "block" -> block++;
                default -> pending++;
            }
        }
        return snapshot.drafts().size()
                + "(p:" + pass + ",h:" + hold + ",b:" + block + ",n:" + pending + ")";
    }

    private SkillDraftEvent parseSkillDraftEvent(JsonNode node) {
        var reasons = new ArrayList<String>();
        JsonNode arr = node.path("reasons");
        if (arr.isArray()) {
            for (JsonNode reason : arr) {
                String text = sanitizeTerminalText(reason.asText(""));
                if (!text.isBlank()) {
                    reasons.add(text);
                }
            }
        }
        return new SkillDraftEvent(
                node.path("type").asText(""),
                node.path("trigger").asText("manual"),
                sanitizeTerminalText(node.path("skillName").asText("")),
                sanitizeTerminalText(node.path("draftPath").asText("")),
                sanitizeTerminalText(node.path("candidateId").asText("")),
                sanitizeTerminalText(node.path("verdict").asText("")),
                sanitizeTerminalText(node.path("releaseStage").asText("")),
                node.path("paused").asBoolean(false),
                reasons
        );
    }

    private void renderSkillDraftEventNotice(SkillDraftEvent event) {
        if (event == null) {
            return;
        }
        switch (event.type()) {
            case "draft_created" -> {
                enqueueUiNotice(INFO + "skill draft created" + RESET + ": "
                        + fitWidth(event.skillName(), 36)
                        + (event.candidateId().isBlank() ? "" : " [" + fitWidth(event.candidateId(), 24) + "]"));
            }
            case "validation_changed" -> {
                String reason = event.reasons().isEmpty() ? event.verdict() : event.reasons().get(0);
                enqueueUiNotice(INFO + "skill draft " + event.verdict() + RESET + ": "
                        + fitWidth(event.skillName(), 32)
                        + " - " + fitWidth(reason, 68));
            }
            case "release_changed" -> {
                String suffix = event.paused() ? " (paused)" : "";
                enqueueUiNotice(INFO + "skill release" + RESET + ": "
                        + fitWidth(event.skillName(), 32)
                        + " -> " + fitWidth(event.releaseStage(), 16) + suffix);
            }
            default -> {
            }
        }
    }

    private SkillDraftSnapshot getSkillDraftSnapshot(Path projectRoot) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Instant now = Instant.now();
        if (cachedSkillDraftSnapshot != null
                && Duration.between(cachedSkillDraftSnapshotAt, now).compareTo(SKILL_DRAFT_REFRESH) < 0) {
            return cachedSkillDraftSnapshot;
        }
        SkillDraftSnapshot next = loadSkillDraftSnapshot(projectRoot);
        cachedSkillDraftSnapshotAt = now;
        cachedSkillDraftSnapshot = next;
        return next;
    }

    private SkillDraftSnapshot loadSkillDraftSnapshot(Path projectRoot) {
        Path draftsRoot = projectRoot.resolve(CL_DRAFTS_DIR);
        if (!Files.isDirectory(draftsRoot)) {
            return new SkillDraftSnapshot(Map.of());
        }

        Map<String, SkillValidationStatus> validations = loadSkillValidationStatuses(
                projectRoot.resolve(CL_METRICS_DIR).resolve(CL_VALIDATION_AUDIT));
        Map<String, SkillReleaseStatus> releases = loadSkillReleaseStatuses(
                projectRoot.resolve(CL_METRICS_DIR).resolve(CL_RELEASE_STATE));
        var drafts = new LinkedHashMap<String, SkillDraftRecord>();
        try (var paths = Files.walk(draftsRoot)) {
            for (Path draftFile : paths
                    .filter(Files::isRegularFile)
                    .filter(path -> "SKILL.md".equals(path.getFileName().toString()))
                    .sorted()
                    .toList()) {
                String draftPath = projectRoot.relativize(draftFile).toString().replace('\\', '/');
                Map<String, String> frontmatter = parseSkillFrontmatter(draftFile);
                String skillName = sanitizeTerminalText(
                        frontmatter.getOrDefault("name", draftFile.getParent().getFileName().toString()));
                var validation = validations.getOrDefault(draftPath, new SkillValidationStatus("pending", List.of()));
                var release = releases.getOrDefault(skillName.toLowerCase(), new SkillReleaseStatus("draft", false));
                boolean manualReviewNeeded = !"active".equalsIgnoreCase(release.stage());
                drafts.put(skillName.toLowerCase(), new SkillDraftRecord(
                        skillName,
                        sanitizeTerminalText(frontmatter.getOrDefault("description", "")),
                        draftPath,
                        sanitizeTerminalText(frontmatter.getOrDefault("source-candidate-id", "")),
                        sanitizeTerminalText(frontmatter.getOrDefault("allowed-tools", "")),
                        parseBoolean(frontmatter.get("disable-model-invocation")),
                        validation.verdict(),
                        validation.reasons(),
                        release.stage(),
                        release.paused(),
                        manualReviewNeeded
                ));
            }
        } catch (Exception e) {
            log.debug("Failed to load skill draft snapshot: {}", e.getMessage());
            return new SkillDraftSnapshot(Map.of());
        }
        return new SkillDraftSnapshot(drafts);
    }

    private Map<String, SkillValidationStatus> loadSkillValidationStatuses(Path auditPath) {
        var statuses = new LinkedHashMap<String, SkillValidationStatus>();
        if (!Files.isRegularFile(auditPath)) {
            return statuses;
        }
        try (var lines = Files.lines(auditPath)) {
            var iterator = lines.iterator();
            while (iterator.hasNext()) {
                String line = iterator.next();
                if (line == null || line.isBlank()) continue;
                JsonNode node = statusMapper.readTree(line);
                if (node == null) continue;
                String draftPath = node.path("draftPath").asText("");
                if (draftPath.isBlank()) continue;
                var reasons = new ArrayList<String>();
                JsonNode arr = node.path("reasons");
                if (arr.isArray()) {
                    for (JsonNode reason : arr) {
                        String code = reason.path("code").asText("");
                        String message = reason.path("message").asText("");
                        if (!code.isBlank() && !message.isBlank()) {
                            reasons.add(sanitizeTerminalText(code + ": " + message));
                        } else if (!code.isBlank()) {
                            reasons.add(sanitizeTerminalText(code));
                        } else if (!message.isBlank()) {
                            reasons.add(sanitizeTerminalText(message));
                        }
                    }
                }
                statuses.put(draftPath, new SkillValidationStatus(
                        sanitizeTerminalText(node.path("verdict").asText("pending")),
                        reasons
                ));
            }
        } catch (Exception e) {
            log.debug("Failed to parse skill draft validation audit: {}", e.getMessage());
        }
        return statuses;
    }

    private Map<String, SkillReleaseStatus> loadSkillReleaseStatuses(Path releaseStatePath) {
        var statuses = new LinkedHashMap<String, SkillReleaseStatus>();
        if (!Files.isRegularFile(releaseStatePath)) {
            return statuses;
        }
        try {
            JsonNode root = statusMapper.readTree(releaseStatePath.toFile());
            JsonNode releases = root == null ? null : root.path("releases");
            if (releases == null || !releases.isArray()) {
                return statuses;
            }
            for (JsonNode release : releases) {
                String skillName = release.path("skillName").asText("");
                if (skillName.isBlank()) continue;
                statuses.put(skillName.toLowerCase(), new SkillReleaseStatus(
                        release.path("stage").asText("draft"),
                        release.path("paused").asBoolean(false)
                ));
            }
        } catch (Exception e) {
            log.debug("Failed to parse skill release state: {}", e.getMessage());
        }
        return statuses;
    }

    private static Map<String, String> parseSkillFrontmatter(Path path) throws IOException {
        var map = new LinkedHashMap<String, String>();
        try (var reader = Files.newBufferedReader(path)) {
            String line = reader.readLine();
            if (line == null || !"---".equals(line.trim())) {
                return map;
            }
            while ((line = reader.readLine()) != null) {
                if ("---".equals(line.trim())) {
                    break;
                }
                int idx = line.indexOf(':');
                if (idx <= 0) continue;
                String key = line.substring(0, idx).trim().toLowerCase();
                String value = line.substring(idx + 1).trim();
                map.put(key, stripQuotes(value));
            }
        }
        return map;
    }

    private static boolean parseBoolean(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        return switch (stripQuotes(raw).trim().toLowerCase()) {
            case "true", "yes", "1" -> true;
            default -> false;
        };
    }

    private static String stripQuotes(String raw) {
        if (raw == null || raw.length() < 2) {
            return raw == null ? "" : raw;
        }
        if ((raw.startsWith("\"") && raw.endsWith("\""))
                || (raw.startsWith("'") && raw.endsWith("'"))) {
            return raw.substring(1, raw.length() - 1);
        }
        return raw;
    }

    private static String sanitizeTerminalText(String raw) {
        return sanitizeCronSummary(raw);
    }

    private static String sanitizeContextField(String raw) {
        String normalized = sanitizeTerminalText(raw);
        if (normalized.isBlank()) {
            return "";
        }
        return normalized.replace('\n', ' ')
                .replace('\r', ' ')
                .replace('\t', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String sanitizeContextContent(String raw) {
        String normalized = sanitizeTerminalText(raw);
        if (normalized.length() <= MAX_CONTEXT_DETAIL_CHARS) {
            return normalized;
        }
        return normalized.substring(0, MAX_CONTEXT_DETAIL_CHARS).stripTrailing()
                + "\n...[truncated]";
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

    /**
     * Returns the best current estimate of context window occupation (per-call input tokens).
     *
     * <p>Single source of truth: {@link ContextMonitor}. During streaming, the monitor
     * is updated in real-time via {@code stream.usage} notifications. After the turn,
     * it holds the last per-call value from {@code recordTurnComplete}.
     */
    private long effectiveContextTokens() {
        return contextMonitor.currentContextTokens();
    }

    private static String formatTokenCount(long tokens) {
        if (tokens < 1000) return String.valueOf(tokens);
        double k = tokens / 1000.0;
        if (k >= 100) return String.format("%.0fK", k);
        if (k >= 10) return String.format("%.0fK", k);
        return String.format("%.1fK", k);
    }

    /**
     * Builds a compact context usage bar with colored blocks for the status panel,
     * matching the visual style of the context usage display.
     *
     * <p>Format: {@code ctx ████░░░░ 10K/200K (5%)}
     */
    private static String buildContextBar(long inputTokens, int contextWindow) {
        if (contextWindow <= 0) return "";

        double pct = (double) inputTokens / contextWindow * 100.0;
        int barWidth = 10; // compact bar for status panel
        int filled = (int) Math.round(pct / 100.0 * barWidth);
        filled = Math.max(0, Math.min(filled, barWidth));
        int empty = barWidth - filled;

        // Color thresholds: green 0-60%, yellow 60-85%, red >85%
        String barColor;
        if (pct > 85.0) {
            barColor = ERROR;
        } else if (pct >= 60.0) {
            barColor = WARNING;
        } else {
            barColor = SUCCESS;
        }

        String filledBar = "\u2588".repeat(filled);
        String emptyBar = "\u2591".repeat(empty);
        // Show actual percentage even if >100% (context overflow is useful diagnostic info)
        String pctStr = String.format(java.util.Locale.ROOT, "%.0f", pct);

        return "ctx " + barColor + filledBar + MUTED + emptyBar + RESET
                + " " + formatTokenCount(inputTokens)
                + "/" + formatTokenCount(contextWindow)
                + " (" + pctStr + "%)";
    }

    private String buildContextPressureBadge() {
        return switch (contextMonitor.pressureLevel()) {
            case NORMAL -> null;
            case WATCH -> WARNING + "ctx-watch" + RESET;
            case COMPACT -> WARNING + "ctx-compact" + RESET;
            case CRITICAL -> ERROR + "ctx-critical" + RESET;
        };
    }

    private String buildCompactionBadge() {
        if (contextMonitor.compactionCount() <= 0) return null;
        return INFO + "cmp#" + contextMonitor.compactionCount() + RESET
                + MUTED + " " + contextMonitor.lastCompactionPhase()
                + " " + formatTokenCount(contextMonitor.lastCompactionOriginalTokens())
                + "->" + formatTokenCount(contextMonitor.lastCompactionCompactedTokens())
                + RESET;
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
                out.println(INFO + "  /context" + RESET + "  Inspect context composition (/context list | /context detail <key>)");
                out.println(INFO + "  /model" + RESET + "    Show current model (or /model <name> to switch)");
                out.println(INFO + "  /tools" + RESET + "    List available tools");
                out.println(INFO + "  /status" + RESET + "   Show session status");
                out.println(INFO + "  /learning" + RESET + " Show learning summary");
                out.println(INFO + "  /learning signals" + RESET + " Show recent reviewable learned signals");
                out.println(INFO + "  /learning reviews" + RESET + " Show recent human reviews");
                out.println(INFO + "  /learning review <action> <type> <id> [note]" + RESET + " Apply human review");
                out.println(INFO + "  /project" + RESET + "  Show current session project");
                out.println(INFO + "  /skills" + RESET + "   List generated skill drafts (/skills inspect <name>)");
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

            case "/context" -> handleContextCommand(out, arg);

            case "/model" -> handleModelCommand(out, arg);

            case "/tools" -> handleToolsCommand(out);

            case "/status" -> {
                out.println();
                out.println(BOLD + "Session Status" + RESET);
                out.printf("  %sModel:%s       %s%n", MUTED, RESET, effectiveModel);
                out.printf("  %sProject:%s     %s%n", MUTED, RESET, sessionInfo.project());
                String statusBranch = currentGitBranch();
                if (statusBranch != null) {
                    out.printf("  %sGit branch:%s  %s%n", MUTED, RESET, statusBranch);
                }
                long ctxTokens = effectiveContextTokens();
                out.printf("  %sContext:%s     %s / %s (%d%%)%n", MUTED, RESET,
                        formatTokenCount(ctxTokens),
                        formatTokenCount(sessionInfo.contextWindowTokens()),
                        sessionInfo.contextWindowTokens() > 0
                                ? ctxTokens * 100 / sessionInfo.contextWindowTokens() : 0);
                out.printf("  %sPressure:%s    %s%n", MUTED, RESET,
                        contextMonitor.pressureLevel().label());
                out.printf("  %sPeak:%s        %s | trend=%s | samples=%d%n", MUTED, RESET,
                        formatTokenCount(contextMonitor.peakContextTokens()),
                        contextMonitor.recentTrend().label(),
                        contextMonitor.sampleCount());
                out.printf("  %sPruning:%s     pruned=%d | summarized=%d%n", MUTED, RESET,
                        contextMonitor.prunedCount(),
                        contextMonitor.summarizedCount());
                if (contextMonitor.compactionCount() > 0) {
                    out.printf("  %sCompaction:%s  #%d %s %s -> %s%n", MUTED, RESET,
                            contextMonitor.compactionCount(),
                            contextMonitor.lastCompactionPhase(),
                            formatTokenCount(contextMonitor.lastCompactionOriginalTokens()),
                            formatTokenCount(contextMonitor.lastCompactionCompactedTokens()));
                }
                out.printf("  %sTotal usage:%s %s in / %s out%n", MUTED, RESET,
                        formatTokenCount(contextMonitor.totalInput()),
                        formatTokenCount(contextMonitor.totalOutput()));
                out.printf("  %sTasks:%s       %d running%n", MUTED, RESET,
                        taskManager.runningCount());
                out.println();
                out.flush();
            }

            case "/learning" -> handleLearningCommand(out, arg);

            case "/project" -> {
                out.println();
                out.println(BOLD + "Session Project" + RESET);
                out.printf("  %sProject:%s %s%n", MUTED, RESET, sessionInfo.project());
                out.println();
                out.flush();
            }

            case "/skills" -> handleSkillsCommand(out, arg);

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

    private void handleContextCommand(PrintWriter out, String arg) {
        if (client == null) {
            out.println(WARNING + "Not connected to daemon." + RESET);
            out.flush();
            return;
        }

        String mode = "list";
        String detailKey = "";
        if (arg != null && !arg.isBlank()) {
            String[] parts = arg.split("\\s+", 2);
            mode = parts[0].toLowerCase(java.util.Locale.ROOT);
            if ("detail".equals(mode)) {
                detailKey = parts.length > 1 ? parts[1].trim() : "";
                if (detailKey.isBlank()) {
                    out.println(WARNING + "Usage: /context detail <key>" + RESET);
                    out.flush();
                    return;
                }
            } else if (!"list".equals(mode)) {
                out.println(WARNING + "Usage: /context list | /context detail <key>" + RESET);
                out.flush();
                return;
            }
        }

        try {
            var params = client.objectMapper().createObjectNode();
            params.put("sessionId", sessionId);
            if (!detailKey.isBlank()) {
                params.put("detailKey", detailKey);
            }
            JsonNode root = client.sendRequest("context.inspect", params);
            out.println();
            if ("detail".equals(mode)) {
                renderContextDetail(out, root, detailKey);
            } else {
                renderContextList(out, root);
            }
            out.flush();
        } catch (Exception e) {
            out.println(WARNING + "Failed to inspect context: "
                    + sanitizeTerminalText(e.getMessage()) + RESET);
            out.flush();
        }
    }

    private void renderContextList(PrintWriter out, JsonNode root) {
        out.println(BOLD + "Context Overview" + RESET);
        out.printf("  %sSystem prompt:%s %s chars (~%s tokens)%n",
                MUTED, RESET,
                formatTokenCount(root.path("totalChars").asLong(0)),
                formatTokenCount(root.path("estimatedTokens").asLong(0)));
        out.printf("  %sBudget:%s        %s chars max | per-section %s%n",
                MUTED, RESET,
                formatTokenCount(root.path("budget").path("maxTotalChars").asLong(0)),
                formatTokenCount(root.path("budget").path("maxPerTierChars").asLong(0)));
        if (root.has("systemPromptSharePct")) {
            out.printf("  %sWindow share:%s  %.1f%%%n",
                    MUTED, RESET, root.path("systemPromptSharePct").asDouble(0.0));
        }
        out.printf("  %sLive context:%s %s / %s (%d%%) | pressure=%s%n",
                MUTED, RESET,
                formatTokenCount(effectiveContextTokens()),
                formatTokenCount(sessionInfo.contextWindowTokens()),
                sessionInfo.contextWindowTokens() > 0
                        ? effectiveContextTokens() * 100 / sessionInfo.contextWindowTokens() : 0,
                contextMonitor.pressureLevel().label());
        out.printf("  %sCompaction:%s    count=%d | pruned=%d | summarized=%d%n",
                MUTED, RESET,
                contextMonitor.compactionCount(),
                contextMonitor.prunedCount(),
                contextMonitor.summarizedCount());
        if (contextMonitor.compactionCount() > 0) {
            out.printf("  %sLast compact:%s  %s %s -> %s%n",
                    MUTED, RESET,
                    contextMonitor.lastCompactionPhase(),
                    formatTokenCount(contextMonitor.lastCompactionOriginalTokens()),
                    formatTokenCount(contextMonitor.lastCompactionCompactedTokens()));
        }

        JsonNode activePaths = root.path("activeFilePaths");
        if (activePaths.isArray() && !activePaths.isEmpty()) {
            out.printf("  %sActive paths:%s  %s%n",
                    MUTED, RESET, joinArrayValues(activePaths, 3));
        }

        JsonNode truncated = root.path("truncatedSectionKeys");
        if (truncated.isArray() && !truncated.isEmpty()) {
            out.printf("  %sTruncated:%s    %s%n",
                    MUTED, RESET, joinArrayValues(truncated, 6));
        }

        out.println();
        out.println(BOLD + "Sections" + RESET);
        for (JsonNode section : root.path("sections")) {
            String key = sanitizeContextField(section.path("key").asText("unknown"));
            long original = section.path("originalChars").asLong(0);
            long finalChars = section.path("finalChars").asLong(0);
            boolean truncatedSection = section.path("truncated").asBoolean(false);
            boolean protectedSection = section.path("protected").asBoolean(false);
            StringBuilder flags = new StringBuilder();
            if (truncatedSection) flags.append("truncated");
            if (protectedSection) {
                if (!flags.isEmpty()) flags.append(", ");
                flags.append("protected");
            }
            out.printf("  %s%-24s%s %6s -> %-6s p=%d%s%n",
                    INFO, fitWidth(key, 24), RESET,
                    formatTokenCount(original),
                    formatTokenCount(finalChars),
                    section.path("priority").asInt(0),
                    flags.isEmpty() ? "" : MUTED + " [" + flags + "]" + RESET);
        }
        out.println();
        out.println(MUTED + "Use /context detail <key> for full section content." + RESET);
    }

    private void renderContextDetail(PrintWriter out, JsonNode root, String detailKey) {
        JsonNode detail = root.path("detail");
        if (detail.isMissingNode() || detail.isNull()) {
            out.println(WARNING + "Context section not found: "
                    + sanitizeContextField(detailKey) + RESET);
            return;
        }

        out.println(BOLD + "Context Detail" + RESET);
        out.printf("  %sKey:%s          %s%n", MUTED, RESET,
                sanitizeContextField(detail.path("key").asText("")));
        out.printf("  %sPriority:%s     %d%n", MUTED, RESET, detail.path("priority").asInt(0));
        out.printf("  %sSize:%s         %s -> %s%n", MUTED, RESET,
                formatTokenCount(detail.path("originalChars").asLong(0)),
                formatTokenCount(detail.path("finalChars").asLong(0)));
        out.printf("  %sProtected:%s    %s%n", MUTED, RESET, detail.path("protected").asBoolean(false));
        out.printf("  %sTruncated:%s    %s%n", MUTED, RESET, detail.path("truncated").asBoolean(false));
        out.println();
        out.println(sanitizeContextContent(detail.path("content").asText("")));
    }

    private static String joinArrayValues(JsonNode values, int limit) {
        var rendered = new ArrayList<String>();
        for (int i = 0; i < values.size() && i < limit; i++) {
            rendered.add(sanitizeContextField(values.get(i).asText("")));
        }
        if (values.size() > limit) {
            rendered.add("+" + (values.size() - limit) + " more");
        }
        return String.join(", ", rendered);
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

    private void handleSkillsCommand(PrintWriter out, String arg) {
        if (sessionInfo.project() == null || sessionInfo.project().isBlank()) {
            out.println(WARNING + "No project root available for skill drafts." + RESET);
            out.flush();
            return;
        }
        final Path projectRoot;
        try {
            projectRoot = Path.of(sessionInfo.project());
        } catch (Exception e) {
            out.println(WARNING + "Invalid project path: " + e.getMessage() + RESET);
            out.flush();
            return;
        }

        if (arg == null || arg.isBlank() || "drafts".equalsIgnoreCase(arg)) {
            renderSkillDraftList(out, projectRoot, false);
            return;
        }
        if ("drafts pending".equalsIgnoreCase(arg) || "pending".equalsIgnoreCase(arg)) {
            renderSkillDraftList(out, projectRoot, true);
            return;
        }
        if (arg.regionMatches(true, 0, "inspect ", 0, "inspect ".length())) {
            renderSkillDraftInspect(out, projectRoot, arg.substring("inspect ".length()).trim());
            return;
        }

        out.println(WARNING + "Usage: /skills drafts | /skills drafts pending | /skills inspect <name>" + RESET);
        out.flush();
    }

    private void handleLearningCommand(PrintWriter out, String arg) {
        if (client == null || !client.isConnected()) {
            out.println(WARNING + "Not connected to daemon" + RESET);
            out.flush();
            return;
        }
        String trimmedArg = arg == null ? "" : arg.trim();
        if (trimmedArg.equalsIgnoreCase("signals")) {
            handleLearningSignalsCommand(out);
            return;
        }
        if (trimmedArg.equalsIgnoreCase("reviews")) {
            handleLearningReviewsCommand(out);
            return;
        }
        if (trimmedArg.equalsIgnoreCase("review")) {
            out.println(WARNING + "Usage: /learning review <action> <targetType> <targetId> [note]" + RESET);
            out.flush();
            return;
        }
        if (!trimmedArg.isBlank() && trimmedArg.regionMatches(true, 0, "review ", 0, "review ".length())) {
            handleLearningReviewApplyCommand(out, trimmedArg.substring("review ".length()).trim());
            return;
        }
        if (!trimmedArg.isBlank()) {
            out.println(WARNING + "Usage: /learning | /learning signals | /learning reviews | "
                    + "/learning review <action> <targetType> <targetId> [note]" + RESET);
            out.flush();
            return;
        }
        try {
            var params = client.objectMapper().createObjectNode();
            params.put("project", sessionInfo.project());
            params.put("limit", 8);
            JsonNode root = client.sendRequest("learning.summary", params);

            out.println();
            out.println(BOLD + "Learning Summary" + RESET);
            JsonNode maintenanceTotals = root.path("maintenanceTotals");
            out.printf("  %sMaintenance:%s deduped=%d merged=%d pruned=%d%n",
                    MUTED, RESET,
                    maintenanceTotals.path("deduped").asInt(0),
                    maintenanceTotals.path("merged").asInt(0),
                    maintenanceTotals.path("pruned").asInt(0));
            out.printf("  %sExplanations:%s %s%n",
                    MUTED, RESET, compactCountMap(root.path("explanationCounts")));
            out.printf("  %sValidations:%s %s%n",
                    MUTED, RESET, compactCountMap(root.path("validationCounts")));
            out.printf("  %sReviews:%s     %s%n",
                    MUTED, RESET, compactCountMap(root.path("reviewCounts")));

            out.println();
            out.println(BOLD + "Recent Maintenance" + RESET);
            renderLearningMaintenanceRuns(out, root.path("maintenanceRuns"));

            out.println();
            out.println(BOLD + "Recent Actions" + RESET);
            renderLearningActionRows(out, root.path("recentActions"), "No recent learning actions.");

            out.println();
            out.println(BOLD + "Recent Validations" + RESET);
            renderLearningValidationRows(out, root.path("recentValidations"));

            out.println();
            out.println(BOLD + "Recent Reviews" + RESET);
            renderLearningReviewRows(out, root.path("recentReviews"), "No human reviews yet.");
            out.println();
            out.flush();
        } catch (Exception e) {
            out.println(WARNING + "Failed to load learning summary: " + sanitizeTerminalText(e.getMessage()) + RESET);
            out.flush();
        }
    }

    private void handleLearningSignalsCommand(PrintWriter out) {
        try {
            var params = client.objectMapper().createObjectNode();
            params.put("project", sessionInfo.project());
            params.put("limit", 12);
            JsonNode root = client.sendRequest("learning.reviewable.list", params);
            out.println();
            out.println(BOLD + "Reviewable Learned Signals" + RESET);
            renderLearningSignals(out, root.path("signals"));
            out.println();
            out.flush();
        } catch (Exception e) {
            out.println(WARNING + "Failed to load reviewable signals: " + sanitizeTerminalText(e.getMessage()) + RESET);
            out.flush();
        }
    }

    private void handleLearningReviewsCommand(PrintWriter out) {
        try {
            var params = client.objectMapper().createObjectNode();
            params.put("project", sessionInfo.project());
            params.put("limit", 12);
            JsonNode root = client.sendRequest("learning.review.list", params);
            out.println();
            out.println(BOLD + "Human Reviews" + RESET);
            renderLearningReviewRows(out, root.path("reviews"), "No human reviews yet.");
            out.println();
            out.flush();
        } catch (Exception e) {
            out.println(WARNING + "Failed to load learning reviews: " + sanitizeTerminalText(e.getMessage()) + RESET);
            out.flush();
        }
    }

    private void handleLearningReviewApplyCommand(PrintWriter out, String arg) {
        String[] parts = arg.split("\\s+", 4);
        if (parts.length < 3) {
            out.println(WARNING + "Usage: /learning review <action> <targetType> <targetId> [note]" + RESET);
            out.flush();
            return;
        }
        try {
            var params = client.objectMapper().createObjectNode();
            params.put("project", sessionInfo.project());
            params.put("action", parts[0]);
            params.put("targetType", parts[1]);
            params.put("targetId", parts[2]);
            params.put("note", parts.length >= 4 ? parts[3] : "");
            params.put("reviewer", "cli");
            params.put("sessionId", sessionId);
            JsonNode result = client.sendRequest("learning.review.apply", params);
            out.println(INFO + sanitizeTerminalText(result.path("summary").asText("Review applied.")) + RESET);
            out.flush();
        } catch (Exception e) {
            out.println(WARNING + "Failed to apply learning review: " + sanitizeTerminalText(e.getMessage()) + RESET);
            out.flush();
        }
    }

    private void renderLearningMaintenanceRuns(PrintWriter out, JsonNode maintenanceRuns) {
        if (!maintenanceRuns.isArray() || maintenanceRuns.isEmpty()) {
            out.println("  " + MUTED + "No maintenance runs recorded yet." + RESET);
            return;
        }
        for (JsonNode run : maintenanceRuns) {
            out.printf("  %s%s%s  %s%n",
                    MUTED,
                    fitWidth(sanitizeTerminalText(run.path("trigger").asText("")), 18),
                    RESET,
                    fitWidth(sanitizeTerminalText(run.path("summary").asText("")), 96));
        }
    }

    private void renderLearningActionRows(PrintWriter out, JsonNode actions, String emptyMessage) {
        if (!actions.isArray() || actions.isEmpty()) {
            out.println("  " + MUTED + emptyMessage + RESET);
            return;
        }
        for (JsonNode action : actions) {
            out.printf("  %s%-24s%s %s%n",
                    INFO,
                    fitWidth(sanitizeTerminalText(action.path("actionType").asText("")), 24),
                    RESET,
                    fitWidth(sanitizeTerminalText(action.path("summary").asText("")), 96));
        }
    }

    private void renderLearningValidationRows(PrintWriter out, JsonNode validations) {
        if (!validations.isArray() || validations.isEmpty()) {
            out.println("  " + MUTED + "No recent learned-behavior validations." + RESET);
            return;
        }
        for (JsonNode validation : validations) {
            out.printf("  %s%-12s%s %s%n",
                    INFO,
                    fitWidth(sanitizeTerminalText(validation.path("verdict").asText("")), 12),
                    RESET,
                    fitWidth(sanitizeTerminalText(validation.path("summary").asText("")), 96));
        }
    }

    private void renderLearningReviewRows(PrintWriter out, JsonNode reviews, String emptyMessage) {
        if (!reviews.isArray() || reviews.isEmpty()) {
            out.println("  " + MUTED + emptyMessage + RESET);
            return;
        }
        for (JsonNode review : reviews) {
            out.printf("  %s%-12s%s %s%n",
                    INFO,
                    fitWidth(sanitizeTerminalText(review.path("action").asText("")), 12),
                    RESET,
                    fitWidth(sanitizeTerminalText(review.path("summary").asText("")), 96));
        }
    }

    private void renderLearningSignals(PrintWriter out, JsonNode signals) {
        if (!signals.isArray() || signals.isEmpty()) {
            out.println("  " + MUTED + "No recent reviewable learned signals." + RESET);
            return;
        }
        for (JsonNode signal : signals) {
            String left = sanitizeTerminalText(
                    signal.path("targetType").asText("") + ":" + signal.path("targetId").asText(""));
            String review = signal.path("reviewAction").asText("");
            String suffix = review.isBlank() ? "" : " [" + review + "]";
            out.printf("  %s%s%s %s%s%n",
                    INFO,
                    left,
                    RESET,
                    fitWidth(sanitizeTerminalText(signal.path("summary").asText("")), 72),
                    sanitizeTerminalText(suffix));
        }
    }

    private String compactCountMap(JsonNode node) {
        if (node == null || !node.isObject() || node.isEmpty()) {
            return "none";
        }
        var parts = new ArrayList<String>();
        node.fields().forEachRemaining(entry -> parts.add(entry.getKey() + "=" + entry.getValue().asInt(0)));
        return fitWidth(sanitizeTerminalText(String.join(", ", parts)), 96);
    }

    private void renderSkillDraftList(PrintWriter out, Path projectRoot, boolean pendingOnly) {
        var snapshot = getSkillDraftSnapshot(projectRoot);
        if (snapshot == null || snapshot.drafts().isEmpty()) {
            out.println();
            out.println(MUTED + "No generated skill drafts." + RESET);
            out.println();
            out.flush();
            return;
        }

        out.println();
        out.println(BOLD + (pendingOnly ? "Pending Skill Drafts" : "Skill Drafts") + RESET);
        var visibleDrafts = snapshot.drafts().values().stream()
                .filter(draft -> !pendingOnly || draft.manualReviewNeeded())
                .sorted((left, right) -> left.skillName().compareToIgnoreCase(right.skillName()))
                .toList();
        if (visibleDrafts.isEmpty()) {
            out.println(MUTED + "No pending manual-review drafts." + RESET);
            out.println();
            out.flush();
            return;
        }
        for (var draft : visibleDrafts) {
            String paused = draft.paused() ? " paused" : "";
            out.printf("  %s%s%s  %scandidate=%s%s  %sverdict=%s%s  %srelease=%s%s%s%n",
                    BOLD, draft.skillName(), RESET,
                    MUTED, fitWidth(draft.candidateId(), 20), RESET,
                    MUTED, draft.validationVerdict(), RESET,
                    MUTED, draft.releaseStage(), paused, RESET);
            out.printf("      %smanual-review=%s%s%n", MUTED,
                    draft.manualReviewNeeded() ? "yes" : "no", RESET);
            if (!draft.description().isBlank()) {
                out.printf("      %s%s%s%n", MUTED, fitWidth(draft.description(), 96), RESET);
            }
        }
        out.println();
        out.flush();
    }

    private void renderSkillDraftInspect(PrintWriter out, Path projectRoot, String name) {
        if (name == null || name.isBlank()) {
            out.println(WARNING + "Usage: /skills inspect <name>" + RESET);
            out.flush();
            return;
        }

        var snapshot = getSkillDraftSnapshot(projectRoot);
        if (snapshot == null || snapshot.drafts().isEmpty()) {
            out.println(MUTED + "No generated skill drafts." + RESET);
            out.flush();
            return;
        }

        SkillDraftRecord selected = snapshot.drafts().values().stream()
                .filter(draft -> draft.skillName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
        if (selected == null) {
            out.println(WARNING + "Skill draft not found: " + name + RESET);
            out.flush();
            return;
        }

        out.println();
        out.println(BOLD + "Skill Draft" + RESET);
        out.printf("  %sName:%s        %s%n", MUTED, RESET, selected.skillName());
        out.printf("  %sDraft path:%s  %s%n", MUTED, RESET, selected.draftPath());
        out.printf("  %sCandidate:%s   %s%n", MUTED, RESET, selected.candidateId());
        out.printf("  %sVerdict:%s     %s%n", MUTED, RESET, selected.validationVerdict());
        out.printf("  %sRelease:%s     %s%s%n", MUTED, RESET, selected.releaseStage(),
                selected.paused() ? " (paused)" : "");
        out.printf("  %sReview:%s      %s%n", MUTED, RESET,
                selected.manualReviewNeeded() ? "manual review needed" : "no manual review needed");
        out.printf("  %sModel inv:%s   %s%n", MUTED, RESET,
                selected.disableModelInvocation() ? "disabled" : "enabled");
        if (!selected.allowedTools().isBlank()) {
            out.printf("  %sTools:%s       %s%n", MUTED, RESET, selected.allowedTools());
        }
        if (!selected.description().isBlank()) {
            out.printf("  %sSummary:%s     %s%n", MUTED, RESET, selected.description());
        }
        if (!selected.validationReasons().isEmpty()) {
            out.printf("  %sReasons:%s%n", MUTED, RESET);
            for (String reason : selected.validationReasons()) {
                out.printf("    %s- %s%s%n", MUTED, fitWidth(reason, 100), RESET);
            }
        }
        out.println();
        out.flush();
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
            fgSink.detach();
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

        // Suspend JLine's Status widget so its scroll region doesn't go
        // stale while ForegroundOutputSink writes directly to the terminal.
        suspendStatusPanel();
        ForegroundOutputSink fgSink = null;
        try {
            // Create new foreground sink and swap it in atomically
            fgSink = new ForegroundOutputSink(out, markdownRenderer, activeTerminal, contextMonitor, this::requestUiRender);
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
        } finally {
            // Restore JLine's Status widget so it recalculates scroll region
            // based on the terminal's current state after task output.
            restoreStatusPanel();
        }
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
        String noAnsi = stripTerminalEscapeSequences(raw);
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

    private static String stripTerminalEscapeSequences(String value) {
        String noCsi = ANSI_CSI_PATTERN.matcher(value).replaceAll("");
        var sb = new StringBuilder(noCsi.length());
        int i = 0;
        while (i < noCsi.length()) {
            char ch = noCsi.charAt(i);
            if (ch != '\u001B') {
                sb.append(ch);
                i++;
                continue;
            }

            if (i + 1 >= noCsi.length()) {
                i++;
                continue;
            }

            char next = noCsi.charAt(i + 1);
            if (next == ']') {
                i += 2;
                while (i < noCsi.length()) {
                    char seq = noCsi.charAt(i++);
                    if (seq == '\u0007') {
                        break;
                    }
                    if (seq == '\u001B' && i < noCsi.length() && noCsi.charAt(i) == '\\') {
                        i++;
                        break;
                    }
                }
                continue;
            }

            i += 2;
        }
        return sb.toString();
    }

    private static String normalizeStatusPanelField(String raw) {
        String normalized = sanitizeCronSummary(raw);
        if (normalized.isBlank()) {
            return "";
        }
        String singleLine = normalized.replace('\n', ' ')
                .replace('\r', ' ')
                .replace('\t', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        return fitWidth(singleLine, STATUS_PANEL_FIELD_MAX_COLS);
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
            var sb = new AttributedStringBuilder();
            sb.append(as);
            sb.append(AttributedString.fromAnsi("\u001B[K"));
            return sb.toAttributedString();
        }
        // Too long — truncate and pad
        var sb = new AttributedStringBuilder();
        sb.append(as.columnSubSequence(0, Math.max(0, safeWidth - 3)));
        sb.append("...");
        sb.append(AttributedString.fromAnsi("\u001B[K"));
        return sb.toAttributedString();
    }
}

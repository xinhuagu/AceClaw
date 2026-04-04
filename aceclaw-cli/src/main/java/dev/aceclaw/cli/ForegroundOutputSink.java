package dev.aceclaw.cli;

import com.fasterxml.jackson.databind.JsonNode;

import org.jline.terminal.Terminal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static dev.aceclaw.cli.TerminalTheme.*;

/**
 * Renders streaming agent events directly to the terminal.
 *
 * <p>All writes to {@code out} are synchronized to prevent interleaved output
 * from multiple task threads. This sink also renders a compact multi-line
 * status area under the active streaming line for tools, sub-agents, and plan steps.
 */
public final class ForegroundOutputSink implements OutputSink {
    private static final Logger log = LoggerFactory.getLogger(ForegroundOutputSink.class);

    /**
     * Tool panel lines (⏳/✅) are noisy on some terminals because cursor restore behavior
     * differs across environments. Keep it opt-in; default to trace-only tool logs.
     */
    private static final boolean TOOL_STATUS_PANEL_ENABLED =
            Boolean.parseBoolean(System.getenv().getOrDefault("ACECLAW_TOOL_STATUS_PANEL", "false"));

    private final PrintWriter out;
    private final TerminalMarkdownRenderer markdownRenderer;
    private final Object lock = new Object();

    private final StringBuilder textBuffer = new StringBuilder();
    private boolean receivedTextOutput = false;
    private boolean wasThinking = false;
    private boolean inCodeFence = false;
    private int backtickRun = 0;  // tracks consecutive backticks across chunk boundaries
    private volatile TerminalSpinner spinner;

    private final StreamStatusRenderer statusRenderer;
    private final ContextMonitor contextMonitor;
    private final Runnable uiRenderCallback;

    public ForegroundOutputSink(PrintWriter out, TerminalMarkdownRenderer markdownRenderer) {
        this(out, markdownRenderer, null, null, null);
    }

    public ForegroundOutputSink(PrintWriter out, TerminalMarkdownRenderer markdownRenderer,
                                Terminal terminal) {
        this(out, markdownRenderer, terminal, null, null);
    }

    public ForegroundOutputSink(PrintWriter out, TerminalMarkdownRenderer markdownRenderer,
                                Terminal terminal, ContextMonitor contextMonitor) {
        this(out, markdownRenderer, terminal, contextMonitor, null);
    }

    public ForegroundOutputSink(PrintWriter out, TerminalMarkdownRenderer markdownRenderer,
                                Terminal terminal, ContextMonitor contextMonitor,
                                Runnable uiRenderCallback) {
        this.out = Objects.requireNonNull(out, "out");
        this.markdownRenderer = Objects.requireNonNull(markdownRenderer, "markdownRenderer");
        this.statusRenderer = new StreamStatusRenderer(out);
        this.contextMonitor = contextMonitor;
        this.uiRenderCallback = uiRenderCallback;
    }

    /**
     * Starts the initial thinking spinner. Called once when the prompt is sent.
     */
    public void startThinkingSpinner() {
        synchronized (lock) {
            spinner = new TerminalSpinner(out, TerminalSpinner.Style.MOON);
            spinner.start("Thinking...");
        }
    }

    @Override
    public void onThinkingDelta(String delta) {
        synchronized (lock) {
            stopSpinnerInternal();
            statusRenderer.hide();
            out.print(THINKING + delta + RESET);
            out.flush();
            wasThinking = true;
        }
    }

    @Override
    public void onTextDelta(String delta) {
        synchronized (lock) {
            stopSpinnerInternal();
            statusRenderer.hide();

            if (wasThinking) {
                out.println();
                out.println();
                wasThinking = false;
            }

            textBuffer.append(delta);
            receivedTextOutput = true;

            // Track code fence state across chunk boundaries
            for (int i = 0; i < delta.length(); i++) {
                if (delta.charAt(i) == '`') {
                    backtickRun++;
                    if (backtickRun == 3) {
                        inCodeFence = !inCodeFence;
                        backtickRun = 0;
                    }
                } else {
                    backtickRun = 0;
                }
            }

            // Render complete paragraph blocks
            if (!inCodeFence) {
                int boundary;
                while ((boundary = textBuffer.indexOf("\n\n")) != -1) {
                    String block = textBuffer.substring(0, boundary + 2);
                    textBuffer.delete(0, boundary + 2);
                    markdownRenderer.render(block, out);
                    out.flush();
                }
            }
        }
    }

    @Override
    public void onToolUse(String toolName) {
        onToolUse("", toolName, "");
    }

    @Override
    public void onToolUse(String toolId, String toolName, String summary) {
        synchronized (lock) {
            if (receivedTextOutput) {
                statusRenderer.hide();
                flushMarkdown();
                inCodeFence = false;
                receivedTextOutput = false;
            }
            if (wasThinking) {
                statusRenderer.hide();
                out.println();
                wasThinking = false;
            }
            stopSpinnerInternal();
            emitToolTraceStart(toolName, summary);
            if (TOOL_STATUS_PANEL_ENABLED) {
                statusRenderer.onToolStarted(toolId, toolName, summary);
            }
        }
    }

    @Override
    public void onToolCompleted(String toolId, String toolName,
                                long durationMs, boolean isError, String error) {
        synchronized (lock) {
            emitToolTraceCompleted(toolName, durationMs, isError, error);
            if (TOOL_STATUS_PANEL_ENABLED) {
                statusRenderer.onToolCompleted(toolId, toolName, durationMs, isError, error);
            }
        }
    }

    @Override
    public void onStreamError(String error) {
        synchronized (lock) {
            if (receivedTextOutput) {
                statusRenderer.hide();
                flushMarkdown();
                inCodeFence = false;
                receivedTextOutput = false;
            }
            stopSpinnerInternal();
            statusRenderer.hide();
            out.printf("%s[stream error: %s]%s%n", ERROR, error, RESET);
            out.flush();
            statusRenderer.refresh();
        }
    }

    @Override
    public void onStreamCancelled() {
        synchronized (lock) {
            stopSpinnerInternal();
            statusRenderer.onCancelled();
        }
    }

    @Override
    public void onUsageUpdate(long inputTokens, long contextWindow) {
        synchronized (lock) {
            if (contextMonitor != null) {
                contextMonitor.recordStreamingUsage(inputTokens);
                contextMonitor.checkThresholds(log);
            }
        }
        // Trigger status bar refresh outside the lock to show updated context %
        if (uiRenderCallback != null) {
            uiRenderCallback.run();
        }
    }

    @Override
    public void onTurnComplete(JsonNode message, boolean hasError) {
        synchronized (lock) {
            stopSpinnerInternal();
            statusRenderer.hide();
            // Context usage is shown in the status panel — no separate bottom bar.

            if (receivedTextOutput) {
                flushMarkdown();
            }

            if (hasError) {
                JsonNode error = message.get("error");
                if (error != null) {
                    int code = error.path("code").asInt(-1);
                    String errorMessage = error.path("message").asText("Unknown error");
                    if (code == -32601) {
                        out.println(ERROR + "[Agent not available. Is the daemon configured correctly?]" + RESET);
                    } else {
                        out.printf("%sError: %s%s%n", ERROR, errorMessage, RESET);
                    }
                }
            } else {
                JsonNode result = message.get("result");
                if (result != null && !receivedTextOutput) {
                    if (result.has("response")) {
                        var response = result.get("response").asText();
                        if (!response.isEmpty()) {
                            markdownRenderer.render(response, out);
                        }
                    }
                }
            }
            out.flush();
            statusRenderer.clearAll();

            // Reset state for next turn
            textBuffer.setLength(0);
            receivedTextOutput = false;
            wasThinking = false;
            inCodeFence = false;
            backtickRun = 0;
        }
    }

    @Override
    public void onConnectionClosed() {
        synchronized (lock) {
            stopSpinnerInternal();
            statusRenderer.hide();
            flushMarkdown();
            out.println("\n[Connection closed]");
            out.flush();
            statusRenderer.clearAll();
        }
    }

    @Override
    public void onCompaction(JsonNode params) {
        if (contextMonitor != null && params != null) {
            contextMonitor.recordCompaction(
                    params.path("originalTokens").asLong(0),
                    params.path("compactedTokens").asLong(0),
                    params.path("phase").asText("UNKNOWN"));
        }
        synchronized (lock) {
            statusRenderer.hide();
            out.println(MUTED + "[context compacted]" + RESET);
            out.flush();
            statusRenderer.refresh();
        }
        if (uiRenderCallback != null) {
            uiRenderCallback.run();
        }
    }

    @Override
    public void onBudgetExhausted(JsonNode params) {
        synchronized (lock) {
            stopSpinnerInternal();
            statusRenderer.hide();
            String reason = params != null ? params.path("reason").asText("unknown") : "unknown";
            out.printf("%s[budget exhausted: %s]%s%n", ERROR, reason, RESET);
            out.flush();
            statusRenderer.onCancelled();
        }
    }

    @Override
    public void onPlanCreated(JsonNode params) {
        synchronized (lock) {
            if (params == null) return;
            stopSpinnerInternal();
            statusRenderer.hide();

            if (receivedTextOutput) {
                flushMarkdown();
                inCodeFence = false;
                receivedTextOutput = false;
            }
            if (wasThinking) {
                out.println();
                wasThinking = false;
            }

            String goal = truncate(params.path("goal").asText(""), 120);
            int stepCount = params.path("stepCount").asInt(0);
            boolean resumed = params.path("resumed").asBoolean(false);

            out.println();
            if (resumed) {
                int resumedFrom = params.path("resumedFromStep").asInt(1);
                out.println(BOLD + ACCENT + BOX_LIGHT_TOP_LEFT + BOX_LIGHT_HORIZONTAL
                        + " Plan (resumed from step " + resumedFrom + ", "
                        + stepCount + " remaining)" + RESET);
            } else {
                out.println(BOLD + ACCENT + BOX_LIGHT_TOP_LEFT + BOX_LIGHT_HORIZONTAL
                        + " Plan (" + stepCount + " steps)" + RESET);
            }
            if (!goal.isEmpty()) {
                out.println(ACCENT + BOX_LIGHT_VERTICAL + RESET + " " + MUTED + goal + RESET);
            }

            JsonNode steps = params.get("steps");
            if (steps != null && steps.isArray()) {
                for (JsonNode step : steps) {
                    int index = step.path("index").asInt(0);
                    String name = truncate(step.path("name").asText(""), 80);
                    out.println(ACCENT + BOX_LIGHT_VERTICAL + RESET + "  "
                            + MUTED + index + ". " + RESET + name);
                }
            }
            out.println(ACCENT + BOX_LIGHT_BOTTOM_LEFT + BOX_LIGHT_HORIZONTAL + RESET);
            out.println();
            out.flush();
        }
    }

    @Override
    public void onPlanStepStarted(JsonNode params) {
        synchronized (lock) {
            if (params == null) return;
            statusRenderer.hide();

            int stepIndex = params.path("stepIndex").asInt(0);
            int totalSteps = params.path("totalSteps").asInt(0);
            String stepName = params.path("stepName").asText("step");

            out.println(ACCENT + BOLD + "[plan] " + RESET + ACCENT
                    + "step " + stepIndex + "/" + totalSteps
                    + " - " + truncate(stepName, 80) + RESET);
            out.flush();
            statusRenderer.onPlanStepStarted(params);
        }
    }

    @Override
    public void onPlanStepCompleted(JsonNode params) {
        synchronized (lock) {
            if (params == null) return;
            statusRenderer.hide();

            int stepIndex = params.path("stepIndex").asInt(0);
            String stepName = params.path("stepName").asText("step");
            boolean success = params.path("success").asBoolean(true);
            long durationMs = params.path("durationMs").asLong(0);
            String elapsed = String.format(Locale.ROOT, "%.1fs", Math.max(0L, durationMs) / 1000.0);

            if (success) {
                out.println(SUCCESS + CHECKMARK + " step " + stepIndex
                        + " done" + RESET + MUTED + " (" + elapsed + ") "
                        + truncate(stepName, 60) + RESET);
            } else {
                out.println(ERROR + "x step " + stepIndex
                        + " failed" + RESET + MUTED + " (" + elapsed + ") "
                        + truncate(stepName, 60) + RESET);
            }
            out.flush();
            statusRenderer.onPlanStepCompleted(params);
        }
    }

    @Override
    public void onPlanCompleted(JsonNode params) {
        synchronized (lock) {
            if (params == null) return;
            statusRenderer.hide();

            boolean success = params.path("success").asBoolean(true);
            if (success) {
                out.println(SUCCESS + BOLD + "[plan] complete" + RESET);
            } else {
                out.println(ERROR + BOLD + "[plan] failed" + RESET);
            }
            out.println();
            out.flush();
            statusRenderer.onPlanCompleted(params);
        }
    }

    @Override
    public void onSubAgentStart(JsonNode params) {
        synchronized (lock) {
            statusRenderer.onSubAgentStart(params);
        }
    }

    @Override
    public void onSubAgentEnd(JsonNode params) {
        synchronized (lock) {
            statusRenderer.onSubAgentEnd(params);
        }
    }

    /**
     * Fully detaches this sink from the foreground, cleaning up all visual artifacts.
     * Called when a task is backgrounded (/bg or auto-background).
     */
    public void detach() {
        synchronized (lock) {
            stopSpinnerInternal();
            statusRenderer.hide();
        }
    }

    /**
     * Stops the active spinner if one is running.
     * Synchronized because this can be called from the signal handler thread (Ctrl+C).
     */
    public void stopSpinner() {
        synchronized (lock) {
            stopSpinnerInternal();
        }
    }

    /** Must be called while holding {@code lock}. */
    private void stopSpinnerInternal() {
        var s = spinner;
        if (s != null && s.isSpinning()) {
            s.clear();
            spinner = null;
        }
    }

    private void flushMarkdown() {
        if (textBuffer.isEmpty()) return;
        markdownRenderer.render(textBuffer.toString(), out);
        out.flush();
        textBuffer.setLength(0);
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        if (maxLen <= 0) return "";
        if (maxLen <= 3) return "...".substring(0, maxLen);
        String normalized = text.replace('\n', ' ').trim();
        if (normalized.length() <= maxLen) return normalized;
        return normalized.substring(0, maxLen - 3) + "...";
    }

    private void emitToolTraceStart(String toolName, String summary) {
        statusRenderer.hide();
        String label = safeName(toolName);
        String detail = truncate(summary, 90);
        if (detail.isBlank()) {
            out.println(MUTED + "[tool:start] " + label + RESET);
        } else {
            out.println(MUTED + "[tool:start] " + label + " - " + detail + RESET);
        }
        out.flush();
    }

    private void emitToolTraceCompleted(String toolName, long durationMs, boolean isError, String error) {
        statusRenderer.hide();
        String label = safeName(toolName);
        String elapsed = String.format(Locale.ROOT, "%.1fs", Math.max(0L, durationMs) / 1000.0);
        if (isError) {
            String reason = truncate(error, 90);
            if (reason.isBlank()) {
                out.println(MUTED + "[tool:error] " + label + " (" + elapsed + ")" + RESET);
            } else {
                out.println(MUTED + "[tool:error] " + label + " (" + elapsed + ") - " + reason + RESET);
            }
        } else {
            out.println(MUTED + "[tool:done] " + label + " (" + elapsed + ")" + RESET);
        }
        out.flush();
    }

    private static String safeName(String toolName) {
        if (toolName == null || toolName.isBlank()) return "unknown";
        return toolName;
    }

    /**
     * Lightweight status renderer for tool/sub-agent/plan progress lines.
     *
     * <p>Uses save/restore cursor + line clear ANSI sequences so status lines
     * stay below the streaming cursor and disappear cleanly.
     */
    private static final class StreamStatusRenderer {

        private static final long RETAIN_DONE_MS = 2000L;

        private enum Kind { TOOL, SUBAGENT, PLAN_STEP }
        private enum State { ACTIVE, SUCCESS, ERROR, CANCELLED }

        private static final class StatusEntry {
            final String key;
            final Kind kind;
            String name;
            String detail;
            final long startedNanos;
            State state;
            long durationMs;
            long expiresAtMs;
            String parentKey;

            StatusEntry(String key, Kind kind, String name, String detail, long startedNanos) {
                this.key = key;
                this.kind = kind;
                this.name = name;
                this.detail = detail;
                this.startedNanos = startedNanos;
                this.state = State.ACTIVE;
            }
        }

        private final PrintWriter out;
        private final LinkedHashMap<String, StatusEntry> entries = new LinkedHashMap<>();
        private int renderedLines;
        private long nextSyntheticId;

        StreamStatusRenderer(PrintWriter out) {
            this.out = out;
        }

        void onToolStarted(String toolId, String toolName, String summary) {
            pruneExpired();
            String parent = firstActiveKey(Kind.SUBAGENT);
            String normalizedToolName = safe(toolName, "unknown");

            StatusEntry entry = null;
            if (toolId != null && !toolId.isBlank()) {
                entry = entries.get(toolId);
            }
            if (entry == null) {
                entry = firstReusableTool(normalizedToolName);
            }

            if (entry == null) {
                String key = (toolId != null && !toolId.isBlank())
                        ? toolId : "tool-" + (++nextSyntheticId);
                entry = new StatusEntry(key, Kind.TOOL, normalizedToolName,
                        truncate(summary, 60), System.nanoTime());
                entries.put(key, entry);
            } else {
                entry.name = normalizedToolName;
                entry.detail = truncate(summary, 60);
                entry.state = State.ACTIVE;
                entry.durationMs = 0L;
                entry.expiresAtMs = 0L;
            }
            entry.parentKey = parent;
            redraw();
        }

        void onToolCompleted(String toolId, String toolName,
                             long durationMs, boolean isError, String error) {
            pruneExpired();
            StatusEntry entry = null;
            if (toolId != null && !toolId.isBlank()) {
                entry = entries.get(toolId);
            }
            if (entry == null) {
                entry = firstActiveTool(safe(toolName, "unknown"));
            }
            if (entry == null) {
                String key = (toolId != null && !toolId.isBlank())
                        ? toolId : "tool-" + (++nextSyntheticId);
                entry = new StatusEntry(key, Kind.TOOL, safe(toolName, "unknown"),
                        "", System.nanoTime());
                entries.put(key, entry);
            }
            entry.state = isError ? State.ERROR : State.SUCCESS;
            entry.durationMs = Math.max(0L, durationMs);
            entry.expiresAtMs = System.currentTimeMillis() + RETAIN_DONE_MS;
            if (isError && error != null && !error.isBlank()) {
                entry.detail = truncate(error, 80);
            }
            redraw();
        }

        void onSubAgentStart(JsonNode params) {
            pruneExpired();
            String agentId = safe(params != null ? params.path("agentType").asText("") : "", "sub-agent");
            String prompt = params != null ? params.path("prompt").asText("") : "";
            String key = "subagent:" + agentId;
            var entry = new StatusEntry(key, Kind.SUBAGENT, agentId,
                    truncate(prompt, 60), System.nanoTime());
            entries.put(key, entry);
            redraw();
        }

        void onSubAgentEnd(JsonNode params) {
            pruneExpired();
            String agentId = safe(params != null ? params.path("agentType").asText("") : "", "sub-agent");
            String key = "subagent:" + agentId;
            var entry = entries.get(key);
            if (entry == null) {
                entry = new StatusEntry(key, Kind.SUBAGENT, agentId, "", System.nanoTime());
                entries.put(key, entry);
            }
            entry.state = State.SUCCESS;
            entry.durationMs = nanosToMs(System.nanoTime() - entry.startedNanos);
            entry.expiresAtMs = System.currentTimeMillis() + RETAIN_DONE_MS;
            redraw();
        }

        void onPlanStepStarted(JsonNode params) {
            pruneExpired();
            if (params == null) return;
            String stepId = safe(params.path("stepId").asText(""), "step-" + (++nextSyntheticId));
            int stepIndex = params.path("stepIndex").asInt(0);
            int totalSteps = params.path("totalSteps").asInt(0);
            String stepName = params.path("stepName").asText("step");
            String detail = stepIndex > 0 && totalSteps > 0
                    ? "step %d/%d - \"%s\"".formatted(stepIndex, totalSteps, truncate(stepName, 40))
                    : truncate(stepName, 50);
            String key = "plan:" + stepId;
            entries.put(key, new StatusEntry(key, Kind.PLAN_STEP, "Plan", detail, System.nanoTime()));
            redraw();
        }

        void onPlanStepCompleted(JsonNode params) {
            pruneExpired();
            if (params == null) return;
            String stepId = safe(params.path("stepId").asText(""), "");
            String key = "plan:" + stepId;
            var entry = entries.get(key);
            if (entry == null) {
                String stepName = params.path("stepName").asText("step");
                entry = new StatusEntry(key, Kind.PLAN_STEP, "Plan",
                        truncate(stepName, 50), System.nanoTime());
                entries.put(key, entry);
            }
            boolean success = params.path("success").asBoolean(true);
            entry.state = success ? State.SUCCESS : State.ERROR;
            entry.durationMs = Math.max(0L, params.path("durationMs").asLong(0L));
            entry.expiresAtMs = System.currentTimeMillis() + RETAIN_DONE_MS;
            redraw();
        }

        void onPlanCompleted(JsonNode params) {
            pruneExpired();
            if (params == null) return;
            boolean success = params.path("success").asBoolean(true);
            for (var entry : entries.values()) {
                if (entry.kind == Kind.PLAN_STEP && entry.state == State.ACTIVE) {
                    entry.state = success ? State.SUCCESS : State.ERROR;
                    entry.durationMs = nanosToMs(System.nanoTime() - entry.startedNanos);
                    entry.expiresAtMs = System.currentTimeMillis() + RETAIN_DONE_MS;
                }
            }
            redraw();
        }

        void onCancelled() {
            pruneExpired();
            long expires = System.currentTimeMillis() + RETAIN_DONE_MS;
            for (var entry : entries.values()) {
                if (entry.state == State.ACTIVE) {
                    entry.state = State.CANCELLED;
                    entry.durationMs = nanosToMs(System.nanoTime() - entry.startedNanos);
                    entry.expiresAtMs = expires;
                }
            }
            redraw();
        }

        void hide() {
            if (renderedLines == 0) return;
            out.print("\0337");
            for (int i = 0; i < renderedLines; i++) {
                out.print("\n\r\033[K");
            }
            out.print("\033[" + renderedLines + "A\r");
            out.print("\0338");
            out.flush();
            renderedLines = 0;
        }

        void refresh() {
            pruneExpired();
            redraw();
        }

        void clearAll() {
            entries.clear();
            hide();
        }

        private void pruneExpired() {
            long now = System.currentTimeMillis();
            entries.values().removeIf(e -> e.state != State.ACTIVE && e.expiresAtMs > 0 && now >= e.expiresAtMs);
        }

        private void redraw() {
            var lines = new java.util.ArrayList<String>(entries.size());
            for (var entry : entries.values()) {
                lines.add(formatLine(entry));
            }
            int linesToClear = Math.max(renderedLines, lines.size());
            if (linesToClear == 0) {
                renderedLines = 0;
                return;
            }

            out.print("\0337");
            for (int i = 0; i < linesToClear; i++) {
                out.print("\n\r\033[K");
            }
            out.print("\033[" + linesToClear + "A\r");
            for (var line : lines) {
                out.print("\n\r\033[K");
                out.print(line);
            }
            out.print("\0338");
            out.flush();
            renderedLines = lines.size();
        }

        private String formatLine(StatusEntry entry) {
            String icon = switch (entry.state) {
                case ACTIVE -> "\u23F3";
                case SUCCESS -> "\u2705";
                case ERROR -> "\u274C";
                case CANCELLED -> "\u26D4";
            };
            String color = switch (entry.state) {
                case ACTIVE, CANCELLED -> WARNING;
                case SUCCESS -> SUCCESS;
                case ERROR -> ERROR;
            };
            String tail = switch (entry.state) {
                case ACTIVE -> formatSeconds(nanosToMs(System.nanoTime() - entry.startedNanos));
                case SUCCESS, ERROR -> formatSeconds(entry.durationMs);
                case CANCELLED -> "cancelled";
            };

            var sb = new StringBuilder();
            if (entry.kind == Kind.TOOL && entry.parentKey != null && isEntryVisible(entry.parentKey)) {
                sb.append("   ").append(MUTED).append("\u2514\u2500 ").append(RESET).append("\uD83D\uDD27 ");
            }
            sb.append(color).append(icon).append(RESET).append(" ");

            switch (entry.kind) {
                case TOOL -> {
                    sb.append(entry.name);
                    if (entry.detail != null && !entry.detail.isBlank()) {
                        sb.append(": ").append(entry.detail);
                    }
                }
                case SUBAGENT -> {
                    sb.append("\uD83E\uDD16 ").append("Sub-agent");
                    String label = entry.detail != null && !entry.detail.isBlank() ? entry.detail : entry.name;
                    if (!label.isBlank()) {
                        sb.append(": ").append(label);
                    }
                }
                case PLAN_STEP -> {
                    sb.append("\uD83D\uDCCB ").append("Plan");
                    if (entry.detail != null && !entry.detail.isBlank()) {
                        sb.append(": ").append(entry.detail);
                    }
                }
            }
            sb.append("    ").append(MUTED).append(tail).append(RESET);
            return sb.toString();
        }

        private StatusEntry firstActiveTool(String toolName) {
            for (var entry : entries.values()) {
                if (entry.kind == Kind.TOOL
                        && entry.state == State.ACTIVE
                        && toolName.equals(entry.name)) {
                    return entry;
                }
            }
            return null;
        }

        private StatusEntry firstReusableTool(String toolName) {
            StatusEntry active = firstActiveTool(toolName);
            if (active != null) return active;
            for (var entry : entries.values()) {
                if (entry.kind == Kind.TOOL && toolName.equals(entry.name)) {
                    return entry;
                }
            }
            return null;
        }

        private String firstActiveKey(Kind kind) {
            for (var entry : entries.values()) {
                if (entry.kind == kind && entry.state == State.ACTIVE) {
                    return entry.key;
                }
            }
            return null;
        }

        private boolean isEntryVisible(String key) {
            return key != null && entries.containsKey(key);
        }

        private static String safe(String value, String fallback) {
            if (value == null || value.isBlank()) {
                return fallback;
            }
            return value;
        }

        private static long nanosToMs(long nanos) {
            return Math.max(0L, nanos / 1_000_000L);
        }

        private static String formatSeconds(long durationMs) {
            return String.format(Locale.ROOT, "%.1fs", durationMs / 1000.0);
        }
    }

}

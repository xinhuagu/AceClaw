package dev.aceclaw.cli;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.PrintWriter;
import java.util.Objects;

import static dev.aceclaw.cli.TerminalTheme.*;

/**
 * Renders streaming agent events directly to the terminal.
 *
 * <p>Extracted from the original {@code TerminalRepl.processInput()} inline switch/case.
 * All writes to {@code out} are synchronized to prevent interleaved output from
 * multiple task threads.
 *
 * <p>This sink manages markdown buffering and incremental rendering:
 * text deltas are buffered until a paragraph boundary ({@code \n\n}) is found,
 * then rendered via {@link TerminalMarkdownRenderer}.
 */
public final class ForegroundOutputSink implements OutputSink {

    private final PrintWriter out;
    private final TerminalMarkdownRenderer markdownRenderer;
    private final Object lock = new Object();

    private final StringBuilder textBuffer = new StringBuilder();
    private boolean receivedTextOutput = false;
    private boolean wasThinking = false;
    private boolean inCodeFence = false;
    private volatile TerminalSpinner spinner;

    public ForegroundOutputSink(PrintWriter out, TerminalMarkdownRenderer markdownRenderer) {
        this.out = Objects.requireNonNull(out, "out");
        this.markdownRenderer = Objects.requireNonNull(markdownRenderer, "markdownRenderer");
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
            stopSpinner();
            out.print(THINKING + delta + RESET);
            out.flush();
            wasThinking = true;
        }
    }

    @Override
    public void onTextDelta(String delta) {
        synchronized (lock) {
            stopSpinner();

            if (wasThinking) {
                out.println();
                out.println();
                wasThinking = false;
            }

            textBuffer.append(delta);
            receivedTextOutput = true;

            // Track code fence state
            for (int i = 0; i < delta.length(); i++) {
                if (i + 2 < delta.length()
                        && delta.charAt(i) == '`'
                        && delta.charAt(i + 1) == '`'
                        && delta.charAt(i + 2) == '`') {
                    inCodeFence = !inCodeFence;
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
        synchronized (lock) {
            if (receivedTextOutput) {
                flushMarkdown();
                inCodeFence = false;
                receivedTextOutput = false;
            }
            if (wasThinking) {
                out.println();
                wasThinking = false;
            }
            stopSpinner();
            String verb = TerminalSpinner.verbForTool(toolName);
            spinner = new TerminalSpinner(out);
            spinner.start(verb + " " + toolName + "...");
        }
    }

    @Override
    public void onStreamError(String error) {
        synchronized (lock) {
            if (receivedTextOutput) {
                flushMarkdown();
                inCodeFence = false;
                receivedTextOutput = false;
            }
            stopSpinner();
            out.printf("%s[stream error: %s]%s%n", ERROR, error, RESET);
            out.flush();
        }
    }

    @Override
    public void onStreamCancelled() {
        synchronized (lock) {
            stopSpinner();
        }
    }

    @Override
    public void onTurnComplete(JsonNode message, boolean hasError) {
        synchronized (lock) {
            stopSpinner();

            if (receivedTextOutput) {
                flushMarkdown();
            }

            if (hasError) {
                JsonNode error = message.get("error");
                if (error != null) {
                    int code = error.get("code").asInt();
                    String errorMessage = error.get("message").asText();
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

            // Reset state for next turn
            textBuffer.setLength(0);
            receivedTextOutput = false;
            wasThinking = false;
            inCodeFence = false;
        }
    }

    @Override
    public void onConnectionClosed() {
        synchronized (lock) {
            stopSpinner();
            flushMarkdown();
            out.println("\n[Connection closed]");
            out.flush();
        }
    }

    @Override
    public void onCompaction(JsonNode params) {
        synchronized (lock) {
            out.println(MUTED + "[context compacted]" + RESET);
            out.flush();
        }
    }

    /**
     * Stops the active spinner if one is running.
     */
    public void stopSpinner() {
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
}

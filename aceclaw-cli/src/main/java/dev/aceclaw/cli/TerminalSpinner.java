package dev.aceclaw.cli;

import java.io.PrintWriter;

import static dev.aceclaw.cli.TerminalTheme.*;

/**
 * Animated terminal spinner for showing activity progress.
 *
 * <p>Runs on a virtual thread, updating a single terminal line at configurable intervals.
 * Use {@link #start(String)} to begin spinning and {@link #stop(String)} to
 * replace the spinner with a final status message.
 *
 * <p>Two animation styles:
 * <ul>
 *   <li><b>DOTS</b> — braille dot pattern for tool execution (fast, compact)</li>
 *   <li><b>BOUNCE</b> — bouncing bar for LLM thinking (smooth, eye-catching)</li>
 * </ul>
 */
public final class TerminalSpinner {

    /** Braille dot spinner — used for tool execution. */
    private static final String[] DOTS_FRAMES = {
            "\u280B", "\u2819", "\u2839", "\u2838",
            "\u283C", "\u2834", "\u2826", "\u2827",
            "\u2807", "\u280F"
    };

    /** Bouncing bar spinner — used for LLM thinking. */
    private static final String[] BOUNCE_FRAMES = {
            "⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"
    };

    /** Moon phase animation — alternative thinking style. */
    private static final String[] MOON_FRAMES = {
            "◐", "◓", "◑", "◒"
    };

    public enum Style {
        DOTS(DOTS_FRAMES, 80),
        BOUNCE(BOUNCE_FRAMES, 80),
        MOON(MOON_FRAMES, 150);

        final String[] frames;
        final long intervalMs;

        Style(String[] frames, long intervalMs) {
            this.frames = frames;
            this.intervalMs = intervalMs;
        }
    }

    private static final long DEFAULT_FRAME_INTERVAL_MS = 80;

    private final PrintWriter out;
    private final Style style;
    private volatile String message = "";
    private volatile boolean spinning = false;
    private volatile Thread spinThread;

    public TerminalSpinner(PrintWriter out) {
        this(out, Style.DOTS);
    }

    public TerminalSpinner(PrintWriter out, Style style) {
        this.out = out;
        this.style = style;
    }

    /**
     * Starts the spinner with the given message. If already spinning, updates the message.
     *
     * @param message the status message to display next to the spinner
     */
    public void start(String message) {
        this.message = message;
        if (spinning) return;
        spinning = true;
        spinThread = Thread.ofVirtual().name("aceclaw-spinner").start(this::animate);
    }

    /**
     * Stops the spinner and prints a final status line.
     *
     * @param finalMessage the message to display after stopping (e.g., checkmark + tool name)
     */
    public void stop(String finalMessage) {
        spinning = false;
        var t = spinThread;
        if (t != null) {
            try {
                t.join(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            spinThread = null;
        }
        // Clear the spinner line and print the final message
        out.print("\r\u001B[K");
        if (finalMessage != null && !finalMessage.isEmpty()) {
            out.println(finalMessage);
        }
        out.flush();
    }

    /**
     * Stops the spinner without printing a final message, just clears the line.
     */
    public void clear() {
        stop(null);
    }

    /**
     * Returns whether the spinner is currently active.
     */
    public boolean isSpinning() {
        return spinning;
    }

    private void animate() {
        String[] frames = style.frames;
        long intervalMs = style.intervalMs;
        int frameIdx = 0;
        while (spinning) {
            String frame = frames[frameIdx % frames.length];
            out.print("\r\u001B[K" + SPINNER + frame + RESET + " " + message);
            out.flush();
            frameIdx++;
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Returns the human-friendly verb for a tool name.
     *
     * @param toolName the tool identifier
     * @return a present-participle verb (e.g., "Reading", "Running")
     */
    public static String verbForTool(String toolName) {
        return switch (toolName) {
            case "read_file" -> "Reading";
            case "write_file" -> "Writing";
            case "edit_file" -> "Editing";
            case "bash" -> "Running";
            case "glob" -> "Searching";
            case "grep" -> "Searching";
            default -> "Running";
        };
    }
}

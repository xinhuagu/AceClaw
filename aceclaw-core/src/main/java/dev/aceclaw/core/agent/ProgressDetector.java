package dev.aceclaw.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Session-scoped detector that tracks forward progress signals versus stall
 * signals to detect when the agent is stuck in an unproductive loop.
 *
 * <p>Progress signals include successful file writes, test passes, reading new
 * files, and successful command execution. When no progress is detected for
 * {@link #stallThreshold} consecutive tool executions, the detector reports a
 * stall and generates a pivot prompt to inject into the conversation.
 *
 * <p>This complements {@link DoomLoopDetector} which tracks exact argument
 * fingerprints: ProgressDetector catches higher-level stalls where the agent
 * tries slightly different failing approaches.
 */
public final class ProgressDetector {

    private static final Logger log = LoggerFactory.getLogger(ProgressDetector.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Default number of consecutive non-progress tool calls before declaring stall. */
    static final int DEFAULT_STALL_THRESHOLD = 5;

    private static final Pattern TEST_COMMAND_PATTERN = Pattern.compile(
            "(?i)\\b(?:test|pytest|jest|vitest|mocha|gradle\\s+test|mvn\\s+test|npm\\s+test|" +
            "npx\\s+jest|go\\s+test|cargo\\s+test|dotnet\\s+test|rspec|phpunit)\\b");

    /** Signals of forward progress. */
    enum ProgressSignal {
        FILE_WRITTEN,
        TEST_PASSED,
        NEW_FILE_READ,
        COMMAND_SUCCEEDED
    }

    private final int stallThreshold;
    private int noProgressCount;
    private final Set<String> seenPaths = new HashSet<>();
    private final List<AttemptRecord> recentAttempts = new ArrayList<>();

    /**
     * Creates a ProgressDetector with the default stall threshold.
     */
    public ProgressDetector() {
        this(DEFAULT_STALL_THRESHOLD);
    }

    /**
     * Creates a ProgressDetector with a custom stall threshold.
     *
     * @param stallThreshold number of consecutive non-progress tool calls before stall
     */
    public ProgressDetector(int stallThreshold) {
        this.stallThreshold = Math.max(1, stallThreshold);
    }

    /**
     * Records the result of a tool execution and updates progress tracking.
     *
     * @param toolName  the tool name
     * @param inputJson the tool input JSON (may be null)
     * @param isError   whether the tool returned an error
     * @param output    the tool output text (may be null)
     */
    public void recordToolResult(String toolName, String inputJson, boolean isError, String output) {
        if (toolName == null) return;

        String safeInput = inputJson != null ? inputJson : "";
        boolean progress = false;

        if (!isError) {
            var signal = classifySuccess(toolName, safeInput, output);
            if (signal != null) {
                progress = true;
                log.debug("Progress signal detected: {} from tool={}", signal, toolName);
            }
        }

        // Track attempt for summary building
        recentAttempts.add(new AttemptRecord(toolName, summarizeInput(safeInput), isError));
        if (recentAttempts.size() > 20) {
            recentAttempts.removeFirst();
        }

        if (progress) {
            noProgressCount = 0;
        } else {
            noProgressCount++;
        }
    }

    /**
     * Returns true if no progress signals in the last {@code stallThreshold} tool executions.
     */
    public boolean isStalled() {
        return noProgressCount >= stallThreshold;
    }

    /**
     * Returns the number of consecutive tool executions with no progress.
     */
    public int noProgressCount() {
        return noProgressCount;
    }

    /**
     * Returns a pivot prompt to inject into the conversation when stalled.
     * The prompt instructs the agent to step back, summarize what has been tried,
     * and take a fundamentally different approach.
     */
    public String buildPivotPrompt() {
        var sb = new StringBuilder();
        sb.append("[SYSTEM: Progress stall detected] You have executed ");
        sb.append(noProgressCount);
        sb.append(" tool calls without making meaningful forward progress. ");
        sb.append("STOP and reassess your approach.\n\n");

        String summary = buildAttemptSummary();
        if (!summary.isEmpty()) {
            sb.append("Recent attempts:\n");
            sb.append(summary);
            sb.append("\n");
        }

        sb.append("Instructions:\n");
        sb.append("1. Briefly state what you were trying to accomplish\n");
        sb.append("2. Identify why your current approach is not working\n");
        sb.append("3. Choose a fundamentally DIFFERENT strategy (not a minor variation)\n");
        sb.append("4. If stuck, consider: reading error messages more carefully, ");
        sb.append("checking file paths, trying a simpler approach, or asking the user for help");

        return sb.toString();
    }

    /**
     * Returns a summary of recent tool attempts for context injection.
     */
    public String buildAttemptSummary() {
        if (recentAttempts.isEmpty()) return "";

        var sb = new StringBuilder();
        int start = Math.max(0, recentAttempts.size() - stallThreshold);
        for (int i = start; i < recentAttempts.size(); i++) {
            var attempt = recentAttempts.get(i);
            sb.append("- ");
            sb.append(attempt.toolName());
            if (!attempt.inputSummary().isEmpty()) {
                sb.append("(");
                sb.append(attempt.inputSummary());
                sb.append(")");
            }
            sb.append(attempt.isError() ? " -> FAILED" : " -> ok");
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * Resets the stall counter. Called when progress is detected or after
     * a pivot prompt has been injected.
     */
    public void reset() {
        noProgressCount = 0;
    }

    /**
     * Classifies a successful tool execution into a progress signal, or null if
     * the execution doesn't represent meaningful forward progress.
     */
    private ProgressSignal classifySuccess(String toolName, String inputJson, String output) {
        String lowerTool = toolName.toLowerCase(Locale.ROOT);

        // Write/edit operations are always progress
        if (lowerTool.equals("write_file") || lowerTool.equals("edit_file")) {
            return ProgressSignal.FILE_WRITTEN;
        }

        // read_file on a new path is progress
        if (lowerTool.equals("read_file")) {
            String path = extractPath(inputJson);
            if (path != null && seenPaths.add(path)) {
                return ProgressSignal.NEW_FILE_READ;
            }
            return null;
        }

        // bash success
        if (lowerTool.equals("bash")) {
            if (isTestCommand(inputJson)) {
                return ProgressSignal.TEST_PASSED;
            }
            return ProgressSignal.COMMAND_SUCCEEDED;
        }

        // glob/grep success indicates exploration which is mild progress
        if (lowerTool.equals("glob") || lowerTool.equals("grep")) {
            return ProgressSignal.COMMAND_SUCCEEDED;
        }

        return null;
    }

    /**
     * Checks if a bash command input contains a test-related command.
     */
    private static boolean isTestCommand(String inputJson) {
        if (inputJson == null || inputJson.isBlank()) return false;
        return TEST_COMMAND_PATTERN.matcher(inputJson).find();
    }

    /**
     * Extracts a file path from tool input JSON.
     */
    private static String extractPath(String inputJson) {
        if (inputJson == null || inputJson.isBlank()) return null;
        try {
            var tree = JSON.readTree(inputJson);
            if (tree == null || !tree.isObject()) return null;
            // Try common field names
            for (String field : new String[]{"path", "file_path", "filePath", "file"}) {
                var node = tree.get(field);
                if (node != null && node.isTextual()) {
                    return node.asText();
                }
            }
        } catch (Exception e) {
            // Fall through
        }
        return null;
    }

    /**
     * Creates a brief summary of tool input for attempt tracking.
     */
    private static String summarizeInput(String inputJson) {
        if (inputJson.isBlank()) return "";
        try {
            var tree = JSON.readTree(inputJson);
            if (tree == null || !tree.isObject()) return truncate(inputJson, 60);
            // Extract the most interesting field
            for (String field : new String[]{"command", "path", "file_path", "pattern", "content"}) {
                var node = tree.get(field);
                if (node != null && node.isTextual()) {
                    return truncate(node.asText(), 60);
                }
            }
        } catch (Exception e) {
            // Fall through
        }
        return truncate(inputJson, 60);
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }

    private record AttemptRecord(String toolName, String inputSummary, boolean isError) {}
}

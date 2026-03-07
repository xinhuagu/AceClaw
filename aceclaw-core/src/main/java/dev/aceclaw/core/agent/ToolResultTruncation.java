package dev.aceclaw.core.agent;

/**
 * Shared utility for truncating tool result output to fit within context window limits.
 *
 * <p>Used by both {@link AgentLoop} and {@link StreamingAgentLoop} for consistent
 * truncation of tool output, error messages, and fallback results.
 */
final class ToolResultTruncation {

    /** Maximum characters for a single tool result (head/tail truncated beyond this). */
    static final int MAX_TOOL_RESULT_CHARS = 30_000;

    private ToolResultTruncation() {}

    /**
     * Truncates tool result output using 40% head / 60% tail split.
     *
     * @param output   the tool result text
     * @param maxChars maximum allowed characters
     * @return the original text if within budget, or a truncated version
     */
    static String truncate(String output, int maxChars) {
        if (output == null || output.length() <= maxChars) {
            return output;
        }
        int headChars = (int) (maxChars * 0.4);
        int tailChars = maxChars - headChars;
        return output.substring(0, headChars)
                + "\n\n... (truncated: " + output.length() + " chars total, showing first "
                + headChars + " and last " + tailChars + ") ...\n\n"
                + output.substring(output.length() - tailChars);
    }
}

package dev.aceclaw.cli;

/**
 * Centralized color palette and ANSI styling for the AceClaw CLI.
 *
 * <p>All terminal colors and semantic styles are defined here so that
 * the rest of the CLI code references named constants rather than
 * raw escape sequences.
 */
public final class TerminalTheme {

    private TerminalTheme() {}

    // -- Reset ---------------------------------------------------------------

    public static final String RESET = "\u001B[0m";

    // -- Text attributes -----------------------------------------------------

    public static final String BOLD = "\u001B[1m";
    public static final String DIM = "\u001B[2m";
    public static final String ITALIC = "\u001B[3m";
    public static final String UNDERLINE = "\u001B[4m";

    // -- Brand palette -------------------------------------------------------

    public static final String ACCENT = "\u001B[34m";       // blue
    public static final String SUCCESS = "\u001B[32m";      // green
    public static final String WARNING = "\u001B[33m";      // yellow
    public static final String ERROR = "\u001B[31m";        // red
    public static final String MUTED = "\u001B[90m";        // bright black / gray
    public static final String INFO = "\u001B[36m";         // cyan

    /** Accenture brand purple (#A100FF) — 24-bit true color */
    public static final String PURPLE = "\u001B[38;2;161;0;255m";

    // -- Semantic combinations -----------------------------------------------

    /** H1 headings: bold cyan */
    public static final String HEADING_1 = BOLD + INFO;
    /** H2 headings: bold green */
    public static final String HEADING_2 = BOLD + SUCCESS;
    /** H3 headings: bold yellow */
    public static final String HEADING_3 = BOLD + WARNING;

    /** Active tool execution */
    public static final String TOOL_ACTIVE = WARNING;
    /** Completed tool */
    public static final String TOOL_DONE = SUCCESS;

    /** Thinking / extended thinking text */
    public static final String THINKING = DIM + ITALIC;

    /** Spinner frames */
    public static final String SPINNER = INFO;

    /** User prompt prefix */
    public static final String PROMPT = INFO;

    /** Permission request border and labels */
    public static final String PERMISSION = BOLD + WARNING;
    /** Approved action */
    public static final String APPROVED = SUCCESS;
    /** Denied action */
    public static final String DENIED = ERROR;

    /** Code content (fenced blocks, inline code) */
    public static final String CODE = INFO;
    /** Code fence markers (```) */
    public static final String CODE_FENCE = DIM;
    /** Inline code backtick markers */
    public static final String CODE_INLINE = MUTED;

    /** Links */
    public static final String LINK = UNDERLINE + "\u001B[35m"; // magenta

    // -- Box drawing helpers -------------------------------------------------

    public static final String BOX_TOP_LEFT = "\u2554";     // double top-left
    public static final String BOX_TOP_RIGHT = "\u2557";    // double top-right
    public static final String BOX_BOTTOM_LEFT = "\u255A";  // double bottom-left
    public static final String BOX_BOTTOM_RIGHT = "\u255D"; // double bottom-right
    public static final String BOX_HORIZONTAL = "\u2550";   // double horizontal
    public static final String BOX_VERTICAL = "\u2551";     // double vertical

    public static final String BOX_LIGHT_TOP_LEFT = "\u250C";
    public static final String BOX_LIGHT_BOTTOM_LEFT = "\u2514";
    public static final String BOX_LIGHT_HORIZONTAL = "\u2500";
    public static final String BOX_LIGHT_VERTICAL = "\u2502";

    /** Checkmark for completed items */
    public static final String CHECKMARK = "\u2713";

    // -- Status panel icons --------------------------------------------------

    /** Filled diamond for primary header — Accenture purple */
    public static final String ICON_PRIMARY = "\u25C6";
    /** Brain emoji for learning section */
    public static final String ICON_LEARNING = "\uD83E\uDDE0";   // U+1F9E0
    /** Cycle arrows emoji for cron/scheduler section */
    public static final String ICON_CRON = "\uD83D\uDD04";       // U+1F504
    /** Rocket emoji for tasks section */
    public static final String ICON_TASKS = "\uD83D\uDE80";      // U+1F680
    /** Bell emoji for notices section */
    public static final String ICON_NOTICES = "\uD83D\uDD14";    // U+1F514
    /** Right-pointing triangle for list items */
    public static final String ICON_ITEM = "\u25B8";

    // -- Status panel tree connectors ----------------------------------------

    public static final String TREE_BRANCH = "\u251C\u2500";    // ├─
    public static final String TREE_LAST   = "\u2514\u2500";    // └─
    public static final String TREE_PIPE   = "\u2502";          // │
    public static final String TREE_PIPE_SPACE = "\u2502    ";  // │    (with indent)

    // -- Utility methods -----------------------------------------------------

    /**
     * Builds a horizontal line of box-drawing characters.
     *
     * @param ch    the character to repeat
     * @param width number of repetitions
     * @return the repeated string
     */
    public static String hline(char ch, int width) {
        return String.valueOf(ch).repeat(Math.max(0, width));
    }

    /**
     * Builds a horizontal line using the double box-drawing character.
     *
     * @param width number of repetitions
     * @return the repeated string
     */
    public static String hline(int width) {
        return BOX_HORIZONTAL.repeat(Math.max(0, width));
    }

    /**
     * Builds a light horizontal line.
     *
     * @param width number of repetitions
     * @return the repeated string
     */
    public static String hlineLight(int width) {
        return BOX_LIGHT_HORIZONTAL.repeat(Math.max(0, width));
    }

    /**
     * Truncates a string to fit within the given display width,
     * appending ellipsis if needed. CJK characters count as 2 columns.
     *
     * @param text  the text to fit
     * @param width maximum visible width in terminal columns
     * @return the possibly truncated text
     */
    public static String fitWidth(String text, int width) {
        if (text == null) return "";
        int dw = displayWidth(text);
        if (dw <= width) return text;
        if (width <= 3) return "...".substring(0, width);
        // Truncate by display width, reserving 3 columns for "..."
        int target = width - 3;
        StringBuilder sb = new StringBuilder();
        int w = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int cpw = isWideChar(cp) ? 2 : 1;
            if (w + cpw > target) break;
            sb.appendCodePoint(cp);
            w += cpw;
            i += Character.charCount(cp);
        }
        sb.append("...");
        return sb.toString();
    }

    /**
     * Pads a string to the right to the given display width.
     * CJK characters count as 2 columns.
     *
     * @param text  the text to pad
     * @param width target display width in terminal columns
     * @return right-padded text
     */
    public static String padRight(String text, int width) {
        if (text == null) text = "";
        int dw = displayWidth(text);
        if (dw >= width) return text;
        return text + " ".repeat(width - dw);
    }

    /**
     * Computes the display width of a string in a terminal.
     * CJK characters and fullwidth characters occupy 2 columns.
     */
    public static int displayWidth(String text) {
        if (text == null) return 0;
        int width = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (cp == 0x200D || cp == 0xFE0F) {
                i += Character.charCount(cp);
                continue;
            }
            width += isWideChar(cp) ? 2 : 1;
            i += Character.charCount(cp);
        }
        return width;
    }

    /**
     * Returns true if the given Unicode code point is a wide (double-width)
     * character in a terminal — CJK ideographs, fullwidth forms, etc.
     */
    public static boolean isWideChar(int cp) {
        if (cp >= 0x2300 && cp <= 0x23FF) return true; // clocks/hourglass/misc technical symbols
        if (cp >= 0x2600 && cp <= 0x27BF) return true; // dingbats/misc symbols (emoji-presented on many terminals)
        if (cp >= 0x2E80 && cp <= 0x2FFF) return true;
        if (cp >= 0x3000 && cp <= 0x303F) return true;
        if (cp >= 0x3040 && cp <= 0x309F) return true;
        if (cp >= 0x30A0 && cp <= 0x30FF) return true;
        if (cp >= 0x3100 && cp <= 0x312F) return true;
        if (cp >= 0x3130 && cp <= 0x318F) return true;
        if (cp >= 0x3190 && cp <= 0x31FF) return true;
        if (cp >= 0x3200 && cp <= 0x32FF) return true;
        if (cp >= 0x3300 && cp <= 0x33FF) return true;
        if (cp >= 0x3400 && cp <= 0x4DBF) return true;
        if (cp >= 0x4E00 && cp <= 0x9FFF) return true;
        if (cp >= 0xA000 && cp <= 0xA4CF) return true;
        if (cp >= 0xAC00 && cp <= 0xD7AF) return true;
        if (cp >= 0xF900 && cp <= 0xFAFF) return true;
        if (cp >= 0xFE30 && cp <= 0xFE4F) return true;
        if (cp >= 0xFF00 && cp <= 0xFF60) return true;
        if (cp >= 0xFFE0 && cp <= 0xFFE6) return true;
        if (cp >= 0x20000 && cp <= 0x2FA1F) return true;
        if (cp >= 0x1F300 && cp <= 0x1F9FF) return true;
        return false;
    }
}

package dev.aceclaw.cli;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.*;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import static dev.aceclaw.cli.TerminalTheme.*;

/**
 * Renders Markdown text as ANSI-formatted terminal output.
 *
 * <p>Uses the CommonMark parser to build an AST, then walks it with
 * a custom visitor that emits ANSI escape codes for styling.
 *
 * <p>Supports: headers, bold, italic, strikethrough, inline code,
 * fenced code blocks, bullet/ordered lists, blockquotes, links,
 * horizontal rules, thematic breaks, and GFM tables.
 */
public final class TerminalMarkdownRenderer {

    private final Parser parser;

    public TerminalMarkdownRenderer() {
        List<Extension> extensions = List.of(
                TablesExtension.create(),
                StrikethroughExtension.create()
        );
        this.parser = Parser.builder()
                .extensions(extensions)
                .build();
    }

    /**
     * Renders markdown text to ANSI-formatted output written to the given writer.
     *
     * @param markdown the markdown source text
     * @param out      the writer to render to
     */
    public void render(String markdown, PrintWriter out) {
        if (markdown == null || markdown.isEmpty()) return;
        Node document = parser.parse(markdown);
        var visitor = new AnsiRenderVisitor(out);
        visitor.renderNode(document);
    }

    /**
     * Renders markdown text to an ANSI-formatted string.
     *
     * @param markdown the markdown source text
     * @return the ANSI-formatted string
     */
    public String renderToString(String markdown) {
        if (markdown == null || markdown.isEmpty()) return "";
        var sw = new StringWriter();
        var pw = new PrintWriter(sw);
        render(markdown, pw);
        pw.flush();
        return sw.toString();
    }

    /**
     * AST visitor that produces ANSI-formatted terminal output.
     */
    private static final class AnsiRenderVisitor {

        private final PrintWriter out;
        private int listDepth = 0;
        private int orderedItemIndex = 0;
        private boolean inBlockQuote = false;

        AnsiRenderVisitor(PrintWriter out) {
            this.out = out;
        }

        void renderNode(Node node) {
            renderChildren(node);
        }

        private void renderChildren(Node parent) {
            Node child = parent.getFirstChild();
            while (child != null) {
                renderSingle(child);
                child = child.getNext();
            }
        }

        private void renderSingle(Node node) {
            switch (node) {
                case Document doc -> renderChildren(doc);

                case Heading heading -> {
                    String color = switch (heading.getLevel()) {
                        case 1 -> HEADING_1;
                        case 2 -> HEADING_2;
                        case 3 -> HEADING_3;
                        default -> BOLD;
                    };
                    out.print(color);
                    String prefix = "#".repeat(heading.getLevel()) + " ";
                    out.print(prefix);
                    renderChildren(heading);
                    out.println(RESET);
                    out.println();
                }

                case Paragraph para -> {
                    if (inBlockQuote) {
                        out.print(DIM + "  | " + RESET);
                    }
                    renderChildren(para);
                    out.println();
                    if (!inBlockQuote) {
                        out.println();
                    }
                }

                case Text text -> out.print(text.getLiteral());

                case StrongEmphasis strong -> {
                    out.print(BOLD);
                    renderChildren(strong);
                    out.print(RESET);
                }

                case Emphasis emphasis -> {
                    out.print(ITALIC);
                    renderChildren(emphasis);
                    out.print(RESET);
                }

                case Strikethrough strikethrough -> {
                    out.print(DIM + "~~");
                    renderChildren(strikethrough);
                    out.print("~~" + RESET);
                }

                case Code code -> {
                    out.print(CODE_INLINE + "`" + code.getLiteral() + "`" + RESET);
                }

                case FencedCodeBlock codeBlock -> {
                    String lang = codeBlock.getInfo();
                    if (lang != null && !lang.isEmpty()) {
                        out.println(CODE_FENCE + "```" + lang + RESET);
                    } else {
                        out.println(CODE_FENCE + "```" + RESET);
                    }
                    out.print(CODE);
                    String literal = codeBlock.getLiteral();
                    if (literal != null) {
                        // Remove trailing newline to avoid extra blank line
                        if (literal.endsWith("\n")) {
                            literal = literal.substring(0, literal.length() - 1);
                        }
                        out.println(literal);
                    }
                    out.println(RESET + CODE_FENCE + "```" + RESET);
                    out.println();
                }

                case IndentedCodeBlock codeBlock -> {
                    out.print(CODE);
                    String literal = codeBlock.getLiteral();
                    if (literal != null) {
                        for (String line : literal.split("\n", -1)) {
                            out.println("    " + line);
                        }
                    }
                    out.print(RESET);
                    out.println();
                }

                case BulletList bulletList -> {
                    listDepth++;
                    renderChildren(bulletList);
                    listDepth--;
                    if (listDepth == 0) out.println();
                }

                case OrderedList orderedList -> {
                    listDepth++;
                    orderedItemIndex = orderedList.getStartNumber();
                    renderChildren(orderedList);
                    listDepth--;
                    if (listDepth == 0) out.println();
                }

                case ListItem item -> {
                    String indent = "  ".repeat(listDepth - 1);
                    Node parentList = item.getParent();
                    if (parentList instanceof OrderedList) {
                        out.print(indent + WARNING + orderedItemIndex + "." + RESET + " ");
                        orderedItemIndex++;
                    } else {
                        out.print(indent + WARNING + "-" + RESET + " ");
                    }
                    // Render item content inline (avoid extra paragraph newlines)
                    Node child = item.getFirstChild();
                    while (child != null) {
                        if (child instanceof Paragraph para) {
                            renderChildren(para);
                            out.println();
                        } else {
                            renderSingle(child);
                        }
                        child = child.getNext();
                    }
                }

                case BlockQuote blockQuote -> {
                    boolean wasInBlockQuote = inBlockQuote;
                    inBlockQuote = true;
                    renderChildren(blockQuote);
                    inBlockQuote = wasInBlockQuote;
                    if (!inBlockQuote) out.println();
                }

                case Link link -> {
                    out.print(LINK);
                    renderChildren(link);
                    out.print(RESET);
                    String dest = link.getDestination();
                    if (dest != null && !dest.isEmpty()) {
                        out.print(DIM + " (" + dest + ")" + RESET);
                    }
                }

                case ThematicBreak ignored -> {
                    out.println(DIM + "---" + RESET);
                    out.println();
                }

                case SoftLineBreak ignored -> out.print(" ");

                case HardLineBreak ignored -> out.println();

                case HtmlInline html -> out.print(html.getLiteral());

                case HtmlBlock html -> {
                    out.println(html.getLiteral());
                    out.println();
                }

                case Image image -> {
                    out.print("[image: ");
                    renderChildren(image);
                    out.print("]");
                }

                case TableBlock table -> renderTable(table);

                default -> renderChildren(node);
            }
        }

        // ── Table rendering ─────────────────────────────────────────────

        private void renderTable(TableBlock table) {
            // 1. Collect all rows (header + body) with their cell contents and alignments
            List<List<String>> rows = new ArrayList<>();
            List<TableCell.Alignment> alignments = new ArrayList<>();
            boolean headerParsed = false;

            Node section = table.getFirstChild();
            while (section != null) {
                Node row = section.getFirstChild();
                while (row instanceof TableRow) {
                    List<String> cells = new ArrayList<>();
                    Node cell = row.getFirstChild();
                    while (cell instanceof TableCell tc) {
                        cells.add(renderCellToString(tc));
                        if (!headerParsed) {
                            alignments.add(tc.getAlignment());
                        }
                        cell = cell.getNext();
                    }
                    rows.add(cells);
                    if (!headerParsed) headerParsed = true;
                    row = row.getNext();
                }
                section = section.getNext();
            }

            if (rows.isEmpty()) return;

            // 2. Compute column widths (max visible width per column)
            int numCols = alignments.size();
            int[] colWidths = new int[numCols];
            for (List<String> row : rows) {
                for (int c = 0; c < numCols && c < row.size(); c++) {
                    int len = displayWidth(stripAnsi(row.get(c)));
                    if (len > colWidths[c]) colWidths[c] = len;
                }
            }
            // Ensure minimum column width of 3
            for (int c = 0; c < numCols; c++) {
                if (colWidths[c] < 3) colWidths[c] = 3;
            }

            // 3. Draw the table
            // Top border
            out.print(DIM + BOX_LIGHT_TOP_LEFT);
            for (int c = 0; c < numCols; c++) {
                out.print(BOX_LIGHT_HORIZONTAL.repeat(colWidths[c] + 2));
                if (c < numCols - 1) out.print("┬");
            }
            out.println(BOX_LIGHT_HORIZONTAL.isEmpty() ? "" : "┐" + RESET);

            // Header row
            if (!rows.isEmpty()) {
                printRow(rows.get(0), colWidths, alignments, true);
            }

            // Header separator
            out.print(DIM + "├");
            for (int c = 0; c < numCols; c++) {
                out.print(BOX_LIGHT_HORIZONTAL.repeat(colWidths[c] + 2));
                if (c < numCols - 1) out.print("┼");
            }
            out.println("┤" + RESET);

            // Body rows
            for (int r = 1; r < rows.size(); r++) {
                printRow(rows.get(r), colWidths, alignments, false);
            }

            // Bottom border
            out.print(DIM + BOX_LIGHT_BOTTOM_LEFT);
            for (int c = 0; c < numCols; c++) {
                out.print(BOX_LIGHT_HORIZONTAL.repeat(colWidths[c] + 2));
                if (c < numCols - 1) out.print("┴");
            }
            out.println("┘" + RESET);
            out.println();
        }

        private void printRow(List<String> cells, int[] colWidths,
                              List<TableCell.Alignment> alignments, boolean isHeader) {
            int numCols = colWidths.length;
            out.print(DIM + BOX_LIGHT_VERTICAL + RESET);
            for (int c = 0; c < numCols; c++) {
                String content = c < cells.size() ? cells.get(c) : "";
                int visibleLen = displayWidth(stripAnsi(content));
                int padding = colWidths[c] - visibleLen;
                TableCell.Alignment align = c < alignments.size() ? alignments.get(c) : null;

                out.print(" ");
                if (isHeader) {
                    out.print(BOLD);
                    out.print(alignText(content, padding, align));
                    out.print(RESET);
                } else {
                    out.print(alignText(content, padding, align));
                }
                out.print(" ");
                out.print(DIM + BOX_LIGHT_VERTICAL + RESET);
            }
            out.println();
        }

        private String alignText(String text, int padding, TableCell.Alignment alignment) {
            if (padding <= 0) return text;
            if (alignment == TableCell.Alignment.CENTER) {
                int left = padding / 2;
                int right = padding - left;
                return " ".repeat(left) + text + " ".repeat(right);
            } else if (alignment == TableCell.Alignment.RIGHT) {
                return " ".repeat(padding) + text;
            } else {
                // LEFT or null (default left)
                return text + " ".repeat(padding);
            }
        }

        /**
         * Renders a TableCell's inline content to a plain ANSI string
         * (so we can measure visible width and align).
         */
        private String renderCellToString(TableCell cell) {
            var sw = new StringWriter();
            var pw = new PrintWriter(sw);
            var cellVisitor = new AnsiRenderVisitor(pw);
            Node child = cell.getFirstChild();
            while (child != null) {
                if (child instanceof Paragraph para) {
                    cellVisitor.renderChildren(para);
                } else {
                    cellVisitor.renderSingle(child);
                }
                child = child.getNext();
            }
            pw.flush();
            return sw.toString().strip();
        }

        /**
         * Strips ANSI escape codes to compute visible character width.
         */
        private static String stripAnsi(String text) {
            return text.replaceAll("\u001B\\[[0-9;]*m", "");
        }

        /** Delegates to TerminalTheme.displayWidth for CJK-aware width calculation. */
        private static int displayWidth(String text) {
            return TerminalTheme.displayWidth(text);
        }
    }
}

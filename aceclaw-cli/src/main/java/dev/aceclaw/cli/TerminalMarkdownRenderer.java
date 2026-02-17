package dev.aceclaw.cli;

import org.commonmark.node.*;
import org.commonmark.parser.Parser;

import java.io.PrintWriter;

/**
 * Renders Markdown text as ANSI-formatted terminal output.
 *
 * <p>Uses the CommonMark parser to build an AST, then walks it with
 * a custom visitor that emits ANSI escape codes for styling.
 *
 * <p>Supports: headers, bold, italic, inline code, fenced code blocks,
 * bullet/ordered lists, blockquotes, links, horizontal rules, and
 * thematic breaks.
 */
public final class TerminalMarkdownRenderer {

    // ANSI escape codes
    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String DIM = "\u001B[2m";
    private static final String ITALIC = "\u001B[3m";
    private static final String UNDERLINE = "\u001B[4m";
    private static final String CYAN = "\u001B[36m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String MAGENTA = "\u001B[35m";
    private static final String BRIGHT_BLACK = "\u001B[90m";

    private final Parser parser;

    public TerminalMarkdownRenderer() {
        this.parser = Parser.builder().build();
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
        var sw = new java.io.StringWriter();
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
                        case 1 -> BOLD + CYAN;
                        case 2 -> BOLD + GREEN;
                        case 3 -> BOLD + YELLOW;
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

                case Code code -> {
                    out.print(BRIGHT_BLACK + "`" + code.getLiteral() + "`" + RESET);
                }

                case FencedCodeBlock codeBlock -> {
                    String lang = codeBlock.getInfo();
                    if (lang != null && !lang.isEmpty()) {
                        out.println(DIM + "```" + lang + RESET);
                    } else {
                        out.println(DIM + "```" + RESET);
                    }
                    out.print(CYAN);
                    String literal = codeBlock.getLiteral();
                    if (literal != null) {
                        // Remove trailing newline to avoid extra blank line
                        if (literal.endsWith("\n")) {
                            literal = literal.substring(0, literal.length() - 1);
                        }
                        out.println(literal);
                    }
                    out.println(RESET + DIM + "```" + RESET);
                    out.println();
                }

                case IndentedCodeBlock codeBlock -> {
                    out.print(CYAN);
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
                        out.print(indent + YELLOW + orderedItemIndex + "." + RESET + " ");
                        orderedItemIndex++;
                    } else {
                        out.print(indent + YELLOW + "-" + RESET + " ");
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
                    out.print(UNDERLINE + MAGENTA);
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

                default -> renderChildren(node);
            }
        }
    }
}

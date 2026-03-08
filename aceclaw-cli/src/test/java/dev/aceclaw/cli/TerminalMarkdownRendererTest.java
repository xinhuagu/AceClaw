package dev.aceclaw.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TerminalMarkdownRendererTest {

    private final TerminalMarkdownRenderer renderer = new TerminalMarkdownRenderer();

    @Test
    void rendersSimpleTable() {
        String md = """
                | Name   | Age |
                |--------|-----|
                | Alice  | 30  |
                | Bob    | 25  |
                """;

        String result = renderer.renderToString(md);
        String plain = stripAnsi(result);

        // Table structure is present — box drawing characters
        assertThat(plain).contains("┌");
        assertThat(plain).contains("┘");
        assertThat(plain).contains("│");
        assertThat(plain).contains("├");

        // Content is present
        assertThat(plain).contains("Name");
        assertThat(plain).contains("Age");
        assertThat(plain).contains("Alice");
        assertThat(plain).contains("30");
        assertThat(plain).contains("Bob");
        assertThat(plain).contains("25");
    }

    @Test
    void rendersTableWithAlignment() {
        String md = """
                | Left | Center | Right |
                |:-----|:------:|------:|
                | a    |   b    |     c |
                """;

        String result = renderer.renderToString(md);
        String plain = stripAnsi(result);

        assertThat(plain).contains("Left");
        assertThat(plain).contains("Center");
        assertThat(plain).contains("Right");
        assertThat(plain).contains("a");
        assertThat(plain).contains("b");
        assertThat(plain).contains("c");
    }

    @Test
    void rendersStrikethrough() {
        String md = "This is ~~deleted~~ text.";

        String result = renderer.renderToString(md);
        String plain = stripAnsi(result);

        assertThat(plain).contains("~~deleted~~");
    }

    @Test
    void rendersHeadingsAndBold() {
        String md = """
                ## Hello
                
                This is **bold** text.
                """;

        String result = renderer.renderToString(md);
        String plain = stripAnsi(result);

        assertThat(plain).contains("## Hello");
        assertThat(plain).contains("bold");
    }

    @Test
    void rendersTableWithCjkCharactersAligned() {
        String md = """
                | 类别 | 内容 |
                |------|------|
                | MISTAKE | 中文双宽字符 |
                | OK | English |
                """;

        String result = renderer.renderToString(md);
        String plain = stripAnsi(result);

        // Every row line in the table should have the same display width.
        // Extract lines that start with │ (content rows)
        String[] lines = plain.split("\n");
        int expectedWidth = -1;
        for (String line : lines) {
            if (line.isEmpty()) continue;
            // All table lines (borders + content) should have the same display width
            char first = line.charAt(0);
            if (first == '┌' || first == '├' || first == '└' || first == '│') {
                int w = TerminalTheme.displayWidth(line);
                if (expectedWidth == -1) {
                    expectedWidth = w;
                } else {
                    assertThat(w)
                            .as("Table line display width should be uniform: '%s'", line)
                            .isEqualTo(expectedWidth);
                }
            }
        }
        assertThat(expectedWidth).as("Should have found table lines").isGreaterThan(0);
    }

    /**
     * Regression test for issue #185: markdown-rendered tables should not produce
     * excessive trailing blank lines that push the prompt down in printAbove.
     */
    @Test
    void renderedTable_doesNotEndWithExcessiveTrailingNewlines() {
        String md = """
                ## Summary

                | Metric | Value |
                |--------|-------|
                | Users  | 100   |
                | Errors | 0     |
                """;

        String result = renderer.renderToString(md);

        // Strip trailing whitespace (mimics the .stripTrailing() fix in TerminalRepl)
        String stripped = result.stripTrailing();
        // After stripping, re-adding a single \n is fine — but the raw output
        // should not have more than 2 trailing newlines (one after the table is normal).
        int trailingNewlines = result.length() - result.stripTrailing().length();
        assertThat(trailingNewlines)
                .as("Rendered table + heading should not produce excessive trailing newlines")
                .isLessThanOrEqualTo(2);

        // The stripped version, when split without -1, should not produce trailing empty entries
        String[] lines = stripped.split("\n");
        assertThat(lines[lines.length - 1])
                .as("Last line after split should not be empty")
                .isNotEmpty();
    }

    @Test
    void rendersNullAndEmptyGracefully() {
        assertThat(renderer.renderToString(null)).isEmpty();
        assertThat(renderer.renderToString("")).isEmpty();
    }

    private static String stripAnsi(String text) {
        return text.replaceAll("\u001B\\[[0-9;]*m", "");
    }
}

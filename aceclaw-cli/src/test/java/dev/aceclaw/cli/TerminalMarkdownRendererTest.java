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
    void rendersNullAndEmptyGracefully() {
        assertThat(renderer.renderToString(null)).isEmpty();
        assertThat(renderer.renderToString("")).isEmpty();
    }

    private static String stripAnsi(String text) {
        return text.replaceAll("\u001B\\[[0-9;]*m", "");
    }
}

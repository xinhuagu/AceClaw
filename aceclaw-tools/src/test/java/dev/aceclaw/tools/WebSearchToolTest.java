package dev.aceclaw.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WebSearchToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path workDir;

    private WebSearchTool ddgTool;
    private WebSearchTool braveTool;

    @BeforeEach
    void setUp() {
        ddgTool = new WebSearchTool(workDir);
        braveTool = new WebSearchTool(workDir, "test-api-key");
    }

    @Test
    void nameIsWebSearch() {
        assertThat(ddgTool.name()).isEqualTo("web_search");
    }

    @Test
    void descriptionIsNotEmpty() {
        assertThat(ddgTool.description()).isNotBlank();
    }

    @Test
    void ddgToolIsNotBraveEnabled() {
        assertThat(ddgTool.isBraveEnabled()).isFalse();
    }

    @Test
    void braveToolIsBraveEnabled() {
        assertThat(braveTool.isBraveEnabled()).isTrue();
    }

    @Test
    void missingQueryReturnsError() throws Exception {
        var input = MAPPER.writeValueAsString(MAPPER.createObjectNode());

        var result = ddgTool.execute(input);

        assertThat(result.isError()).isTrue();
        assertThat(result.output()).contains("Missing required parameter: query");
    }

    @Test
    void blankQueryReturnsError() throws Exception {
        var input = MAPPER.writeValueAsString(
                MAPPER.createObjectNode().put("query", "   "));

        var result = ddgTool.execute(input);

        assertThat(result.isError()).isTrue();
        assertThat(result.output()).contains("Missing required parameter: query");
    }

    @Test
    void inputSchemaHasRequiredQuery() {
        var schema = ddgTool.inputSchema();

        assertThat(schema.has("required")).isTrue();
        assertThat(schema.get("required").toString()).contains("query");
    }

    @Test
    void parseDdgResultsFromSampleHtml() throws IOException {
        String html = loadResource("ddg-lite-sample.html");

        var results = ddgTool.parseDdgResults(html, 5);

        assertThat(results).isNotEmpty();
        assertThat(results.size()).isLessThanOrEqualTo(5);

        var first = results.get(0);
        assertThat(first.title()).isNotBlank();
        assertThat(first.url()).startsWith("http");
    }

    @Test
    void parseDdgResultsRespectsMaxResults() throws IOException {
        String html = loadResource("ddg-lite-sample.html");

        var results = ddgTool.parseDdgResults(html, 2);

        assertThat(results.size()).isLessThanOrEqualTo(2);
    }

    @Test
    void parseDdgResultsEmptyHtml() {
        var results = ddgTool.parseDdgResults("<html><body></body></html>", 5);

        assertThat(results).isEmpty();
    }

    @Test
    void parseDdgResultsNoResultLinks() {
        // HTML with links but not result-link class, and all links are DDG internal
        String html = """
                <html><body>
                <a href="https://duckduckgo.com/about">About</a>
                </body></html>
                """;

        var results = ddgTool.parseDdgResults(html, 5);

        assertThat(results).isEmpty();
    }

    @Test
    void parseDdgResultsFallbackToGenericLinks() {
        // HTML with no result-link class but external links
        String html = """
                <html><body>
                <a href="https://example.com/page1">Example Page 1</a>
                <a href="https://example.com/page2">Example Page 2</a>
                </body></html>
                """;

        var results = ddgTool.parseDdgResults(html, 5);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).url()).isEqualTo("https://example.com/page1");
        assertThat(results.get(0).title()).isEqualTo("Example Page 1");
    }

    private static String loadResource(String name) throws IOException {
        try (InputStream is = WebSearchToolTest.class.getClassLoader().getResourceAsStream(name)) {
            if (is == null) throw new IOException("Resource not found: " + name);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

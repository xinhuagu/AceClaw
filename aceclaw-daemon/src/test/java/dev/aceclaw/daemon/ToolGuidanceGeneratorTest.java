package dev.aceclaw.daemon;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ToolGuidanceGeneratorTest {

    @Test
    void fullToolSetMentionsAllWebTools() {
        var tools = Set.of("read_file", "write_file", "edit_file", "bash", "glob", "grep",
                "list_directory", "web_fetch", "web_search", "browser", "screen_capture", "applescript", "skill");

        var result = ToolGuidanceGenerator.generate(tools, true);

        assertThat(result).contains("web_search");
        assertThat(result).contains("web_fetch");
        assertThat(result).contains("browser");
        assertThat(result).contains("screen_capture");
        assertThat(result).contains("applescript");
        assertThat(result).contains("click-precision-robust");
        assertThat(result).contains("click `Calendar` first");
        assertThat(result).contains("list_directory");
        assertThat(result).contains("Brave Search API");
    }

    @Test
    void minimalToolSetOmitsWebReferences() {
        var tools = Set.of("read_file", "write_file", "edit_file", "bash", "glob", "grep");

        var result = ToolGuidanceGenerator.generate(tools, false);

        assertThat(result).doesNotContain("**web_search**");
        assertThat(result).doesNotContain("**web_fetch**");
        assertThat(result).doesNotContain("**browser**");
        assertThat(result).doesNotContain("**screen_capture**");
        assertThat(result).doesNotContain("**applescript**");
    }

    @Test
    void noBraveKeyShowsDdgNote() {
        var tools = Set.of("read_file", "bash", "web_search", "web_fetch");

        var result = ToolGuidanceGenerator.generate(tools, false);

        assertThat(result).contains("DuckDuckGo");
        assertThat(result).contains("BRAVE_SEARCH_API_KEY");
    }

    @Test
    void withBraveKeyDoesNotShowDdgNote() {
        var tools = Set.of("read_file", "bash", "web_search", "web_fetch");

        var result = ToolGuidanceGenerator.generate(tools, true);

        // Should mention Brave, not DDG fallback note
        assertThat(result).contains("Brave Search API");
        // Should not show the availability note about DDG
        assertThat(result).doesNotContain("web_search is using DuckDuckGo");
    }

    @Test
    void noScreenCaptureDoesNotMentionIt() {
        var tools = Set.of("read_file", "bash", "web_search", "web_fetch", "browser");

        var result = ToolGuidanceGenerator.generate(tools, false);

        assertThat(result).doesNotContain("**screen_capture**");
    }

    @Test
    void fallbackChainAdaptsToAvailableTools() {
        var tools = Set.of("web_fetch", "bash");

        var result = ToolGuidanceGenerator.generate(tools, false);

        // Should start with web_fetch, not web_search
        assertThat(result).contains("web_fetch with a known URL");
        assertThat(result).contains("bash with curl");
        assertThat(result).doesNotContain("Try web_search");
    }

    @Test
    void compositionGuidanceAlwaysIncluded() {
        var tools = Set.of("read_file", "bash");

        var result = ToolGuidanceGenerator.generate(tools, false);

        assertThat(result).contains("Creative Tool Composition");
        assertThat(result).contains("NEVER ask the user to provide a URL");
    }

    @Test
    void emptyToolSetStillGenerates() {
        var result = ToolGuidanceGenerator.generate(Set.of(), false);

        // Should still produce composition guidance
        assertThat(result).contains("Creative Tool Composition");
        // No tool-specific lines
        assertThat(result).doesNotContain("**web_search**");
    }
}

package dev.aceclaw.tools;

import dev.aceclaw.core.agent.Tool.ToolResult;
import dev.aceclaw.memory.AutoMemoryStore;
import dev.aceclaw.memory.MemoryEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MemoryTool}.
 *
 * <p>Uses a real {@link AutoMemoryStore} backed by a temporary directory for full
 * integration coverage of save/search/list actions.
 */
class MemoryToolTest {

    @TempDir
    Path tempDir;

    private AutoMemoryStore store;
    private MemoryTool tool;
    private Path projectPath;

    @BeforeEach
    void setUp() throws IOException {
        store = new AutoMemoryStore(tempDir);
        projectPath = tempDir.resolve("workspace");
        Files.createDirectories(projectPath);
        store.load(projectPath);
        tool = new MemoryTool(store, projectPath);
    }

    /**
     * Builds a JSON object string from key-value pairs.
     * Example: json("action", "save", "content", "foo") -> {"action":"save","content":"foo"}
     */
    private static String json(String... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("Must provide key-value pairs");
        }
        var sb = new StringBuilder("{");
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(pairs[i]).append("\":");
            String value = pairs[i + 1];
            // Handle boolean and integer values without quotes
            if ("true".equals(value) || "false".equals(value) || value.matches("\\d+")) {
                sb.append(value);
            } else {
                sb.append("\"").append(value).append("\"");
            }
        }
        sb.append("}");
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Basic action routing
    // -----------------------------------------------------------------------

    @Test
    void nameIsMemory() {
        assertThat(tool.name()).isEqualTo("memory");
    }

    @Test
    void missingActionReturnsError() throws Exception {
        var result = tool.execute("{}");

        assertThat(result.isError()).isTrue();
        assertThat(result.output()).contains("Missing required parameter: action");
    }

    @Test
    void unknownActionReturnsError() throws Exception {
        var result = tool.execute(json("action", "delete"));

        assertThat(result.isError()).isTrue();
        assertThat(result.output()).contains("Unknown action");
        assertThat(result.output()).contains("delete");
    }

    // -----------------------------------------------------------------------
    // Save action
    // -----------------------------------------------------------------------

    @Test
    void saveRequiresContent() throws Exception {
        var result = tool.execute(json("action", "save", "category", "pattern"));

        assertThat(result.isError()).isTrue();
        assertThat(result.output()).contains("content");
    }

    @Test
    void saveRequiresCategory() throws Exception {
        var result = tool.execute(json("action", "save", "content", "Use records for DTOs"));

        assertThat(result.isError()).isTrue();
        assertThat(result.output()).contains("category");
    }

    @Test
    void saveWithValidParams() throws Exception {
        var result = tool.execute(json(
                "action", "save",
                "content", "Use records for DTOs",
                "category", "pattern",
                "tags", "java, dto"));

        assertThat(result.isError()).isFalse();
        assertThat(result.output()).contains("Memory saved successfully");
        assertThat(result.output()).contains("pattern");
        assertThat(result.output()).contains("java, dto");
    }

    @Test
    void savePersistsToStore() throws Exception {
        tool.execute(json(
                "action", "save",
                "content", "Use records for DTOs",
                "category", "pattern"));

        var entries = store.query(MemoryEntry.Category.PATTERN, null, 10);
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().content()).isEqualTo("Use records for DTOs");
        assertThat(entries.getFirst().category()).isEqualTo(MemoryEntry.Category.PATTERN);
    }

    @Test
    void saveWithInvalidCategory() throws Exception {
        var result = tool.execute(json(
                "action", "save",
                "content", "some insight",
                "category", "nonexistent"));

        assertThat(result.isError()).isTrue();
        assertThat(result.output()).contains("Invalid category");
        assertThat(result.output()).contains("nonexistent");
        // Should list valid categories
        assertThat(result.output()).contains("mistake");
        assertThat(result.output()).contains("pattern");
    }

    @Test
    void saveCategoryIsCaseInsensitive() throws Exception {
        var resultUpper = tool.execute(json(
                "action", "save",
                "content", "Uppercase test",
                "category", "PATTERN"));

        var resultLower = tool.execute(json(
                "action", "save",
                "content", "Lowercase test",
                "category", "pattern"));

        var resultMixed = tool.execute(json(
                "action", "save",
                "content", "Mixed case test",
                "category", "Pattern"));

        assertThat(resultUpper.isError()).isFalse();
        assertThat(resultLower.isError()).isFalse();
        assertThat(resultMixed.isError()).isFalse();

        var entries = store.query(MemoryEntry.Category.PATTERN, null, 10);
        assertThat(entries).hasSize(3);
    }

    @Test
    void saveWithGlobalFlag() throws Exception {
        var result = tool.execute(json(
                "action", "save",
                "content", "Global insight",
                "category", "strategy",
                "global", "true"));

        assertThat(result.isError()).isFalse();
        assertThat(result.output()).contains("global");
    }

    @Test
    void saveWithEmptyTags() throws Exception {
        var result = tool.execute(json(
                "action", "save",
                "content", "No tags insight",
                "category", "pattern"));

        assertThat(result.isError()).isFalse();
        assertThat(result.output()).contains("(none)");

        var entries = store.query(MemoryEntry.Category.PATTERN, null, 10);
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().tags()).isEmpty();
    }

    @Test
    void saveWithCommaSeparatedTags() throws Exception {
        tool.execute(json(
                "action", "save",
                "content", "Tagged insight",
                "category", "pattern",
                "tags", "gradle, java, testing"));

        var entries = store.query(MemoryEntry.Category.PATTERN, null, 10);
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().tags()).containsExactly("gradle", "java", "testing");
    }

    // -----------------------------------------------------------------------
    // Search action
    // -----------------------------------------------------------------------

    @Test
    void searchRequiresQuery() throws Exception {
        var result = tool.execute(json("action", "search"));

        assertThat(result.isError()).isTrue();
        assertThat(result.output()).contains("query");
    }

    @Test
    void searchReturnsResults() throws Exception {
        // Save some entries first
        tool.execute(json(
                "action", "save",
                "content", "Use records for immutable DTOs in Java",
                "category", "pattern",
                "tags", "java, records"));
        tool.execute(json(
                "action", "save",
                "content", "Always run gradle tests before commit",
                "category", "workflow",
                "tags", "gradle, testing"));

        var result = tool.execute(json("action", "search", "query", "records DTOs Java"));

        assertThat(result.isError()).isFalse();
        assertThat(result.output()).contains("Search results");
        assertThat(result.output()).contains("records");
    }

    @Test
    void searchWithNoResults() throws Exception {
        var result = tool.execute(json("action", "search", "query", "quantum computing flux capacitor"));

        assertThat(result.isError()).isFalse();
        assertThat(result.output()).contains("No memories found");
    }

    @Test
    void searchWithCategoryFilter() throws Exception {
        tool.execute(json(
                "action", "save",
                "content", "Use sealed interfaces for type hierarchies",
                "category", "pattern",
                "tags", "java"));
        tool.execute(json(
                "action", "save",
                "content", "Forgot to check null on sealed interface",
                "category", "mistake",
                "tags", "java"));

        var result = tool.execute(json(
                "action", "search",
                "query", "sealed interfaces java",
                "category", "pattern"));

        assertThat(result.isError()).isFalse();
        // Should contain the pattern entry
        assertThat(result.output()).contains("PATTERN");
        // Should not contain the mistake entry
        assertThat(result.output()).doesNotContain("MISTAKE");
    }

    @Test
    void searchWithInvalidCategory() throws Exception {
        var result = tool.execute(json(
                "action", "search",
                "query", "something",
                "category", "bogus"));

        assertThat(result.isError()).isTrue();
        assertThat(result.output()).contains("Invalid category");
        assertThat(result.output()).contains("bogus");
    }

    @Test
    void searchRespectsLimit() throws Exception {
        // Save 5 entries with similar content so they all match a search
        for (int i = 1; i <= 5; i++) {
            tool.execute(json(
                    "action", "save",
                    "content", "Java pattern insight number " + i,
                    "category", "pattern",
                    "tags", "java"));
        }

        var result = tool.execute(json(
                "action", "search",
                "query", "Java pattern insight",
                "limit", "2"));

        assertThat(result.isError()).isFalse();
        // Count the number of "## [PATTERN]" occurrences in the output
        long entryCount = result.output().lines()
                .filter(line -> line.startsWith("## [PATTERN]"))
                .count();
        assertThat(entryCount).isLessThanOrEqualTo(2);
    }

    // -----------------------------------------------------------------------
    // List action
    // -----------------------------------------------------------------------

    @Test
    void listReturnsAllMemories() throws Exception {
        tool.execute(json("action", "save", "content", "Insight A", "category", "pattern"));
        tool.execute(json("action", "save", "content", "Insight B", "category", "mistake"));
        tool.execute(json("action", "save", "content", "Insight C", "category", "strategy"));

        var result = tool.execute(json("action", "list"));

        assertThat(result.isError()).isFalse();
        assertThat(result.output()).contains("3 entries");
        assertThat(result.output()).contains("Insight A");
        assertThat(result.output()).contains("Insight B");
        assertThat(result.output()).contains("Insight C");
    }

    @Test
    void listEmptyStore() throws Exception {
        var result = tool.execute(json("action", "list"));

        assertThat(result.isError()).isFalse();
        assertThat(result.output()).contains("No memories stored yet");
    }

    @Test
    void listWithCategoryFilter() throws Exception {
        tool.execute(json("action", "save", "content", "Pattern entry", "category", "pattern"));
        tool.execute(json("action", "save", "content", "Mistake entry", "category", "mistake"));
        tool.execute(json("action", "save", "content", "Another mistake", "category", "mistake"));

        var result = tool.execute(json("action", "list", "category", "mistake"));

        assertThat(result.isError()).isFalse();
        assertThat(result.output()).contains("Mistake entry");
        assertThat(result.output()).contains("Another mistake");
        assertThat(result.output()).doesNotContain("Pattern entry");
    }

    @Test
    void listWithInvalidCategory() throws Exception {
        var result = tool.execute(json("action", "list", "category", "bogus"));

        assertThat(result.isError()).isTrue();
        assertThat(result.output()).contains("Invalid category");
        assertThat(result.output()).contains("bogus");
    }

    @Test
    void listRespectsLimit() throws Exception {
        for (int i = 1; i <= 5; i++) {
            tool.execute(json("action", "save", "content", "Entry " + i, "category", "pattern"));
        }

        var result = tool.execute(json("action", "list", "limit", "3"));

        assertThat(result.isError()).isFalse();
        // Count formatted entry lines
        long entryCount = result.output().lines()
                .filter(line -> line.startsWith("## ["))
                .count();
        assertThat(entryCount).isEqualTo(3);
    }

    // -----------------------------------------------------------------------
    // Edge cases
    // -----------------------------------------------------------------------

    @Test
    void saveWithBlankContent() throws Exception {
        var resultEmpty = tool.execute(json(
                "action", "save",
                "content", "",
                "category", "pattern"));
        assertThat(resultEmpty.isError()).isTrue();
        assertThat(resultEmpty.output()).contains("content");

        var resultSpaces = tool.execute(json(
                "action", "save",
                "content", "   ",
                "category", "pattern"));
        assertThat(resultSpaces.isError()).isTrue();
        assertThat(resultSpaces.output()).contains("content");
    }

    @Test
    void actionIsCaseInsensitive() throws Exception {
        var resultUpper = tool.execute(json(
                "action", "SAVE",
                "content", "Uppercase action",
                "category", "pattern"));
        assertThat(resultUpper.isError()).isFalse();
        assertThat(resultUpper.output()).contains("Memory saved successfully");

        var resultMixed = tool.execute(json(
                "action", "Search",
                "query", "Uppercase"));
        assertThat(resultMixed.isError()).isFalse();

        var resultListUpper = tool.execute(json("action", "LIST"));
        assertThat(resultListUpper.isError()).isFalse();
    }
}

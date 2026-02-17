package dev.aceclaw.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AutoMemoryStoreTest {

    @TempDir
    Path tempDir;

    private AutoMemoryStore store;
    private Path projectPath;

    @BeforeEach
    void setUp() throws IOException {
        store = new AutoMemoryStore(tempDir);
        projectPath = tempDir.resolve("workspace");
        Files.createDirectories(projectPath);
        store.load(projectPath);
    }

    @Test
    void addAndQuery() {
        store.add(MemoryEntry.Category.MISTAKE, "Always use UTF-8 encoding",
                List.of("java", "encoding"), "test", false, projectPath);

        var results = store.query(MemoryEntry.Category.MISTAKE, null, 10);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().content()).isEqualTo("Always use UTF-8 encoding");
        assertThat(results.getFirst().category()).isEqualTo(MemoryEntry.Category.MISTAKE);
    }

    @Test
    void persistAcrossRestart() throws IOException {
        store.add(MemoryEntry.Category.PATTERN, "Use records for DTOs",
                List.of("java", "records"), "test", false, projectPath);
        store.add(MemoryEntry.Category.STRATEGY, "Prefer composition over inheritance",
                List.of("design"), "test", true, null);

        // Create a fresh store instance and load
        var store2 = new AutoMemoryStore(tempDir);
        store2.load(projectPath);

        assertThat(store2.size()).isEqualTo(2);
        assertThat(store2.query(MemoryEntry.Category.PATTERN, null, 10)).hasSize(1);
        assertThat(store2.query(MemoryEntry.Category.STRATEGY, null, 10)).hasSize(1);
    }

    @Test
    void tamperDetection() throws IOException {
        store.add(MemoryEntry.Category.MISTAKE, "Original content",
                List.of("test"), "test", true, null);
        assertThat(store.size()).isEqualTo(1);

        // Tamper with the global file
        Path globalFile = tempDir.resolve("memory").resolve("global.jsonl");
        String content = Files.readString(globalFile);
        String tampered = content.replace("Original content", "Tampered content");
        Files.writeString(globalFile, tampered);

        // Reload and verify tampered entry is skipped
        var store2 = new AutoMemoryStore(tempDir);
        store2.load(projectPath);
        assertThat(store2.size()).isEqualTo(0);
    }

    @Test
    void backwardCompatWithOldCategories() throws IOException {
        // All original 5 categories should still work
        for (var cat : List.of(
                MemoryEntry.Category.MISTAKE,
                MemoryEntry.Category.PATTERN,
                MemoryEntry.Category.PREFERENCE,
                MemoryEntry.Category.CODEBASE_INSIGHT,
                MemoryEntry.Category.STRATEGY)) {
            store.add(cat, "Entry for " + cat, List.of("test"), "test", true, null);
        }

        var store2 = new AutoMemoryStore(tempDir);
        store2.load(projectPath);
        assertThat(store2.size()).isEqualTo(5);
    }

    @Test
    void newCategoriesPersistAndLoad() throws IOException {
        var newCategories = List.of(
                MemoryEntry.Category.WORKFLOW,
                MemoryEntry.Category.ENVIRONMENT,
                MemoryEntry.Category.RELATIONSHIP,
                MemoryEntry.Category.TERMINOLOGY,
                MemoryEntry.Category.CONSTRAINT,
                MemoryEntry.Category.DECISION,
                MemoryEntry.Category.TOOL_USAGE,
                MemoryEntry.Category.COMMUNICATION,
                MemoryEntry.Category.CONTEXT,
                MemoryEntry.Category.CORRECTION,
                MemoryEntry.Category.BOOKMARK);

        for (var cat : newCategories) {
            store.add(cat, "Entry for " + cat.name(), List.of("new"), "test", true, null);
        }

        var store2 = new AutoMemoryStore(tempDir);
        store2.load(projectPath);
        assertThat(store2.size()).isEqualTo(newCategories.size());
    }

    @Test
    void keyFilePermissions() throws IOException {
        Path keyFile = tempDir.resolve("memory").resolve("memory.key");
        assertThat(Files.exists(keyFile)).isTrue();

        try {
            var perms = Files.getPosixFilePermissions(keyFile);
            assertThat(perms).containsExactlyInAnyOrder(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE);
        } catch (UnsupportedOperationException e) {
            // Non-POSIX filesystem, skip assertion
        }
    }

    @Test
    void formatForPromptIncludesNewCategories() {
        store.add(MemoryEntry.Category.WORKFLOW, "Always run tests before commit",
                List.of("ci"), "test", false, projectPath);
        store.add(MemoryEntry.Category.BOOKMARK, "See src/Main.java:42",
                List.of("reference"), "test", false, projectPath);

        String prompt = store.formatForPrompt(projectPath, 50);
        assertThat(prompt).contains("Workflows");
        assertThat(prompt).contains("Bookmarks");
        assertThat(prompt).contains("Always run tests before commit");
    }

    @Test
    void removeEntry() throws IOException {
        var entry = store.add(MemoryEntry.Category.MISTAKE, "Remove me",
                List.of("test"), "test", false, projectPath);

        assertThat(store.size()).isEqualTo(1);
        boolean removed = store.remove(entry.id(), projectPath);
        assertThat(removed).isTrue();
        assertThat(store.size()).isEqualTo(0);

        // Verify persisted
        var store2 = new AutoMemoryStore(tempDir);
        store2.load(projectPath);
        assertThat(store2.size()).isEqualTo(0);
    }

    @Test
    void queryByTagFilter() {
        store.add(MemoryEntry.Category.PATTERN, "Use sealed interfaces",
                List.of("java", "design"), "test", false, projectPath);
        store.add(MemoryEntry.Category.PATTERN, "Use gradle kotlin DSL",
                List.of("gradle", "build"), "test", false, projectPath);

        var javaResults = store.query(MemoryEntry.Category.PATTERN, List.of("java"), 10);
        assertThat(javaResults).hasSize(1);
        assertThat(javaResults.getFirst().content()).contains("sealed interfaces");
    }
}

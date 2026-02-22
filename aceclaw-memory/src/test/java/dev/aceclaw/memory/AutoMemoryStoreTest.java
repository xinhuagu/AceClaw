package dev.aceclaw.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
    void selfLearningCategoriesAccepted() throws IOException {
        var selfLearningCats = List.of(
                MemoryEntry.Category.SESSION_SUMMARY,
                MemoryEntry.Category.ERROR_RECOVERY,
                MemoryEntry.Category.SUCCESSFUL_STRATEGY,
                MemoryEntry.Category.ANTI_PATTERN,
                MemoryEntry.Category.USER_FEEDBACK,
                MemoryEntry.Category.FAILURE_SIGNAL);

        for (var cat : selfLearningCats) {
            store.add(cat, "Entry for " + cat.name(), List.of("self-learning"), "test", true, null);
        }

        var store2 = new AutoMemoryStore(tempDir);
        store2.load(projectPath);
        assertThat(store2.size()).isEqualTo(selfLearningCats.size());
    }

    @Test
    void accessCountDefaultsToZero() {
        var entry = store.add(MemoryEntry.Category.PATTERN, "Test pattern",
                List.of("test"), "test", false, projectPath);

        assertThat(entry.accessCount()).isEqualTo(0);
        assertThat(entry.lastAccessedAt()).isNull();
    }

    @Test
    void accessCountIncrementedOnQueryAndSearch() {
        store.add(MemoryEntry.Category.PATTERN, "Use sealed interfaces",
                List.of("java"), "test", false, projectPath);

        // Query increments accessCount
        store.query(MemoryEntry.Category.PATTERN, null, 10);
        var afterQuery = store.entries().stream()
                .filter(e -> e.content().equals("Use sealed interfaces"))
                .findFirst().orElseThrow();
        assertThat(afterQuery.accessCount()).isEqualTo(1);
        assertThat(afterQuery.lastAccessedAt()).isNotNull();

        // Search increments again
        store.search("sealed interfaces", null, 10);
        var afterSearch = store.entries().stream()
                .filter(e -> e.content().equals("Use sealed interfaces"))
                .findFirst().orElseThrow();
        assertThat(afterSearch.accessCount()).isEqualTo(2);
    }

    @Test
    void formatForPromptIncludesSelfLearningCategories() {
        store.add(MemoryEntry.Category.ERROR_RECOVERY, "Fixed by clearing cache",
                List.of("cache"), "test", false, projectPath);
        store.add(MemoryEntry.Category.ANTI_PATTERN, "Never use Thread.sleep in tests",
                List.of("testing"), "test", false, projectPath);

        String prompt = store.formatForPrompt(projectPath, 50);
        assertThat(prompt).contains("Error Recoveries");
        assertThat(prompt).contains("Anti-Patterns");
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

    // =========================================================================
    // File locking and concurrent write safety
    // =========================================================================

    @Test
    void concurrentAddsProduceValidJsonl() throws Exception {
        int threadCount = 10;
        int entriesPerThread = 20;
        var latch = new CountDownLatch(1);
        var errors = new AtomicInteger(0);

        try (ExecutorService executor = Executors.newFixedThreadPool(threadCount)) {
            for (int t = 0; t < threadCount; t++) {
                int threadId = t;
                executor.submit(() -> {
                    try {
                        latch.await();
                        for (int i = 0; i < entriesPerThread; i++) {
                            store.add(MemoryEntry.Category.PATTERN,
                                    "Thread " + threadId + " entry " + i,
                                    List.of("concurrent"), "test-" + threadId,
                                    false, projectPath);
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                });
            }

            // Release all threads simultaneously
            latch.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(errors.get()).isEqualTo(0);
        assertThat(store.size()).isEqualTo(threadCount * entriesPerThread);

        // Verify the JSONL file is valid by reloading from disk
        var freshStore = new AutoMemoryStore(tempDir);
        freshStore.load(projectPath);
        assertThat(freshStore.size()).isEqualTo(threadCount * entriesPerThread);
    }

    @Test
    void concurrentAddAndRemoveDoNotCorruptFile() throws Exception {
        // Pre-populate with entries to remove
        var idsToRemove = new ArrayList<String>();
        for (int i = 0; i < 10; i++) {
            var entry = store.add(MemoryEntry.Category.MISTAKE, "Entry to remove " + i,
                    List.of("remove"), "setup", false, projectPath);
            idsToRemove.add(entry.id());
        }

        var latch = new CountDownLatch(1);
        var errors = new AtomicInteger(0);

        try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
            // Thread 1-2: add new entries
            for (int t = 0; t < 2; t++) {
                int threadId = t;
                executor.submit(() -> {
                    try {
                        latch.await();
                        for (int i = 0; i < 10; i++) {
                            store.add(MemoryEntry.Category.PATTERN,
                                    "New entry " + threadId + "-" + i,
                                    List.of("new"), "adder-" + threadId,
                                    false, projectPath);
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                });
            }

            // Thread 3-4: remove pre-populated entries
            for (int t = 0; t < 2; t++) {
                int start = t * 5;
                executor.submit(() -> {
                    try {
                        latch.await();
                        for (int i = start; i < start + 5; i++) {
                            store.remove(idsToRemove.get(i), projectPath);
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                });
            }

            latch.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(errors.get()).isEqualTo(0);

        // Verify file integrity by reloading
        var freshStore = new AutoMemoryStore(tempDir);
        freshStore.load(projectPath);
        assertThat(freshStore.size()).isEqualTo(store.size());
    }

    // =========================================================================
    // Proactive strategy injection — per-tool grouping
    // =========================================================================

    @Test
    void formatEntriesGroupsErrorRecoveriesByTool() {
        store.add(MemoryEntry.Category.ERROR_RECOVERY,
                "read_file: file not found, check path",
                List.of("read_file", "error-recovery"), "test", false, projectPath);
        store.add(MemoryEntry.Category.ERROR_RECOVERY,
                "bash: permission denied, use sudo",
                List.of("bash", "error-recovery"), "test", false, projectPath);

        String prompt = store.formatForPrompt(projectPath, 50);
        assertThat(prompt).contains("## Error Recoveries");
        assertThat(prompt).contains("### read_file");
        assertThat(prompt).contains("### bash");
    }

    @Test
    void formatEntriesGroupsRecoveryRecipesByTool() {
        store.add(MemoryEntry.Category.RECOVERY_RECIPE,
                "Recovery recipe: detect encoding -> convert -> retry",
                List.of("read_file", "recovery-recipe"), "test", false, projectPath);

        String prompt = store.formatForPrompt(projectPath, 50);
        assertThat(prompt).contains("## Recovery Recipes");
        assertThat(prompt).contains("### read_file");
    }

    @Test
    void formatEntriesKeepsFlatFormatForNonStrategyCategories() {
        store.add(MemoryEntry.Category.PATTERN,
                "Use records for DTOs",
                List.of("java", "design"), "test", false, projectPath);

        String prompt = store.formatForPrompt(projectPath, 50);
        assertThat(prompt).contains("## Code Patterns");
        // Should NOT have ### subheadings for non-strategy categories
        assertThat(prompt).doesNotContain("### java");
    }

    @Test
    void formatToolStrategiesReturnsFilteredEntries() {
        store.add(MemoryEntry.Category.ERROR_RECOVERY,
                "read_file: missing path resolved",
                List.of("read_file", "error-recovery"), "test", false, projectPath);
        store.add(MemoryEntry.Category.SUCCESSFUL_STRATEGY,
                "grep then read works well",
                List.of("grep", "successful-strategy"), "test", false, projectPath);
        store.add(MemoryEntry.Category.RECOVERY_RECIPE,
                "encoding recovery recipe",
                List.of("read_file", "recovery-recipe"), "test", false, projectPath);

        String strategies = store.formatToolStrategies("read_file", 10);
        assertThat(strategies).contains("Strategies for read_file");
        assertThat(strategies).contains("read_file: missing path resolved");
        assertThat(strategies).contains("encoding recovery recipe");
        assertThat(strategies).doesNotContain("grep then read");
    }

    @Test
    void formatToolStrategiesReturnsEmptyForUnknownTool() {
        store.add(MemoryEntry.Category.ERROR_RECOVERY,
                "some error",
                List.of("bash"), "test", false, projectPath);

        String strategies = store.formatToolStrategies("unknown_tool", 10);
        assertThat(strategies).isEmpty();
    }

    @Test
    void atomicRewritePreservesDataOnReload() throws IOException {
        // Add entries, consolidate (triggers rewrite), then verify reload
        store.add(MemoryEntry.Category.PATTERN, "Keep this",
                List.of("test"), "test", false, projectPath);
        store.add(MemoryEntry.Category.MISTAKE, "Keep this too",
                List.of("test"), "test", false, projectPath);

        // Trigger rewrite via replaceEntries
        store.replaceEntries(new ArrayList<>(store.entries()), projectPath);

        // Verify no .tmp files left behind
        var tmpFiles = Files.list(tempDir.resolve("memory"))
                .filter(p -> p.toString().endsWith(".tmp"))
                .toList();
        assertThat(tmpFiles).isEmpty();

        // Verify data survived
        var freshStore = new AutoMemoryStore(tempDir);
        freshStore.load(projectPath);
        assertThat(freshStore.size()).isEqualTo(2);
    }
}

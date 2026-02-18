package dev.aceclaw.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryConsolidatorTest {

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
    void exactDedupRemovesDuplicates() {
        store.add(MemoryEntry.Category.MISTAKE, "Never use System.exit",
                List.of("java"), "test1", false, projectPath);
        store.add(MemoryEntry.Category.MISTAKE, "Never use System.exit",
                List.of("java"), "test2", false, projectPath);
        store.add(MemoryEntry.Category.PATTERN, "Use records for DTOs",
                List.of("java"), "test3", false, projectPath);

        assertThat(store.size()).isEqualTo(3);

        var result = MemoryConsolidator.consolidate(store, projectPath, tempDir.resolve("archive"));

        assertThat(result.deduped()).isEqualTo(1);
        assertThat(store.size()).isEqualTo(2);
    }

    @Test
    void similarityMergeCombinesEntries() {
        // Two entries with >80% token overlap in same category
        store.add(MemoryEntry.Category.PATTERN, "Use sealed interfaces for type hierarchies in Java",
                List.of("java", "design"), "test1", false, projectPath);
        store.add(MemoryEntry.Category.PATTERN, "Use sealed interfaces for type hierarchies in Java code",
                List.of("java", "patterns"), "test2", false, projectPath);

        assertThat(store.size()).isEqualTo(2);

        var result = MemoryConsolidator.consolidate(store, projectPath, tempDir.resolve("archive"));

        assertThat(result.merged()).isGreaterThanOrEqualTo(1);
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void agePruneArchivesOldEntries() {
        // Manually create entries with old timestamps using internal list manipulation
        var entries = new ArrayList<>(store.entries());

        // Add a recent entry
        store.add(MemoryEntry.Category.PATTERN, "Recent pattern",
                List.of("test"), "test", false, projectPath);

        // Add an old entry with 0 access count (simulated via direct list manipulation)
        var oldEntry = new MemoryEntry(
                java.util.UUID.randomUUID().toString(),
                MemoryEntry.Category.PATTERN,
                "Very old pattern that is no longer relevant",
                List.of("old"),
                Instant.now().minus(100, ChronoUnit.DAYS),
                "test",
                "skip-hmac",
                0,
                null);
        entries = new ArrayList<>(store.entries());
        entries.add(oldEntry);
        store.replaceEntries(entries, projectPath);

        assertThat(store.size()).isEqualTo(2);

        Path archiveDir = tempDir.resolve("archive");
        var result = MemoryConsolidator.consolidate(store, projectPath, archiveDir);

        assertThat(result.pruned()).isEqualTo(1);
        assertThat(store.size()).isEqualTo(1);
        assertThat(Files.exists(archiveDir.resolve("archived.jsonl"))).isTrue();
    }

    @Test
    void consolidateRunsAllPasses() {
        // Add duplicates
        store.add(MemoryEntry.Category.MISTAKE, "Same content here",
                List.of("a"), "test", false, projectPath);
        store.add(MemoryEntry.Category.MISTAKE, "Same content here",
                List.of("b"), "test", false, projectPath);

        var result = MemoryConsolidator.consolidate(store, projectPath, tempDir.resolve("archive"));

        assertThat(result.hasChanges()).isTrue();
        assertThat(result.deduped()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void agePruneSkipsWhenArchiveDirNull() throws IOException {
        // Add an old entry with 0 access count
        var entries = new ArrayList<>(store.entries());
        var oldEntry = new MemoryEntry(
                java.util.UUID.randomUUID().toString(),
                MemoryEntry.Category.PATTERN,
                "Old pattern that should NOT be deleted",
                List.of("old"),
                Instant.now().minus(100, ChronoUnit.DAYS),
                "test",
                "skip-hmac",
                0,
                null);
        entries.add(oldEntry);
        store.replaceEntries(entries, projectPath);

        assertThat(store.size()).isEqualTo(1);

        // Consolidate with null archiveDir — should NOT prune (prevents data loss)
        var result = MemoryConsolidator.consolidate(store, projectPath, null);

        assertThat(result.pruned()).isEqualTo(0);
        assertThat(store.size()).isEqualTo(1); // entry preserved
    }

    @Test
    void emptyStoreNoOp() {
        var result = MemoryConsolidator.consolidate(store, projectPath, tempDir.resolve("archive"));

        assertThat(result.deduped()).isEqualTo(0);
        assertThat(result.merged()).isEqualTo(0);
        assertThat(result.pruned()).isEqualTo(0);
        assertThat(result.hasChanges()).isFalse();
    }

    @Test
    void tokenSimilarity() {
        // Identical strings
        assertThat(MemoryConsolidator.tokenSimilarity("hello world", "hello world"))
                .isEqualTo(1.0);

        // Completely different
        assertThat(MemoryConsolidator.tokenSimilarity("hello world", "goodbye universe"))
                .isEqualTo(0.0);

        // Partial overlap
        double sim = MemoryConsolidator.tokenSimilarity(
                "use sealed interfaces for types",
                "use sealed interfaces for type hierarchies");
        assertThat(sim).isBetween(0.5, 1.0);
    }
}

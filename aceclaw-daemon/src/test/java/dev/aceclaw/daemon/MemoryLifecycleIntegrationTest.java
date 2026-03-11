package dev.aceclaw.daemon;

import dev.aceclaw.memory.*;
import dev.aceclaw.tools.MemoryTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test for the memory lifecycle:
 * session start → system prompt assembly → memory tool use →
 * session end extraction → memory consolidation.
 *
 * <p>Tests the full memory data flow without UDS or LLM clients,
 * exercising all memory components together.
 */
class MemoryLifecycleIntegrationTest {

    @TempDir
    Path tempDir;

    private Path aceclawHome;
    private Path workspacePath;
    private AutoMemoryStore memoryStore;
    private MarkdownMemoryStore markdownStore;
    private DailyJournal journal;

    @BeforeEach
    void setUp() throws IOException {
        aceclawHome = tempDir.resolve(".aceclaw");
        Files.createDirectories(aceclawHome);
        workspacePath = tempDir.resolve("workspace");
        Files.createDirectories(workspacePath);

        memoryStore = AutoMemoryStore.forWorkspace(aceclawHome, workspacePath);
        markdownStore = MarkdownMemoryStore.forWorkspace(aceclawHome, workspacePath);
        journal = new DailyJournal(aceclawHome.resolve("memory"));
    }

    // =========================================================================
    // Session Start: system prompt includes all memory tiers
    // =========================================================================

    @Test
    void systemPromptIncludesAllConfiguredTiers() throws IOException {
        // Set up project-level tiers (User Memory uses real ~/.aceclaw, tested in MemoryTierLoaderTest)
        Files.writeString(workspacePath.resolve("ACECLAW.md"), "Use Java 21 records.");
        markdownStore.writeMemoryMd("# Project Notes\nKey insight here.");
        memoryStore.add(MemoryEntry.Category.PATTERN, "Use sealed interfaces",
                List.of("java"), "test", false, workspacePath);
        journal.append("Previous session: refactored auth module");

        String prompt = SystemPromptLoader.load(
                workspacePath, memoryStore, journal, markdownStore, "test-model", "anthropic");

        assertThat(prompt).contains("Project Instructions");
        assertThat(prompt).contains("Use Java 21 records.");
        assertThat(prompt).contains("Persistent Memory");
        assertThat(prompt).contains("Key insight here.");
        assertThat(prompt).contains("Auto-Memory");
        assertThat(prompt).contains("sealed interfaces");
        assertThat(prompt).contains("Daily Journal");
        assertThat(prompt).contains("refactored auth module");
    }

    // =========================================================================
    // P0: System prompt includes MEMORY.md directory path
    // =========================================================================

    @Test
    void systemPromptIncludesMarkdownMemoryPath() throws IOException {
        markdownStore.writeMemoryMd("# Notes\nSome content.");

        String prompt = SystemPromptLoader.load(
                workspacePath, memoryStore, journal, markdownStore, "test-model", "anthropic");

        // Agent should know the filesystem path to MEMORY.md
        assertThat(prompt).contains("persistent memory directory at `");
        assertThat(prompt).contains(markdownStore.memoryDir().toString());
    }

    @Test
    void systemPromptDocumentsTopicFiles() throws IOException {
        markdownStore.writeMemoryMd("# Notes\nSome content.");

        String prompt = SystemPromptLoader.load(
                workspacePath, memoryStore, journal, markdownStore, "test-model", "anthropic");

        // P2: Agent should know about topic files
        assertThat(prompt).contains("topic files");
        assertThat(prompt).contains("debugging.md");
        assertThat(prompt).contains("read_file");
        assertThat(prompt).contains("write_file");
    }

    // =========================================================================
    // Memory Tool: save, search, list
    // =========================================================================

    @Test
    void memoryToolSaveAndSearch() throws Exception {
        var tool = new MemoryTool(memoryStore, workspacePath);

        // Save a memory
        var saveResult = tool.execute(
                "{\"action\":\"save\",\"content\":\"Gradle requires --enable-preview\"," +
                "\"category\":\"codebase_insight\",\"tags\":\"gradle,java\"}");
        assertThat(saveResult.isError()).isFalse();
        assertThat(saveResult.output()).contains("Memory saved successfully");

        // Search for it
        var searchResult = tool.execute(
                "{\"action\":\"search\",\"query\":\"gradle preview\"}");
        assertThat(searchResult.isError()).isFalse();
        assertThat(searchResult.output()).contains("enable-preview");

        // List by category
        var listResult = tool.execute(
                "{\"action\":\"list\",\"category\":\"codebase_insight\"}");
        assertThat(listResult.isError()).isFalse();
        assertThat(listResult.output()).contains("CODEBASE_INSIGHT");
    }

    // =========================================================================
    // Session End: extraction + consolidation
    // =========================================================================

    @Test
    void sessionEndExtractionAndConsolidation() throws IOException {
        // Simulate a conversation with corrections and preferences
        var messages = List.<AgentSession.ConversationMessage>of(
                new AgentSession.ConversationMessage.User("Fix the build"),
                new AgentSession.ConversationMessage.Assistant("I'll use Maven to fix it."),
                new AgentSession.ConversationMessage.User("No, use Gradle instead of Maven"),
                new AgentSession.ConversationMessage.Assistant("OK, using Gradle."),
                new AgentSession.ConversationMessage.User("Always use ./gradlew, never gradle directly")
        );

        // Extract memories from conversation
        var extracted = SessionEndExtractor.extract(messages);
        assertThat(extracted).isNotEmpty();
        var learnings = new SessionAnalyzer().analyze(messages, java.util.Map.of());
        assertThat(learnings.sessionSummary()).contains("Session retrospective");

        // Save extracted memories to store (as the daemon callback does)
        for (var mem : extracted) {
            memoryStore.add(mem.category(), mem.content(), mem.tags(),
                    "session-end:test-session", false, workspacePath);
        }
        for (var insight : learnings.insights()) {
            memoryStore.addIfAbsent(
                    insight.category(), insight.content(), insight.tags(),
                    "session-analysis:test-session", false, workspacePath);
        }
        int sizeAfterExtraction = memoryStore.size();
        assertThat(sizeAfterExtraction).isGreaterThan(0);

        // Run consolidation
        Path archiveDir = markdownStore.memoryDir();
        var consolidationResult = MemoryConsolidator.consolidate(
                memoryStore, workspacePath, archiveDir);

        // No changes expected for fresh entries (no duplicates, not old enough to prune)
        assertThat(memoryStore.size()).isEqualTo(sizeAfterExtraction);

        // Journal should record the activity
        journal.append("Session test-session ended: " + messages.size() +
                " messages, " + extracted.size() + " memories extracted");
        journal.append("Session retrospective (test-ses): " + learnings.sessionSummary());
        var journalEntries = journal.loadRecentWindow();
        assertThat(journalEntries).anyMatch(e -> e.contains("memories extracted"));
        assertThat(journalEntries).anyMatch(e -> e.contains("Session retrospective"));
    }

    @Test
    void duplicateMemoriesConsolidated() throws IOException {
        // Add the same memory twice (simulates extraction from two similar sessions)
        memoryStore.add(MemoryEntry.Category.CORRECTION, "Use Gradle not Maven",
                List.of("build"), "session1", false, workspacePath);
        memoryStore.add(MemoryEntry.Category.CORRECTION, "Use Gradle not Maven",
                List.of("build"), "session2", false, workspacePath);

        assertThat(memoryStore.size()).isEqualTo(2);

        var result = MemoryConsolidator.consolidate(
                memoryStore, workspacePath, markdownStore.memoryDir());

        assertThat(result.deduped()).isEqualTo(1);
        assertThat(memoryStore.size()).isEqualTo(1);
    }

    // =========================================================================
    // P3: Archive dir null prevents data loss
    // =========================================================================

    @Test
    void consolidationWithNullArchiveDirPreservesEntries() throws IOException {
        // Add an old entry via direct list manipulation
        var oldEntry = new MemoryEntry(
                java.util.UUID.randomUUID().toString(),
                MemoryEntry.Category.PATTERN,
                "Old but valuable pattern",
                List.of("test"),
                java.time.Instant.now().minus(java.time.Duration.ofDays(100)),
                "test",
                "skip-hmac",
                0,
                null);
        var entries = new java.util.ArrayList<>(memoryStore.entries());
        entries.add(oldEntry);
        memoryStore.replaceEntries(entries, workspacePath);

        // Consolidate with null archiveDir
        var result = MemoryConsolidator.consolidate(memoryStore, workspacePath, null);

        // Entry should NOT be pruned (no archiveDir = no safe archive destination)
        assertThat(result.pruned()).isEqualTo(0);
        assertThat(memoryStore.size()).isEqualTo(1);
    }

    // =========================================================================
    // P3: Truncation warning in system prompt
    // =========================================================================

    @Test
    void systemPromptContainsTruncationWarningWhenBudgetExceeded() throws IOException {
        // Create a large ACECLAW.md that exceeds per-tier cap
        String largeContent = "Important instruction.\n".repeat(2000); // ~44K chars
        Files.writeString(workspacePath.resolve("ACECLAW.md"), largeContent);

        var budget = new SystemPromptBudget(5_000, 150_000);
        String prompt = SystemPromptLoader.load(
                workspacePath, memoryStore, journal, markdownStore,
                "test-model", "anthropic", budget);

        // The workspace memory tier should be truncated
        assertThat(prompt).contains("[TRUNCATED]");
        // Budget warning should be appended
        assertThat(prompt).contains("Budget warning");
        assertThat(prompt).contains("Workspace Memory");
    }

    // =========================================================================
    // Full lifecycle: start → use → end → restart
    // =========================================================================

    @Test
    void fullLifecycleAcrossSessions() throws Exception {
        // === Session 1: save a memory and simulate conversation ===
        var tool = new MemoryTool(memoryStore, workspacePath);
        tool.execute("{\"action\":\"save\",\"content\":\"Project uses Java 21 with preview features\"," +
                "\"category\":\"codebase_insight\",\"tags\":\"java,setup\"}");

        // Simulate conversation with a correction
        var session1Messages = List.<AgentSession.ConversationMessage>of(
                new AgentSession.ConversationMessage.User("Create a HashMap"),
                new AgentSession.ConversationMessage.Assistant("Created HashMap<String, String>"),
                new AgentSession.ConversationMessage.User("No, use ConcurrentHashMap for thread safety")
        );

        // Session end extraction
        var extracted = SessionEndExtractor.extract(session1Messages);
        for (var mem : extracted) {
            memoryStore.add(mem.category(), mem.content(), mem.tags(),
                    "session-end:session-1", false, workspacePath);
        }

        // Consolidation
        MemoryConsolidator.consolidate(memoryStore, workspacePath, markdownStore.memoryDir());
        journal.append("Session 1 ended: " + extracted.size() + " memories");

        // === Session 2: verify memories survive restart ===
        // Simulate "restart" by reloading the store from disk
        var freshStore = AutoMemoryStore.forWorkspace(aceclawHome, workspacePath);

        // Verify previously saved memories are present
        var searchResults = freshStore.search("Java 21 preview", null, 10);
        assertThat(searchResults).isNotEmpty();
        assertThat(searchResults.getFirst().content()).contains("Java 21");

        // Verify system prompt includes memories from session 1
        String prompt = SystemPromptLoader.load(
                workspacePath, freshStore, journal, markdownStore, "test-model", "anthropic");
        assertThat(prompt).contains("Java 21");
        assertThat(prompt).contains("Daily Journal");
        assertThat(prompt).contains("Session 1 ended");
    }
}

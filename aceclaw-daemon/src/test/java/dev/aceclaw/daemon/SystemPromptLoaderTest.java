package dev.aceclaw.daemon;

import dev.aceclaw.memory.AutoMemoryStore;
import dev.aceclaw.memory.DailyJournal;
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
 * Tests for {@link SystemPromptLoader} with 6-tier memory hierarchy integration.
 */
class SystemPromptLoaderTest {

    @TempDir
    Path tempDir;

    private Path workDir;

    @BeforeEach
    void setUp() throws IOException {
        workDir = tempDir.resolve("workspace");
        Files.createDirectories(workDir);
    }

    @Test
    void basicLoadContainsEnvironmentContext() {
        String prompt = SystemPromptLoader.load(workDir);

        assertThat(prompt).contains("# Environment");
        assertThat(prompt).contains("Working directory:");
        assertThat(prompt).contains("Platform:");
    }

    @Test
    void soulMdAppearsInSystemPrompt() throws IOException {
        // Create global SOUL.md in a mock ~/.aceclaw dir
        // Note: SystemPromptLoader uses the real ~/.aceclaw, so we test with
        // workspace-scoped SOUL.md instead (which takes precedence)
        Path wsAceclaw = workDir.resolve(".aceclaw");
        Files.createDirectories(wsAceclaw);
        Files.writeString(wsAceclaw.resolve("SOUL.md"),
                "You are AceClaw, an enterprise AI agent with supreme coding abilities.");

        String prompt = SystemPromptLoader.load(workDir);

        assertThat(prompt).contains("Soul (Core Identity)");
        assertThat(prompt).contains("enterprise AI agent with supreme coding abilities");
    }

    @Test
    void workspaceAceclawMdAppearsInPrompt() throws IOException {
        Files.writeString(workDir.resolve("ACECLAW.md"),
                "Always use Java 21 features.");

        String prompt = SystemPromptLoader.load(workDir);

        assertThat(prompt).contains("Project Instructions");
        assertThat(prompt).contains("Always use Java 21 features");
    }

    @Test
    void autoMemoryAppearsInPrompt() throws IOException {
        Path aceclawHome = tempDir.resolve(".aceclaw-home");
        Files.createDirectories(aceclawHome);

        var store = new AutoMemoryStore(aceclawHome);
        store.load(workDir);
        store.add(MemoryEntry.Category.MISTAKE, "Never use System.exit in library code",
                List.of("java"), "test", false, workDir);

        String prompt = SystemPromptLoader.load(workDir, store);

        assertThat(prompt).contains("Auto-Memory");
        assertThat(prompt).contains("Never use System.exit");
    }

    @Test
    void dailyJournalAppearsInPrompt() throws IOException {
        Path aceclawHome = tempDir.resolve(".aceclaw-home");
        Path memDir = aceclawHome.resolve("memory");
        Files.createDirectories(memDir);

        var journal = new DailyJournal(memDir);
        journal.append("Implemented feature X");

        String prompt = SystemPromptLoader.load(workDir, null, journal, null, null);

        assertThat(prompt).contains("Daily Journal");
        assertThat(prompt).contains("Implemented feature X");
    }

    @Test
    void emptyStoreShowsPlaceholderInPrompt() throws IOException {
        Path aceclawHome = tempDir.resolve(".aceclaw-home");
        Files.createDirectories(aceclawHome);

        var store = new AutoMemoryStore(aceclawHome);
        store.load(workDir);
        // Store exists but is empty

        String prompt = SystemPromptLoader.load(workDir, store);

        assertThat(prompt).contains("Auto-Memory");
        assertThat(prompt).contains("No memories stored yet");
    }

    @Test
    void systemPromptDescribesMemorySystem() {
        String prompt = SystemPromptLoader.load(workDir);

        assertThat(prompt).contains("Persistent Memory");
        assertThat(prompt).contains("persistent auto-memory system");
    }

    @Test
    void systemPromptContainsEnhancedSections() {
        String prompt = SystemPromptLoader.load(workDir);

        // Multi-step problem solving section
        assertThat(prompt).contains("Multi-Step Problem Solving");
        assertThat(prompt).contains("Understand");
        assertThat(prompt).contains("Verify");

        // Codebase understanding section
        assertThat(prompt).contains("Understanding the Codebase");
        assertThat(prompt).contains("existing patterns");

        // Autonomous behavior
        assertThat(prompt).contains("Be Autonomous");
        assertThat(prompt).contains("NEVER Give Up");
    }

    @Test
    void crossSessionMemoryViaJournal() throws IOException {
        // Simulate Session A: journal entries are written during a turn
        Path aceclawHome = tempDir.resolve(".aceclaw-home");
        Path memDir = aceclawHome.resolve("memory");
        Files.createDirectories(memDir);

        var journal = new DailyJournal(memDir);
        journal.append("User: Check the weather in Berlin -> Agent: Berlin is 5C and cloudy | Tools: web_search | Tokens: 1234");
        journal.append("User: Fix the login bug -> Agent: Fixed null check in AuthService.java | Tools: read_file, edit_file | Tokens: 5678");

        // Simulate Session B: new session loads journal into system prompt
        String prompt = SystemPromptLoader.load(workDir, null, journal, "gpt-4o", "openai");

        assertThat(prompt).contains("Daily Journal");
        assertThat(prompt).contains("weather in Berlin");
        assertThat(prompt).contains("Fix the login bug");
        assertThat(prompt).contains("AuthService.java");
    }

    @Test
    void crossProviderMemoryPersistence() throws IOException {
        // Journal is workspace-scoped, not provider-scoped
        // Session with anthropic writes to journal
        Path aceclawHome = tempDir.resolve(".aceclaw-home");
        Path memDir = aceclawHome.resolve("memory");
        Files.createDirectories(memDir);

        var journal = new DailyJournal(memDir);
        journal.append("User: Refactored auth module -> Agent: Split into 3 files | Tools: edit_file | Tokens: 3000");

        // Session with openai should see the same journal
        String promptAnthropic = SystemPromptLoader.load(workDir, null, journal, "claude-3-opus", "anthropic");
        String promptOpenai = SystemPromptLoader.load(workDir, null, journal, "gpt-4o", "openai");

        // Both providers see the same journal content
        assertThat(promptAnthropic).contains("Refactored auth module");
        assertThat(promptOpenai).contains("Refactored auth module");
    }

    @Test
    void modelAndProviderAppearInPrompt() {
        String prompt = SystemPromptLoader.load(workDir, null, "claude-3-opus", "anthropic");

        assertThat(prompt).contains("claude-3-opus");
        assertThat(prompt).contains("anthropic");
    }

    @Test
    void allTiersAssembled() throws IOException {
        // Soul
        Path wsAceclaw = workDir.resolve(".aceclaw");
        Files.createDirectories(wsAceclaw);
        Files.writeString(wsAceclaw.resolve("SOUL.md"), "I am AceClaw.");

        // Workspace instructions
        Files.writeString(workDir.resolve("ACECLAW.md"), "Follow Java conventions.");

        // Auto-memory
        Path aceclawHome = tempDir.resolve(".aceclaw-home");
        Files.createDirectories(aceclawHome);
        var store = new AutoMemoryStore(aceclawHome);
        store.load(workDir);
        store.add(MemoryEntry.Category.PATTERN, "Use records for DTOs",
                List.of("java"), "test", false, workDir);

        // Journal
        var journal = new DailyJournal(aceclawHome.resolve("memory"));
        journal.append("Session started");

        String prompt = SystemPromptLoader.load(workDir, store, journal, "test-model", "test-provider");

        assertThat(prompt).contains("Soul (Core Identity)");
        assertThat(prompt).contains("I am AceClaw.");
        assertThat(prompt).contains("Project Instructions");
        assertThat(prompt).contains("Follow Java conventions.");
        assertThat(prompt).contains("Auto-Memory");
        assertThat(prompt).contains("Use records for DTOs");
        assertThat(prompt).contains("Daily Journal");
        assertThat(prompt).contains("Session started");
        assertThat(prompt).contains("test-model");
        assertThat(prompt).contains("test-provider");
    }
}

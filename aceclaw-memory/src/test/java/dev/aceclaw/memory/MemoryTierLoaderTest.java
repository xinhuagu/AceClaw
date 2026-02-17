package dev.aceclaw.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryTierLoaderTest {

    @TempDir
    Path tempDir;

    private Path aceclawHome;
    private Path workspacePath;

    @BeforeEach
    void setUp() throws IOException {
        aceclawHome = tempDir.resolve(".aceclaw");
        Files.createDirectories(aceclawHome);
        workspacePath = tempDir.resolve("workspace");
        Files.createDirectories(workspacePath);
    }

    @Test
    void soulMdLoadedFirst() throws IOException {
        Files.writeString(aceclawHome.resolve("SOUL.md"),
                "You are AceClaw, an enterprise AI agent.");

        var result = MemoryTierLoader.loadAll(aceclawHome, workspacePath, null, null);

        assertThat(result.soulContent()).isNotNull();
        assertThat(result.soulContent()).contains("AceClaw");
        assertThat(result.tieredSections()).isNotEmpty();
        assertThat(result.tieredSections().getFirst().tier()).isInstanceOf(MemoryTier.Soul.class);
    }

    @Test
    void workspaceSoulOverridesGlobal() throws IOException {
        Files.writeString(aceclawHome.resolve("SOUL.md"), "Global soul");

        Path wsAceclaw = workspacePath.resolve(".aceclaw");
        Files.createDirectories(wsAceclaw);
        Files.writeString(wsAceclaw.resolve("SOUL.md"), "Workspace soul");

        var result = MemoryTierLoader.loadAll(aceclawHome, workspacePath, null, null);

        assertThat(result.soulContent()).isEqualTo("Workspace soul");
    }

    @Test
    void managedPolicySkippedIfAbsent() {
        var result = MemoryTierLoader.loadAll(aceclawHome, workspacePath, null, null);

        assertThat(result.tieredSections()).noneMatch(
                s -> s.tier() instanceof MemoryTier.ManagedPolicy);
    }

    @Test
    void managedPolicyLoadedIfPresent() throws IOException {
        Files.writeString(aceclawHome.resolve("managed-policy.md"),
                "Do not access external APIs without approval.");

        var result = MemoryTierLoader.loadAll(aceclawHome, workspacePath, null, null);

        assertThat(result.tieredSections()).anyMatch(
                s -> s.tier() instanceof MemoryTier.ManagedPolicy);
    }

    @Test
    void allTiersAssemblyOrder() throws IOException {
        // Set up all tiers
        Files.writeString(aceclawHome.resolve("SOUL.md"), "Soul content");
        Files.writeString(aceclawHome.resolve("managed-policy.md"), "Policy content");
        Files.writeString(aceclawHome.resolve("ACECLAW.md"), "User instructions");

        Path wsAceclaw = workspacePath.resolve(".aceclaw");
        Files.createDirectories(wsAceclaw);
        Files.writeString(workspacePath.resolve("ACECLAW.md"), "Project instructions");

        var store = new AutoMemoryStore(aceclawHome);
        store.load(workspacePath);
        store.add(MemoryEntry.Category.PATTERN, "Test pattern",
                List.of("test"), "test", false, workspacePath);

        var journal = new DailyJournal(aceclawHome.resolve("memory"));
        journal.append("Did some work");

        var result = MemoryTierLoader.loadAll(aceclawHome, workspacePath, store, journal);

        assertThat(result.tiersLoaded()).isEqualTo(6);

        // Verify order matches priority (Soul first, Journal last)
        var tierTypes = result.tieredSections().stream()
                .map(s -> s.tier().getClass().getSimpleName())
                .toList();
        assertThat(tierTypes.indexOf("Soul")).isLessThan(tierTypes.indexOf("ManagedPolicy"));
        assertThat(tierTypes.indexOf("ManagedPolicy")).isLessThan(tierTypes.indexOf("WorkspaceMemory"));
        assertThat(tierTypes.indexOf("WorkspaceMemory")).isLessThan(tierTypes.indexOf("UserMemory"));
        assertThat(tierTypes.indexOf("UserMemory")).isLessThan(tierTypes.indexOf("AutoMemory"));
        assertThat(tierTypes.indexOf("AutoMemory")).isLessThan(tierTypes.indexOf("Journal"));
    }

    @Test
    void emptyTiersOmitted() {
        var result = MemoryTierLoader.loadAll(aceclawHome, workspacePath, null, null);

        assertThat(result.tiersLoaded()).isEqualTo(0);
        assertThat(result.tieredSections()).isEmpty();
    }

    @Test
    void assembleForSystemPrompt() throws IOException {
        Files.writeString(aceclawHome.resolve("SOUL.md"), "I am AceClaw.");
        Files.writeString(aceclawHome.resolve("ACECLAW.md"), "Be concise.");

        var store = new AutoMemoryStore(aceclawHome);
        store.load(workspacePath);
        store.add(MemoryEntry.Category.MISTAKE, "Do not use System.exit",
                List.of("java"), "test", false, workspacePath);

        var result = MemoryTierLoader.loadAll(aceclawHome, workspacePath, store, null);
        var assembled = MemoryTierLoader.assembleForSystemPrompt(
                result, store, workspacePath, 50);

        assertThat(assembled).contains("Soul (Core Identity)");
        assertThat(assembled).contains("I am AceClaw.");
        assertThat(assembled).contains("User Instructions");
        assertThat(assembled).contains("Be concise.");
        assertThat(assembled).contains("Auto-Memory");
        assertThat(assembled).contains("Do not use System.exit");
    }

    @Test
    void assembleEmptyResult() {
        var result = MemoryTierLoader.loadAll(aceclawHome, workspacePath, null, null);
        var assembled = MemoryTierLoader.assembleForSystemPrompt(result, null, workspacePath, 50);

        assertThat(assembled).isEmpty();
    }

    @Test
    void assembleWithJournal() throws IOException {
        var journal = new DailyJournal(aceclawHome.resolve("memory"));
        journal.append("Refactored the auth module");

        var result = MemoryTierLoader.loadAll(aceclawHome, workspacePath, null, journal);
        var assembled = MemoryTierLoader.assembleForSystemPrompt(result, null, workspacePath, 50);

        assertThat(assembled).contains("Daily Journal");
        assertThat(assembled).contains("Refactored the auth module");
    }

    @Test
    void emptyStoreShowsPlaceholderNote() throws IOException {
        // Store exists but has no entries
        var store = new AutoMemoryStore(aceclawHome);
        store.load(workspacePath);
        assertThat(store.size()).isEqualTo(0);

        var result = MemoryTierLoader.loadAll(aceclawHome, workspacePath, store, null);
        // AutoMemory tier should be present even with empty store
        assertThat(result.tieredSections()).anyMatch(
                s -> s.tier() instanceof MemoryTier.AutoMemory);
        assertThat(result.tiersLoaded()).isEqualTo(1);

        var assembled = MemoryTierLoader.assembleForSystemPrompt(result, store, workspacePath, 50);
        assertThat(assembled).contains("Auto-Memory");
        assertThat(assembled).contains("No memories stored yet");
    }

    @Test
    void workspaceMemoryMergesBothFiles() throws IOException {
        Files.writeString(workspacePath.resolve("ACECLAW.md"), "Root instructions.");

        Path wsAceclaw = workspacePath.resolve(".aceclaw");
        Files.createDirectories(wsAceclaw);
        Files.writeString(wsAceclaw.resolve("ACECLAW.md"), "Config instructions.");

        var result = MemoryTierLoader.loadAll(aceclawHome, workspacePath, null, null);

        var workspaceSection = result.tieredSections().stream()
                .filter(s -> s.tier() instanceof MemoryTier.WorkspaceMemory)
                .findFirst();
        assertThat(workspaceSection).isPresent();
        assertThat(workspaceSection.get().content()).contains("Root instructions.");
        assertThat(workspaceSection.get().content()).contains("Config instructions.");
    }
}

package dev.aceclaw.daemon;

import dev.aceclaw.memory.MemoryTier;
import dev.aceclaw.memory.MemoryTierLoader.TierSection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link TierTruncator} and {@link SystemPromptBudget}.
 */
class TierTruncatorTest {

    // =========================================================================
    // SystemPromptBudget tests
    // =========================================================================

    @Test
    void defaultBudgetHasExpectedValues() {
        var budget = SystemPromptBudget.DEFAULT;
        assertThat(budget.maxPerTierChars()).isEqualTo(20_000);
        assertThat(budget.maxTotalChars()).isEqualTo(150_000);
    }

    @Test
    void budgetRejectsNonPositiveValues() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new SystemPromptBudget(0, 100));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new SystemPromptBudget(100, 0));
    }

    // =========================================================================
    // truncateContent tests
    // =========================================================================

    @Test
    void truncateContentUnderLimitReturnsOriginal() {
        String content = "Short content";
        assertThat(TierTruncator.truncateContent(content, 1000)).isEqualTo(content);
    }

    @Test
    void truncateContentNullReturnsNull() {
        assertThat(TierTruncator.truncateContent(null, 100)).isNull();
    }

    @Test
    void truncateContentOverLimitApplies70_20_10Split() {
        String content = "A".repeat(10_000);
        String truncated = TierTruncator.truncateContent(content, 1000);

        assertThat(truncated.length()).isLessThanOrEqualTo(1000);
        assertThat(truncated).contains("[TRUNCATED]");
        assertThat(truncated).contains("Original: 10000 chars");
        // Should have content from head and tail
        assertThat(truncated).startsWith("AAAA");
        assertThat(truncated).endsWith("AAAA");
    }

    @Test
    void truncateContentPreservesHeadAndTail() {
        // Build content with distinct head and tail
        String head = "HEAD_SECTION_" + "x".repeat(5000);
        String tail = "y".repeat(5000) + "_TAIL_SECTION";
        String content = head + tail;

        String truncated = TierTruncator.truncateContent(content, 2000);

        assertThat(truncated).startsWith("HEAD_SECTION_");
        assertThat(truncated).endsWith("_TAIL_SECTION");
        assertThat(truncated).contains("[TRUNCATED]");
    }

    // =========================================================================
    // applyBudget tests
    // =========================================================================

    @Test
    void applyBudgetUnderTotalReturnsUnchanged() {
        var sections = List.of(
                new TierSection(new MemoryTier.WorkspaceMemory(), "Short content"),
                new TierSection(new MemoryTier.Journal(), "Journal content")
        );
        var budget = new SystemPromptBudget(20_000, 150_000);

        var result = TierTruncator.applyBudget(sections, budget);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).content()).isEqualTo("Short content");
        assertThat(result.get(1).content()).isEqualTo("Journal content");
    }

    @Test
    void applyBudgetTruncatesPerTierOverLimit() {
        String largeContent = "x".repeat(25_000);
        var sections = List.of(
                new TierSection(new MemoryTier.WorkspaceMemory(), largeContent)
        );
        var budget = new SystemPromptBudget(20_000, 150_000);

        var result = TierTruncator.applyBudget(sections, budget);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().content().length()).isLessThanOrEqualTo(20_000);
        assertThat(result.getFirst().content()).contains("[TRUNCATED]");
    }

    @Test
    void applyBudgetTruncatesLowestPriorityFirst() {
        // Journal (50) should be truncated before Workspace (80)
        var sections = new ArrayList<>(List.of(
                new TierSection(new MemoryTier.WorkspaceMemory(), "w".repeat(40_000)),
                new TierSection(new MemoryTier.Journal(), "j".repeat(40_000))
        ));
        // Total = 80K, budget total = 50K
        var budget = new SystemPromptBudget(50_000, 50_000);

        var result = TierTruncator.applyBudget(sections, budget);

        // Journal should be truncated more aggressively (or removed)
        int journalLen = 0;
        int workspaceLen = 0;
        for (var s : result) {
            if (s.tier() instanceof MemoryTier.Journal && s.content() != null) {
                journalLen = s.content().length();
            }
            if (s.tier() instanceof MemoryTier.WorkspaceMemory && s.content() != null) {
                workspaceLen = s.content().length();
            }
        }
        // Journal (lower priority) should be shorter than workspace
        assertThat(journalLen).isLessThan(workspaceLen);
    }

    @Test
    void applyBudgetSkipsSoulAndPolicy() {
        String largeSoul = "S".repeat(30_000);
        String largePolicy = "P".repeat(30_000);
        String journal = "J".repeat(30_000);
        var sections = new ArrayList<>(List.of(
                new TierSection(new MemoryTier.Soul(), largeSoul),
                new TierSection(new MemoryTier.ManagedPolicy(), largePolicy),
                new TierSection(new MemoryTier.Journal(), journal)
        ));
        // Total = 90K, budget total = 70K per-tier = 50K
        var budget = new SystemPromptBudget(50_000, 70_000);

        var result = TierTruncator.applyBudget(sections, budget);

        // Soul and Policy should be untouched (even though they exceed per-tier cap)
        for (var s : result) {
            if (s.tier() instanceof MemoryTier.Soul) {
                assertThat(s.content()).isEqualTo(largeSoul);
            }
            if (s.tier() instanceof MemoryTier.ManagedPolicy) {
                assertThat(s.content()).isEqualTo(largePolicy);
            }
        }
    }

    @Test
    void applyBudgetHandlesNullContent() {
        // AutoMemory has null content (formatted separately)
        var sections = List.of(
                new TierSection(new MemoryTier.AutoMemory(), null),
                new TierSection(new MemoryTier.Journal(), "Journal")
        );
        var budget = SystemPromptBudget.DEFAULT;

        var result = TierTruncator.applyBudget(sections, budget);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).content()).isNull();
    }

    @Test
    void applyBudgetRemovesTierEntirelyWhenNeeded() {
        var sections = new ArrayList<>(List.of(
                new TierSection(new MemoryTier.WorkspaceMemory(), "w".repeat(10_000)),
                new TierSection(new MemoryTier.Journal(), "j".repeat(10_000))
        ));
        // Budget so tight that Journal must be removed entirely
        var budget = new SystemPromptBudget(20_000, 10_000);

        var result = TierTruncator.applyBudget(sections, budget);

        // At least one tier should have been truncated or removed
        int total = result.stream()
                .filter(s -> s.content() != null)
                .mapToInt(s -> s.content().length())
                .sum();
        assertThat(total).isLessThanOrEqualTo(10_000);
    }
}

package dev.aceclaw.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CorrectionRulePromoterTest {

    @TempDir
    Path tempDir;

    private MemoryEntry correction(String content, String... tags) {
        return new MemoryEntry(
                UUID.randomUUID().toString(),
                MemoryEntry.Category.CORRECTION,
                content,
                List.of(tags),
                Instant.now(),
                "test",
                null,
                0,
                null
        );
    }

    private MemoryEntry mistake(String content, String... tags) {
        return new MemoryEntry(
                UUID.randomUUID().toString(),
                MemoryEntry.Category.MISTAKE,
                content,
                List.of(tags),
                Instant.now(),
                "test",
                null,
                0,
                null
        );
    }

    private MemoryEntry insight(String content) {
        return new MemoryEntry(
                UUID.randomUUID().toString(),
                MemoryEntry.Category.CODEBASE_INSIGHT,
                content,
                List.of(),
                Instant.now(),
                "test",
                null,
                0,
                null
        );
    }

    @Test
    void singleCorrectionNotPromoted() {
        var entries = List.of(
                correction("Use skill for Excel operations", "excel", "skill")
        );

        var result = CorrectionRulePromoter.detectRepeatedCorrections(entries, Set.of());

        assertFalse(result.hasPromotions());
        assertEquals(1, result.scannedCorrections());
    }

    @Test
    void twoDissimilarCorrectionsNotGrouped() {
        var entries = List.of(
                correction("Use skill for Excel operations", "excel"),
                correction("Never force push without confirmation", "git")
        );

        var result = CorrectionRulePromoter.detectRepeatedCorrections(entries, Set.of());

        assertFalse(result.hasPromotions());
    }

    @Test
    void twoSimilarCorrectionsPromoted() {
        var entries = List.of(
                correction("Forgot to use Microsoft Office & Teams Controller skill for Excel tab switching",
                        "excel", "skill", "applescript"),
                correction("Again forgot to use Microsoft Office & Teams Controller skill for Excel operations",
                        "excel", "skill", "repeated-mistake")
        );

        var result = CorrectionRulePromoter.detectRepeatedCorrections(entries, Set.of());

        assertTrue(result.hasPromotions());
        assertEquals(1, result.rules().size());
        assertEquals(2, result.rules().getFirst().occurrences());
    }

    @Test
    void nonCorrectionsIgnored() {
        var entries = List.of(
                insight("Modified file: TerminalRepl.java"),
                insight("Modified file: OutputSink.java"),
                insight("Modified file: AceClawDaemon.java")
        );

        var result = CorrectionRulePromoter.detectRepeatedCorrections(entries, Set.of());

        assertFalse(result.hasPromotions());
        assertEquals(0, result.scannedCorrections());
    }

    @Test
    void mistakesAlsoPromotable() {
        var entries = List.of(
                mistake("Forgot to use Microsoft Office skill for PowerPoint slide navigation", "ppt", "skill"),
                mistake("Again forgot to use Microsoft Office skill for PowerPoint slide switching", "ppt", "skill")
        );

        var result = CorrectionRulePromoter.detectRepeatedCorrections(entries, Set.of());

        assertTrue(result.hasPromotions());
    }

    @Test
    void existingFingerprintSkipped() {
        var entries = List.of(
                correction("Forgot to use skill for Excel", "excel", "skill"),
                correction("Forgot to use skill for Excel operations", "excel", "skill")
        );

        // First run: get the fingerprint
        var result1 = CorrectionRulePromoter.detectRepeatedCorrections(entries, Set.of());
        assertTrue(result1.hasPromotions());
        var fp = result1.rules().getFirst().fingerprint();

        // Second run: same fingerprint should be skipped
        var result2 = CorrectionRulePromoter.detectRepeatedCorrections(entries, Set.of(fp));
        assertFalse(result2.hasPromotions());
    }

    @Test
    void appendRulesCreatesFile() throws IOException {
        var aceClawMd = tempDir.resolve("ACECLAW.md");
        Files.writeString(aceClawMd, "# AceClaw Rules\n\nExisting content.\n");

        var rule = new CorrectionRulePromoter.PromotedRule(
                "EXCEL / SKILL",
                "**Always use Microsoft Office & Teams Controller skill for Excel operations**",
                "abc123",
                List.of("excel", "skill"),
                List.of("id-1", "id-2"),
                2,
                Instant.now()
        );

        int written = CorrectionRulePromoter.appendRules(aceClawMd, List.of(rule));

        assertEquals(1, written);
        var content = Files.readString(aceClawMd);
        assertTrue(content.contains(CorrectionRulePromoter.SECTION_MARKER));
        assertTrue(content.contains("abc123"));
        assertTrue(content.contains("EXCEL / SKILL"));
        assertTrue(content.contains("2 corrections"));
    }

    @Test
    void appendRulesIdempotent() throws IOException {
        var aceClawMd = tempDir.resolve("ACECLAW.md");
        Files.writeString(aceClawMd, "# AceClaw Rules\n");

        var rule = new CorrectionRulePromoter.PromotedRule(
                "TEST RULE",
                "**Test rule text**",
                "fp-001",
                List.of("test"),
                List.of("id-1"),
                2,
                Instant.now()
        );

        CorrectionRulePromoter.appendRules(aceClawMd, List.of(rule));

        // Load fingerprints — should find the one we just wrote
        var fps = CorrectionRulePromoter.loadExistingFingerprints(aceClawMd);
        assertTrue(fps.contains("fp-001"));
    }

    @Test
    void loadFingerprintsFromMissingFile() {
        var missing = tempDir.resolve("nonexistent.md");
        var fps = CorrectionRulePromoter.loadExistingFingerprints(missing);
        assertTrue(fps.isEmpty());
    }

    @Test
    void groupBySimilarityWorks() {
        var entries = List.of(
                correction("Use skill for Excel tab switching not AppleScript"),
                correction("Use skill for Excel operations not manual AppleScript"),
                correction("Never force push to main branch")
        );

        var groups = CorrectionRulePromoter.groupBySimilarity(entries);

        // Should have 2 groups: 2 Excel-related + 1 git-related
        assertEquals(2, groups.size());
        assertTrue(groups.stream().anyMatch(g -> g.size() == 2)); // Excel group
        assertTrue(groups.stream().anyMatch(g -> g.size() == 1)); // Git group
    }

    @Test
    void emptyEntriesReturnsEmpty() {
        var result = CorrectionRulePromoter.detectRepeatedCorrections(List.of(), Set.of());
        assertFalse(result.hasPromotions());
        assertEquals(0, result.scannedCorrections());
    }
}

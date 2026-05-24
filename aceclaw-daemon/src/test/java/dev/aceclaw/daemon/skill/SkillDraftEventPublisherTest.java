package dev.aceclaw.daemon.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the pure-function helpers extracted from AceClawDaemon
 * alongside SkillDraftEventPublisher (batch A of the daemon decomp). The
 * three publish methods themselves go through the daemon's end-to-end
 * integration tests; these unit tests pin the parsing + path conventions
 * that the publisher relies on.
 */
class SkillDraftEventPublisherTest {

    // -- skillNameFromDraftPath: convention is <skillName>/draft.md ----------

    @Test
    void skillNameFromDraftPath_extractsParentDirectoryName() {
        assertThat(SkillDraftEventPublisher.skillNameFromDraftPath("my-skill/draft.md"))
                .isEqualTo("my-skill");
    }

    @Test
    void skillNameFromDraftPath_normalisesBackslashesToForward() {
        // Windows paths must work — the daemon stores draft paths with
        // either separator depending on which subsystem produced them.
        assertThat(SkillDraftEventPublisher.skillNameFromDraftPath("foo\\bar.md"))
                .isEqualTo("foo");
    }

    @Test
    void skillNameFromDraftPath_emptyForRootLevelFile() {
        assertThat(SkillDraftEventPublisher.skillNameFromDraftPath("just-a-file.md"))
                .isEqualTo("");
    }

    @Test
    void skillNameFromDraftPath_handlesNestedPaths() {
        // The convention is "parent of the file" — works even when nested.
        assertThat(SkillDraftEventPublisher.skillNameFromDraftPath(".aceclaw/skills/refactor/draft.md"))
                .isEqualTo("refactor");
    }

    // -- stripQuotes: drop one matching pair of surrounding quotes -----------

    @Test
    void stripQuotes_removesMatchingDoubleQuotes() {
        assertThat(SkillDraftEventPublisher.stripQuotes("\"hello\"")).isEqualTo("hello");
    }

    @Test
    void stripQuotes_removesMatchingSingleQuotes() {
        assertThat(SkillDraftEventPublisher.stripQuotes("'hello'")).isEqualTo("hello");
    }

    @Test
    void stripQuotes_leavesMismatchedQuotesAlone() {
        // Pre-decomp behaviour preserved: only strip when start and end
        // match the SAME quote style.
        assertThat(SkillDraftEventPublisher.stripQuotes("\"hello'")).isEqualTo("\"hello'");
    }

    @Test
    void stripQuotes_leavesUnquotedAlone() {
        assertThat(SkillDraftEventPublisher.stripQuotes("hello")).isEqualTo("hello");
    }

    @Test
    void stripQuotes_handlesNullAndShort() {
        assertThat(SkillDraftEventPublisher.stripQuotes(null)).isEqualTo("");
        assertThat(SkillDraftEventPublisher.stripQuotes("")).isEqualTo("");
        assertThat(SkillDraftEventPublisher.stripQuotes("\"")).isEqualTo("\"");
    }

    // -- parseDraftFrontmatter: simple YAML-like header parsing --------------

    @Test
    void parseDraftFrontmatter_extractsKeyValuePairsBetweenMarkers(@TempDir Path tempDir) throws Exception {
        Path draft = tempDir.resolve("draft.md");
        Files.writeString(draft, """
                ---
                source-candidate-id: cand_123
                name: my-skill
                description: "a useful skill"
                ---
                # Body content here
                """);

        var fm = SkillDraftEventPublisher.parseDraftFrontmatter(draft);

        assertThat(fm)
                .containsEntry("source-candidate-id", "cand_123")
                .containsEntry("name", "my-skill")
                .containsEntry("description", "a useful skill");
    }

    @Test
    void parseDraftFrontmatter_lowercasesKeys(@TempDir Path tempDir) throws Exception {
        Path draft = tempDir.resolve("draft.md");
        Files.writeString(draft, """
                ---
                Source-Candidate-ID: cand_42
                ---
                """);

        var fm = SkillDraftEventPublisher.parseDraftFrontmatter(draft);

        assertThat(fm).containsEntry("source-candidate-id", "cand_42");
    }

    @Test
    void parseDraftFrontmatter_emptyWhenNoFrontmatter(@TempDir Path tempDir) throws Exception {
        Path draft = tempDir.resolve("draft.md");
        Files.writeString(draft, "# Just a heading, no frontmatter");

        var fm = SkillDraftEventPublisher.parseDraftFrontmatter(draft);

        assertThat(fm).isEmpty();
    }

    @Test
    void parseDraftFrontmatter_emptyWhenOnlyOpenMarker(@TempDir Path tempDir) throws Exception {
        Path draft = tempDir.resolve("draft.md");
        Files.writeString(draft, """
                ---
                name: orphaned
                # Never closed
                """);

        var fm = SkillDraftEventPublisher.parseDraftFrontmatter(draft);

        assertThat(fm)
                .as("unclosed frontmatter must yield empty map (silent failure, not partial)")
                .isEmpty();
    }

    @Test
    void parseDraftFrontmatter_skipsLinesWithoutColon(@TempDir Path tempDir) throws Exception {
        Path draft = tempDir.resolve("draft.md");
        Files.writeString(draft, """
                ---
                valid: x
                not-a-keyvalue-line
                another: y
                ---
                """);

        var fm = SkillDraftEventPublisher.parseDraftFrontmatter(draft);

        assertThat(fm)
                .containsEntry("valid", "x")
                .containsEntry("another", "y")
                .doesNotContainKey("not-a-keyvalue-line");
    }
}

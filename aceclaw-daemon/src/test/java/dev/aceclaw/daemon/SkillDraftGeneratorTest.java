package dev.aceclaw.daemon;

import dev.aceclaw.memory.CandidateState;
import dev.aceclaw.memory.CandidateStateMachine;
import dev.aceclaw.memory.CandidateStore;
import dev.aceclaw.memory.MemoryEntry;
import dev.aceclaw.memory.CandidateKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SkillDraftGeneratorTest {

    @TempDir
    Path tempDir;

    @Test
    void generatedDraftContainsRequiredFrontmatter() throws Exception {
        var store = newStore(tempDir.resolve("project-a"));
        var t0 = Instant.parse("2026-02-24T00:00:00Z");

        store.upsert(obs("Use retry with bounded timeout for flaky bash commands", "bash", t0));
        store.evaluateAll();
        assertThat(store.byState(CandidateState.PROMOTED)).hasSize(1);

        var generator = new SkillDraftGenerator();
        var summary = generator.generateFromPromoted(store, tempDir.resolve("project-a"));

        assertThat(summary.processedPromotedCandidates()).isEqualTo(1);
        assertThat(summary.createdDrafts()).isEqualTo(1);
        assertThat(summary.updatedDrafts()).isZero();
        assertThat(summary.draftPaths()).hasSize(1);

        var skillFile = tempDir.resolve("project-a").resolve(summary.draftPaths().getFirst());
        assertThat(skillFile).exists();
        String content = Files.readString(skillFile);
        assertThat(content).contains("name:");
        assertThat(content).contains("description:");
        assertThat(content).contains("context:");
        assertThat(content).contains("allowed-tools:");
        assertThat(content).contains("max-turns:");
        assertThat(content).contains("disable-model-invocation: true");
        assertThat(content).contains("source-candidate-id:");

        assertThat(summary.auditFile()).exists();
        String audit = Files.readString(summary.auditFile());
        assertThat(audit).contains("\"action\":\"created\"");
    }

    @Test
    void collisionNamingIsDeterministic() throws Exception {
        var project = tempDir.resolve("project-b");
        var store = newStore(project);
        var t0 = Instant.parse("2026-02-24T00:00:00Z");

        // Same content => same base skill name; different tool tags prevent merge in candidate store.
        store.upsert(obs("Prefer concise command output summaries", "bash", t0));
        store.upsert(obs("Prefer concise command output summaries", "read_file", t0.plusSeconds(30)));
        store.evaluateAll();
        assertThat(store.byState(CandidateState.PROMOTED)).hasSize(2);

        var generator = new SkillDraftGenerator();
        var summary = generator.generateFromPromoted(store, project);

        assertThat(summary.createdDrafts()).isEqualTo(2);
        assertThat(summary.draftPaths()).hasSize(2);
        assertThat(summary.draftPaths().get(0)).isNotEqualTo(summary.draftPaths().get(1));

        var names = summary.draftPaths().stream()
                .map(p -> Path.of(p).getParent().getFileName().toString())
                .toList();
        assertThat(names.get(1)).startsWith(names.get(0) + "-");
    }

    @Test
    void regeneratingExistingDraftIncrementsUpdatedCount() throws Exception {
        var project = tempDir.resolve("project-regen");
        var store = newStore(project);
        var t0 = Instant.parse("2026-02-24T00:00:00Z");

        store.upsert(obs("Prefer concise command output summaries", "bash", t0));
        store.upsert(obs("Prefer concise command output summaries", "read_file", t0.plusSeconds(30)));
        store.evaluateAll();
        assertThat(store.byState(CandidateState.PROMOTED)).hasSize(2);

        var generator = new SkillDraftGenerator();
        var first = generator.generateFromPromoted(store, project);
        var second = generator.generateFromPromoted(store, project);

        assertThat(first.createdDrafts()).isEqualTo(2);
        assertThat(first.updatedDrafts()).isZero();
        assertThat(second.createdDrafts()).isZero();
        assertThat(second.updatedDrafts()).isEqualTo(2);
        assertThat(second.draftPaths()).containsExactlyElementsOf(first.draftPaths());
    }

    @Test
    void onlyPromotedCandidatesGenerateDrafts() throws Exception {
        var project = tempDir.resolve("project-c");
        var store = newStore(project);
        var t0 = Instant.parse("2026-02-24T00:00:00Z");

        // Promoted candidate.
        store.upsert(obs("Use read-first then write pattern for edits", "edit_file", t0));
        store.evaluateAll();

        // Shadow candidate (insufficient score/evidence) should not produce draft.
        store.upsert(obsLow("Experimental rough strategy", "glob", t0.plusSeconds(120)));

        var generator = new SkillDraftGenerator();
        var summary = generator.generateFromPromoted(store, project);

        assertThat(summary.processedPromotedCandidates()).isEqualTo(1);
        assertThat(summary.createdDrafts()).isEqualTo(1);
        assertThat(summary.draftPaths()).singleElement()
                .satisfies(path -> assertThat(path).contains(".aceclaw/skills-drafts"));
    }

    private static CandidateStore newStore(Path projectRoot) throws Exception {
        var config = new CandidateStateMachine.Config(
                1, 0.1, 1.0, 3,
                Duration.ofDays(14), Duration.ofDays(7), 1, 0.8, 2, Duration.ZERO,
                Set.of()
        );
        var store = new CandidateStore(projectRoot, config);
        store.load();
        return store;
    }

    private static CandidateStore.CandidateObservation obs(String content, String toolTag, Instant at) {
        return new CandidateStore.CandidateObservation(
                MemoryEntry.Category.ERROR_RECOVERY,
                CandidateKind.ERROR_RECOVERY,
                content,
                toolTag,
                List.of(toolTag, "draft"),
                0.9,
                1,
                0,
                "src:" + toolTag,
                at
        );
    }

    private static CandidateStore.CandidateObservation obsLow(String content, String toolTag, Instant at) {
        return new CandidateStore.CandidateObservation(
                MemoryEntry.Category.WORKFLOW,
                CandidateKind.WORKFLOW,
                content,
                toolTag,
                List.of(toolTag),
                0.01,
                0,
                1,
                "src:shadow",
                at
        );
    }
}

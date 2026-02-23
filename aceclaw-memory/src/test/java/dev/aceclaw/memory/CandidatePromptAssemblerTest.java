package dev.aceclaw.memory;

import dev.aceclaw.core.agent.ContextEstimator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CandidatePromptAssemblerTest {
    private static final String TOKEN_HEADROOM_FACTOR_PROPERTY =
            "aceclaw.candidate.injection.tokenHeadroomFactor";

    @TempDir
    Path tempDir;

    private CandidateStore store;

    @BeforeEach
    void setUp() throws Exception {
        // Use low promotion gates for testing (evidence >= 1, score >= 0.3)
        var smConfig = new CandidateStateMachine.Config(1, 0.3, 0.9, 10, Set.of());
        store = new CandidateStore(tempDir, Duration.ofDays(30), 0.50, smConfig);
        store.load();
    }

    @Test
    void onlyPromotedCandidatesAppear() throws Exception {
        // Create a shadow candidate
        upsertAndPromote("strategy alpha", "bash", 0.85);
        // Create another shadow candidate (stays shadow)
        store.upsert(obs("shadow strategy", "grep", 0.6));

        var config = CandidatePromptAssembler.Config.defaults();
        var result = CandidatePromptAssembler.assemble(store, config);

        assertThat(result).contains("strategy alpha");
        assertThat(result).doesNotContain("shadow strategy");
    }

    @Test
    void shadowCandidatesExcluded() throws Exception {
        store.upsert(obs("shadow only", "bash", 0.8));

        var config = CandidatePromptAssembler.Config.defaults();
        var result = CandidatePromptAssembler.assemble(store, config);
        assertThat(result).isEmpty();
    }

    @Test
    void demotedAndRejectedExcluded() throws Exception {
        // Create promoted candidate, then demote it
        upsertAndPromote("demoted strategy", "bash", 0.85);
        var promoted = store.byState(CandidateState.PROMOTED);
        assertThat(promoted).hasSize(1);

        // Force failure counts high enough for demotion (netFailures > 10)
        for (int i = 0; i < 12; i++) {
            store.upsert(new CandidateStore.CandidateObservation(
                    MemoryEntry.Category.ERROR_RECOVERY, CandidateKind.ERROR_RECOVERY,
                    "demoted strategy bash timeout", "bash", List.of("bash"),
                    0.85, 0, 1, "src:" + i, null));
        }
        store.evaluateAll();

        var config = CandidatePromptAssembler.Config.defaults();
        var result = CandidatePromptAssembler.assemble(store, config);
        assertThat(result).isEmpty();
    }

    @Test
    void countCapEnforced() throws Exception {
        for (int i = 0; i < 5; i++) {
            upsertAndPromote("strategy " + i, "tool" + i, 0.9 - i * 0.01);
        }

        var config = new CandidatePromptAssembler.Config(true, 3, 10000, Set.of());
        var result = CandidatePromptAssembler.assemble(store, config);

        // Should only have at most 3 strategies
        int count = 0;
        for (var line : result.split("\n")) {
            if (line.startsWith("- ")) count++;
        }
        assertThat(count).isLessThanOrEqualTo(3);
    }

    @Test
    void charBudgetCapEnforced() throws Exception {
        for (int i = 0; i < 10; i++) {
            // Use distinct tool tags to prevent merging
            upsertAndPromote("this is a very long strategy description number " + i +
                    " with extra padding to fill up the character budget quickly", "tool" + i, 0.85);
        }

        var config = new CandidatePromptAssembler.Config(true, 100, 50, Set.of());
        var result = CandidatePromptAssembler.assemble(store, config);

        assertThat(ContextEstimator.estimateTokens(result)).isLessThanOrEqualTo(50);
    }

    @Test
    void tokenHeadroomFactorApplied() throws Exception {
        String original = System.getProperty(TOKEN_HEADROOM_FACTOR_PROPERTY);
        System.setProperty(TOKEN_HEADROOM_FACTOR_PROPERTY, "0.5");
        try {
            for (int i = 0; i < 10; i++) {
                upsertAndPromote("headroom strategy with enough text to consume tokens " + i,
                        "tool" + i, 0.9);
            }

            var config = new CandidatePromptAssembler.Config(true, 100, 80, Set.of());
            var result = CandidatePromptAssembler.assemble(store, config);
            assertThat(ContextEstimator.estimateTokens(result)).isLessThanOrEqualTo(40);
        } finally {
            if (original == null) {
                System.clearProperty(TOKEN_HEADROOM_FACTOR_PROPERTY);
            } else {
                System.setProperty(TOKEN_HEADROOM_FACTOR_PROPERTY, original);
            }
        }
    }

    @Test
    void orderingScoreThenEvidenceThenRecency() throws Exception {
        var t0 = Instant.parse("2026-02-23T00:00:00Z");

        // Low score - use different tool tags to prevent merge
        upsertAndPromoteAt("low score strategy for grep searches", "grep", 0.5, t0);
        // High score
        upsertAndPromoteAt("high score strategy for bash commands", "bash", 0.95, t0.plusSeconds(60));

        var config = CandidatePromptAssembler.Config.defaults();
        var result = CandidatePromptAssembler.assemble(store, config);

        int highIdx = result.indexOf("high score strategy");
        int lowIdx = result.indexOf("low score strategy");
        assertThat(highIdx).isGreaterThanOrEqualTo(0);
        assertThat(lowIdx).isGreaterThanOrEqualTo(0);
        assertThat(highIdx).isLessThan(lowIdx);
    }

    @Test
    void emptyWhenNoPromotedCandidates() {
        var config = CandidatePromptAssembler.Config.defaults();
        var result = CandidatePromptAssembler.assemble(store, config);
        assertThat(result).isEmpty();
    }

    @Test
    void emptyWhenInjectionDisabled() throws Exception {
        upsertAndPromote("strategy alpha", "bash", 0.85);
        var config = CandidatePromptAssembler.Config.disabled();
        var result = CandidatePromptAssembler.assemble(store, config);
        assertThat(result).isEmpty();
    }

    @Test
    void categoryAllowlistFiltering() throws Exception {
        upsertAndPromote("strategy alpha", "bash", 0.85);

        // Use an allowlist that doesn't include ERROR_RECOVERY
        var config = new CandidatePromptAssembler.Config(
                true, 10, 5000, Set.of(MemoryEntry.Category.SUCCESSFUL_STRATEGY));
        var result = CandidatePromptAssembler.assemble(store, config);
        assertThat(result).isEmpty();
    }

    @Test
    void groupedByToolTag() throws Exception {
        upsertAndPromote("bash strategy one", "bash", 0.85);
        upsertAndPromote("grep strategy one", "grep", 0.80);

        var config = CandidatePromptAssembler.Config.defaults();
        var result = CandidatePromptAssembler.assemble(store, config);

        assertThat(result).contains("### bash");
        assertThat(result).contains("### grep");
    }

    @Test
    void metadataIncludesInjectedCandidateIds() throws Exception {
        upsertAndPromote("bash strategy one", "bash", 0.85);
        upsertAndPromote("grep strategy one", "grep", 0.80);

        var assembled = CandidatePromptAssembler.assembleWithMetadata(
                store, CandidatePromptAssembler.Config.defaults());

        assertThat(assembled.section()).contains("Learned Strategies");
        assertThat(assembled.candidateIds()).isNotEmpty();
    }

    private void upsertAndPromote(String content, String toolTag, double score) {
        upsertAndPromoteAt(content, toolTag, score, null);
    }

    private void upsertAndPromoteAt(String content, String toolTag, double score, Instant at) {
        var stored = store.upsert(new CandidateStore.CandidateObservation(
                MemoryEntry.Category.ERROR_RECOVERY, CandidateKind.ERROR_RECOVERY,
                content, toolTag, List.of(toolTag, "test"),
                score, 1, 0, "test-ref", at));
        // Force promotion by directly transitioning (may fail if already promoted)
        if (stored.state() == CandidateState.SHADOW) {
            store.transition(stored.id(), CandidateState.PROMOTED, "test-promotion");
        }
    }

    private static CandidateStore.CandidateObservation obs(String content, String toolTag, double score) {
        return new CandidateStore.CandidateObservation(
                MemoryEntry.Category.ERROR_RECOVERY, CandidateKind.ERROR_RECOVERY,
                content, toolTag, List.of(toolTag, "test"),
                score, 1, 0, "test-ref", null);
    }
}

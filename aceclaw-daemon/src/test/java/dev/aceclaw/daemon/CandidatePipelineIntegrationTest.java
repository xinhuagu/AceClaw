package dev.aceclaw.daemon;

import dev.aceclaw.core.agent.ToolMetrics;
import dev.aceclaw.core.agent.Turn;
import dev.aceclaw.core.agent.ContextEstimator;
import dev.aceclaw.core.llm.*;
import dev.aceclaw.memory.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration tests for the candidate pipeline:
 * observation → merge → state machine (promotion/demotion) → prompt injection.
 */
class CandidatePipelineIntegrationTest {

    @TempDir
    Path tempDir;

    private AutoMemoryStore memoryStore;
    private CandidateStore candidateStore;
    private SelfImprovementEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        memoryStore = new AutoMemoryStore(tempDir);
        memoryStore.load(tempDir);

        // Low promotion gates for easier testing
        var smConfig = new CandidateStateMachine.Config(2, 0.5, 0.5, 2, Set.of());
        candidateStore = new CandidateStore(tempDir, smConfig);
        candidateStore.load();

        var errorDetector = new ErrorDetector(memoryStore);
        var patternDetector = new PatternDetector(memoryStore);
        engine = new SelfImprovementEngine(
                errorDetector, patternDetector, new FailureSignalDetector(),
                memoryStore, null, candidateStore);
    }

    @Test
    void shadowCandidateProgressionToPromoted() {
        var t0 = Instant.parse("2026-02-23T00:00:00Z");

        // Upsert observations that exceed promotion gates (evidence >= 2, score >= 0.5)
        candidateStore.upsert(obs("timeout recovery via retry", "bash", 0.8, t0));
        candidateStore.upsert(obs("bash timeout recovery via retry approach", "bash", 0.8,
                t0.plusSeconds(60)));

        assertThat(candidateStore.byState(CandidateState.SHADOW)).hasSize(1);

        // Evaluate triggers promotion
        var transitions = candidateStore.evaluateAll();
        assertThat(transitions).hasSize(1);
        assertThat(transitions.getFirst().fromState()).isEqualTo(CandidateState.SHADOW);
        assertThat(transitions.getFirst().toState()).isEqualTo(CandidateState.PROMOTED);

        assertThat(candidateStore.byState(CandidateState.PROMOTED)).hasSize(1);
        assertThat(candidateStore.byState(CandidateState.SHADOW)).isEmpty();
    }

    @Test
    void promotedCandidateRegressionToDemoted() {
        var t0 = Instant.parse("2026-02-23T00:00:00Z");

        // Create and promote a candidate
        candidateStore.upsert(obs("timeout recovery", "bash", 0.8, t0));
        candidateStore.upsert(obs("bash timeout recovery approach", "bash", 0.8, t0.plusSeconds(60)));
        candidateStore.evaluateAll(); // promotes
        assertThat(candidateStore.byState(CandidateState.PROMOTED)).hasSize(1);

        // Bump failure count high enough for demotion (netFailures > 2)
        for (int i = 0; i < 5; i++) {
            candidateStore.upsert(new CandidateStore.CandidateObservation(
                    MemoryEntry.Category.ERROR_RECOVERY, CandidateKind.ERROR_RECOVERY,
                    "bash timeout recovery approach fails again", "bash",
                    List.of("bash", "timeout"), 0.8, 0, 1, "fail:" + i,
                    t0.plusSeconds(120 + i)));
        }

        var demotionTransitions = candidateStore.evaluateAll();
        assertThat(demotionTransitions).hasSize(1);
        assertThat(demotionTransitions.getFirst().toState()).isEqualTo(CandidateState.DEMOTED);

        assertThat(candidateStore.byState(CandidateState.DEMOTED)).hasSize(1);
        assertThat(candidateStore.byState(CandidateState.PROMOTED)).isEmpty();
    }

    @Test
    void killSwitchPreventsPromptInjection() {
        var t0 = Instant.parse("2026-02-23T00:00:00Z");

        // Create and promote a candidate
        candidateStore.upsert(obs("strategy for bash", "bash", 0.8, t0));
        candidateStore.upsert(obs("bash strategy for timeout", "bash", 0.8, t0.plusSeconds(60)));
        candidateStore.evaluateAll();
        assertThat(candidateStore.byState(CandidateState.PROMOTED)).hasSize(1);

        // Kill-switch: disabled config produces empty result
        var disabledConfig = CandidatePromptAssembler.Config.disabled();
        var result = CandidatePromptAssembler.assemble(candidateStore, disabledConfig);
        assertThat(result).isEmpty();
    }

    @Test
    void promptBudgetCapRespected() {
        var t0 = Instant.parse("2026-02-23T00:00:00Z");

        // Create many promoted candidates with distinct tool tags to prevent merging
        for (int i = 0; i < 20; i++) {
            candidateStore.upsert(new CandidateStore.CandidateObservation(
                    MemoryEntry.Category.ERROR_RECOVERY, CandidateKind.ERROR_RECOVERY,
                    "unique strategy " + i + " with long description padding for size",
                    "tool" + i, List.of("tool" + i), 0.8, 1, 0, "src:" + i, t0));
            candidateStore.upsert(new CandidateStore.CandidateObservation(
                    MemoryEntry.Category.ERROR_RECOVERY, CandidateKind.ERROR_RECOVERY,
                    "unique strategy " + i + " extended description with different wording",
                    "tool" + i, List.of("tool" + i), 0.8, 1, 0, "src2:" + i,
                    t0.plusSeconds(60)));
        }
        candidateStore.evaluateAll();
        assertThat(candidateStore.byState(CandidateState.PROMOTED).size()).isGreaterThanOrEqualTo(5);

        // Set a tight char budget
        var config = new CandidatePromptAssembler.Config(true, 100, 80, Set.of());
        var result = CandidatePromptAssembler.assemble(candidateStore, config);

        assertThat(ContextEstimator.estimateTokens(result)).isLessThanOrEqualTo(80);
    }

    @Test
    void transitionAuditTrailPersisted() throws IOException {
        var t0 = Instant.parse("2026-02-23T00:00:00Z");

        candidateStore.upsert(obs("timeout recovery", "bash", 0.8, t0));
        candidateStore.upsert(obs("bash timeout recovery approach", "bash", 0.8, t0.plusSeconds(60)));
        candidateStore.evaluateAll();

        Path transitionsFile = tempDir.resolve("memory").resolve("candidate-transitions.jsonl");
        assertThat(transitionsFile).exists();
        var lines = Files.readAllLines(transitionsFile);
        assertThat(lines).isNotEmpty();

        var firstLine = lines.getFirst();
        assertThat(firstLine).contains("SHADOW");
        assertThat(firstLine).contains("PROMOTED");
        assertThat(firstLine).contains("auto-promotion");
    }

    @Test
    void selfImprovementEngineUpsertsToCandidateStore() {
        var insights = List.<Insight>of(
                Insight.ErrorInsight.of("bash", "Permission denied: /tmp/out", "Use writable directory", 0.9),
                new Insight.PatternInsight(
                        PatternType.USER_PREFERENCE,
                        "Use explicit command output summaries",
                        3,
                        0.85,
                        List.of("preference", "format"))
        );

        var persisted = engine.persist(insights, "test-session", tempDir);

        assertThat(persisted).isEqualTo(2);
        assertThat(candidateStore.all()).isNotEmpty();
    }

    @Test
    void promotedCandidatesAppearInPromptAssembly() {
        var t0 = Instant.parse("2026-02-23T00:00:00Z");

        candidateStore.upsert(obs("use retry for transient errors", "bash", 0.85, t0));
        candidateStore.upsert(obs("bash retry for transient timeout errors", "bash", 0.85,
                t0.plusSeconds(60)));
        candidateStore.evaluateAll();

        var config = CandidatePromptAssembler.Config.defaults();
        var result = CandidatePromptAssembler.assemble(candidateStore, config);

        assertThat(result).contains("Learned Strategies");
        assertThat(result).contains("bash");
        assertThat(result).contains("retry");
    }

    @Test
    void autoPromotionTriggersSkillDraftGeneration() throws IOException {
        var t0 = Instant.parse("2026-02-23T00:00:00Z");

        // Track whether the trigger was called and capture the project path
        var triggerCalled = new java.util.concurrent.atomic.AtomicBoolean(false);
        var draftsCreated = new java.util.concurrent.atomic.AtomicInteger(0);

        // Wire engine with candidate store + draft generation trigger
        var errorDetector = new ErrorDetector(memoryStore);
        var patternDetector = new PatternDetector(memoryStore);
        var engineWithTrigger = new SelfImprovementEngine(
                errorDetector, patternDetector, new FailureSignalDetector(),
                memoryStore, null, candidateStore, true,
                projectPath -> {
                    triggerCalled.set(true);
                    // Simulate what the daemon lambda does: generate drafts from promoted candidates
                    var generator = new SkillDraftGenerator();
                    try {
                        var summary = generator.generateFromPromoted(candidateStore, projectPath);
                        draftsCreated.set(summary.createdDrafts());
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });

        // Feed enough observations to trigger promotion (evidence >= 2, score >= 0.5)
        candidateStore.upsert(obs("timeout recovery via retry", "bash", 0.8, t0));
        candidateStore.upsert(obs("bash timeout recovery via retry approach", "bash", 0.8,
                t0.plusSeconds(60)));

        // Persist insights to trigger evaluateAll() + draft generation
        var insights = List.<Insight>of(
                Insight.ErrorInsight.of("bash", "Command timed out", "Retry with shorter timeout", 0.9)
        );
        engineWithTrigger.persist(insights, "test-session", tempDir);

        // Verify: trigger was called because new promotions occurred
        assertThat(triggerCalled.get()).isTrue();

        // Verify: skill drafts were generated for the promoted candidate
        assertThat(draftsCreated.get()).isGreaterThanOrEqualTo(1);

        // Verify: draft file exists on disk
        Path draftsDir = tempDir.resolve(".aceclaw").resolve("skills-drafts");
        assertThat(draftsDir).exists();
        var draftFiles = Files.walk(draftsDir)
                .filter(p -> p.getFileName().toString().equals("SKILL.md"))
                .toList();
        assertThat(draftFiles).isNotEmpty();
    }

    @Test
    void triggerAlwaysFiresEvenWithoutNewPromotions() {
        // Trigger should always fire after persist() for idempotent re-evaluation,
        // regardless of whether new promotions occurred in this turn.
        var triggerCalled = new java.util.concurrent.atomic.AtomicBoolean(false);
        var errorDetector = new ErrorDetector(memoryStore);
        var patternDetector = new PatternDetector(memoryStore);
        var engineWithTrigger = new SelfImprovementEngine(
                errorDetector, patternDetector, new FailureSignalDetector(),
                memoryStore, null, candidateStore, true,
                projectPath -> triggerCalled.set(true));

        // Only one observation — not enough for promotion (needs evidence >= 2)
        var t0 = Instant.parse("2026-02-23T00:00:00Z");
        candidateStore.upsert(obs("single observation", "bash", 0.8, t0));

        var insights = List.<Insight>of(
                Insight.ErrorInsight.of("bash", "Command failed", "Retry", 0.9)
        );
        engineWithTrigger.persist(insights, "test-session", tempDir);

        // Trigger fires on every turn (draft generation is idempotent)
        assertThat(triggerCalled.get()).isTrue();
    }

    private static CandidateStore.CandidateObservation obs(String content, String toolTag,
                                                            double score, Instant at) {
        return new CandidateStore.CandidateObservation(
                MemoryEntry.Category.ERROR_RECOVERY, CandidateKind.ERROR_RECOVERY,
                content, toolTag, List.of(toolTag, "timeout"),
                score, 1, 0, "test-ref", at);
    }

    private static Message assistantWithToolUse(String id, String toolName) {
        return new Message.AssistantMessage(List.of(
                new ContentBlock.ToolUse(id, toolName, "{}")));
    }

    private static Message toolResult(String toolUseId, String output, boolean isError) {
        return Message.toolResult(toolUseId, output, isError);
    }
}

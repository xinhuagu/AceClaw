package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.aceclaw.core.agent.SkillOutcome;
import dev.aceclaw.core.agent.SkillOutcomeTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SkillRefinementEngineTest {

    @TempDir
    Path tempDir;

    private Path workDir;
    private Path skillDir;
    private MockLlmClient mockLlm;
    private SkillMetricsStore metricsStore;
    private SkillRefinementEngine engine;
    private SkillOutcomeTracker tracker;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        workDir = tempDir.resolve("workspace");
        skillDir = workDir.resolve(".aceclaw/skills/review");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                description: "Review code changes"
                context: inline
                max-turns: 5
                user-invocable: true
                disable-model-invocation: false
                ---

                # Review Skill

                Review the patch carefully and summarize issues.
                """);
        mockLlm = new MockLlmClient();
        metricsStore = new SkillMetricsStore();
        engine = new SkillRefinementEngine(mockLlm, "mock-model", metricsStore);
        tracker = new SkillOutcomeTracker();
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Test
    void refinesUnderperformingSkillAndResetsMetricsWindow() throws Exception {
        record(success(2), failure("missed logic bug"), success(2), failure("weak summary"),
                success(1), failure("ignored failing test"), success(2), failure("wrong file"),
                failure("missed regression"), failure("vague output"));
        persistMetrics();
        mockLlm.enqueueSendMessageResponse(MockLlmClient.sendMessageTextResponse("""
                {
                  "reason": "Add explicit regression and test checks.",
                  "updated_body": "# Review Skill\\n\\nReview the patch carefully.\\n\\nChecklist:\\n1. Verify failing tests.\\n2. Call out regressions with file evidence."
                }
                """));

        var outcome = evaluate();

        assertThat(outcome.action()).isEqualTo(SkillRefinementEngine.RefinementAction.REFINED);
        assertThat(Files.readString(skillDir.resolve("SKILL.md")))
                .contains("Verify failing tests")
                .contains("disable-model-invocation: false");
        assertThat(Files.readString(skillDir.resolve("versions/v1.md")))
                .contains("Review the patch carefully and summarize issues.");
        assertThat(tracker.getMetrics("review")).isEmpty();
        assertThat(Files.exists(skillDir.resolve("metrics.json"))).isFalse();
        assertThat(mockLlm.capturedSendRequests()).hasSize(1);

        var state = readState();
        assertThat(state.currentVersion()).isEqualTo(1);
        assertThat(state.latestVersion()).isEqualTo(1);
        assertThat(state.rollbackBaselineSuccessRate()).isEqualTo(0.4);
        assertThat(state.deprecated()).isFalse();
    }

    @Test
    void deprecatesSkillAfterFiveConsecutiveFailures() throws Exception {
        record(failure("missed regression"), failure("bad recommendation"), failure("missed test"),
                failure("incorrect summary"), failure("wrong conclusion"));
        persistMetrics();

        var outcome = evaluate();

        assertThat(outcome.action()).isEqualTo(SkillRefinementEngine.RefinementAction.DEPRECATED);
        assertThat(Files.readString(skillDir.resolve("SKILL.md")))
                .contains("disable-model-invocation: true")
                .contains("deprecated: true");
        assertThat(mockLlm.capturedSendRequests()).isEmpty();
        assertThat(tracker.getMetrics("review")).isEmpty();
        assertThat(readState().deprecated()).isTrue();
    }

    @Test
    void rollsBackRefinedSkillWhenNewWindowPerformsWorse() throws Exception {
        String original = Files.readString(skillDir.resolve("SKILL.md"));
        record(success(1), failure("missed bug"), success(2), failure("weak summary"),
                success(1), failure("ignored test"), success(2), failure("wrong file"),
                failure("missed regression"), failure("vague output"));
        persistMetrics();
        mockLlm.enqueueSendMessageResponse(MockLlmClient.sendMessageTextResponse("""
                {
                  "reason": "Make the review steps more explicit.",
                  "updated_body": "# Review Skill\\n\\nUse an explicit checklist before answering."
                }
                """));
        var refined = evaluate();
        assertThat(refined.action()).isEqualTo(SkillRefinementEngine.RefinementAction.REFINED);

        var afterFirstFailure = recordAndEvaluate(failure("still missed a regression"));
        var afterSecondFailure = recordAndEvaluate(failure("still ignored tests"));
        var afterThirdFailure = recordAndEvaluate(failure("still too vague"));

        assertThat(afterFirstFailure.action()).isEqualTo(SkillRefinementEngine.RefinementAction.NONE);
        assertThat(afterSecondFailure.action()).isEqualTo(SkillRefinementEngine.RefinementAction.NONE);
        assertThat(afterThirdFailure.action()).isEqualTo(SkillRefinementEngine.RefinementAction.ROLLED_BACK);
        assertThat(Files.readString(skillDir.resolve("SKILL.md"))).isEqualTo(original);
        assertThat(tracker.getMetrics("review")).isEmpty();
        assertThat(readState().currentVersion()).isZero();
        assertThat(readState().latestVersion()).isEqualTo(1);
    }

    private SkillRefinementEngine.RefinementOutcome recordAndEvaluate(SkillOutcome outcome) throws Exception {
        tracker.record("review", outcome);
        persistMetrics();
        return evaluate();
    }

    @Test
    void analyzeUsesLastTenInvocationsAndCorrectionsBreakFailureStreak() {
        record(
                failure("old-1"), failure("old-2"), failure("old-3"), failure("old-4"), failure("old-5"),
                success(1), success(1), success(1), success(1), success(1),
                success(1), success(1), success(1), success(1), success(1));

        var decision = engine.analyze(tracker.outcomes("review"));

        assertThat(decision).isInstanceOf(SkillRefinementEngine.RefinementDecision.NoActionNeeded.class);

        tracker.reset("review");
        record(
                failure("f1"), failure("f2"), failure("f3"), failure("f4"),
                new SkillOutcome.UserCorrected(Instant.now(), "fix prompt"),
                failure("f5"));

        decision = engine.analyze(tracker.outcomes("review"));

        assertThat(decision).isInstanceOf(SkillRefinementEngine.RefinementDecision.NoActionNeeded.class);
    }

    private SkillRefinementEngine.RefinementOutcome evaluate() throws Exception {
        var outcomes = tracker.outcomes("review");
        var plan = engine.prepare(workDir, "review", outcomes);
        if (plan.action() == SkillRefinementEngine.RefinementAction.NONE) {
            return SkillRefinementEngine.RefinementOutcome.none();
        }
        SkillRefinementEngine.SkillRefinement proposal = null;
        if (plan.action() == SkillRefinementEngine.RefinementAction.REFINED) {
            proposal = engine.proposeRefinement(plan, outcomes);
        }
        return engine.apply(workDir, tracker, plan, proposal);
    }

    private void persistMetrics() throws Exception {
        metricsStore.persist(workDir, "review", tracker);
    }

    private SkillRefinementEngine.RefinementState readState() throws Exception {
        return objectMapper.readValue(
                skillDir.resolve("skill-refinement-state.json").toFile(),
                SkillRefinementEngine.RefinementState.class);
    }

    private void record(SkillOutcome... outcomes) {
        for (var outcome : outcomes) {
            tracker.record("review", outcome);
        }
    }

    private static SkillOutcome.Success success(int turns) {
        return new SkillOutcome.Success(Instant.now(), turns);
    }

    private static SkillOutcome.Failure failure(String reason) {
        return new SkillOutcome.Failure(Instant.now(), reason);
    }
}

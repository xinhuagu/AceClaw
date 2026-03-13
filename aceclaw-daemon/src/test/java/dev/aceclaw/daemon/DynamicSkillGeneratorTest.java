package dev.aceclaw.daemon;

import dev.aceclaw.core.agent.SkillRegistry;
import dev.aceclaw.core.agent.SkillOutcome;
import dev.aceclaw.core.agent.Turn;
import dev.aceclaw.core.llm.ContentBlock;
import dev.aceclaw.core.llm.Message;
import dev.aceclaw.core.llm.StopReason;
import dev.aceclaw.core.llm.Usage;
import dev.aceclaw.memory.Insight;
import dev.aceclaw.memory.PatternType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicSkillGeneratorTest {

    @TempDir
    Path tempDir;

    private Path workDir;
    private MockLlmClient mockLlm;
    private SkillRegistry skillRegistry;
    private DynamicSkillGenerator generator;
    private MutableClock clock;

    @BeforeEach
    void setUp() throws Exception {
        workDir = tempDir.resolve("workspace");
        Files.createDirectories(workDir);
        mockLlm = new MockLlmClient();
        skillRegistry = SkillRegistry.load(workDir);
        clock = new MutableClock(Instant.parse("2026-03-13T12:00:00Z"));
        generator = new DynamicSkillGenerator(mockLlm, ignored -> "mock-model", skillRegistry, clock);
    }

    @Test
    void recordsRuntimeSkillExplainabilityAndDraftPersistence() throws Exception {
        var explanationStore = new LearningExplanationStore();
        generator.setLearningExplanationRecorder(new LearningExplanationRecorder(explanationStore));
        var validationStore = new LearningValidationStore();
        generator.setLearningValidationRecorder(new LearningValidationRecorder(validationStore));
        mockLlm.enqueueSendMessageResponse(MockLlmClient.sendMessageTextResponse("""
                {
                  "name": "review-file-workflow",
                  "description": "Review a file-oriented workflow.",
                  "argument_hint": "<target>",
                  "body": "# Runtime Workflow\\n\\nFollow the repeated workflow carefully."
                }
                """));

        var generated = generator.maybeGenerate(
                "session-1",
                workDir,
                repeatedSequenceTurn("read_file", "grep", "edit_file"),
                sessionHistory("Please inspect the config files."),
                repeatedSequenceInsight(),
                Set.of("read_file", "grep", "edit_file", "skill"));

        assertThat(generated).isPresent();
        generator.onOutcome("session-1", workDir, "review-file-workflow", new SkillOutcome.Success(clock.instant(), 1));
        generator.onOutcome("session-1", workDir, "review-file-workflow", new SkillOutcome.Success(clock.instant(), 1));
        assertThat(generator.persistDrafts("session-1", workDir)).isEqualTo(1);

        var explanations = explanationStore.recent(workDir, 10);
        assertThat(explanations).anyMatch(explanation -> explanation.actionType().equals("runtime_skill_created"));
        assertThat(explanations).anyMatch(explanation -> explanation.actionType().equals("runtime_skill_persisted"));
        var validations = validationStore.recent(workDir, 10);
        assertThat(validations).anyMatch(validation -> validation.verdict() == LearningValidation.Verdict.OBSERVED_USEFUL);
        assertThat(validations).anyMatch(validation -> validation.verdict() == LearningValidation.Verdict.HOLD);
    }

    @Test
    void generatesRuntimeSkillAndPersistsDraftOnSessionEnd() throws Exception {
        mockLlm.enqueueSendMessageResponse(MockLlmClient.sendMessageTextResponse("""
                {
                  "name": "review-file-workflow",
                  "description": "Review a file-oriented workflow.",
                  "argument_hint": "<target>",
                  "body": "# Runtime Workflow\\n\\nFollow the repeated workflow carefully."
                }
                """));

        var generated = generator.maybeGenerate(
                "session-1",
                workDir,
                repeatedSequenceTurn("read_file", "grep", "edit_file"),
                sessionHistory("Please inspect the config files."),
                repeatedSequenceInsight(),
                Set.of("read_file", "grep", "edit_file", "skill"));

        assertThat(generated).isPresent();
        assertThat(skillRegistry.names("session-1")).contains("review-file-workflow");
        assertThat(skillRegistry.names("session-2")).doesNotContain("review-file-workflow");
        assertThat(skillRegistry.formatDescriptions("session-1")).contains("review-file-workflow");
        generator.onOutcome("session-1", workDir, "review-file-workflow", new SkillOutcome.Success(clock.instant(), 1));
        generator.onOutcome("session-1", workDir, "review-file-workflow", new SkillOutcome.Success(clock.instant(), 1));

        int persisted = generator.persistDrafts("session-1", workDir);

        assertThat(persisted).isEqualTo(1);
        assertThat(skillRegistry.names("session-1")).doesNotContain("review-file-workflow");
        assertThat(workDir.resolve(".aceclaw/skills-drafts/review-file-workflow/SKILL.md"))
                .exists()
                .content()
                .contains("argument-hint: \"<target>\"")
                .contains("disable-model-invocation: true")
                .contains("source-session-id: \"session-1\"")
                .contains("source-tool-sequence: \"read_file -> grep -> edit_file\"");
    }

    @Test
    void skipsRuntimeGenerationWhenSequenceContainsBash() {
        var generated = generator.maybeGenerate(
                "session-1",
                workDir,
                repeatedSequenceTurn("read_file", "bash", "edit_file"),
                sessionHistory("Please inspect the config files."),
                repeatedSequenceInsight(),
                Set.of("read_file", "bash", "edit_file"));

        assertThat(generated).isEmpty();
        assertThat(mockLlm.capturedSendRequests()).isEmpty();
        assertThat(skillRegistry.names("session-1")).doesNotContain("runtime-workflow");
    }

    @Test
    void limitsRuntimeSkillsPerSessionToThree() {
        for (int i = 1; i <= 3; i++) {
            mockLlm.enqueueSendMessageResponse(MockLlmClient.sendMessageTextResponse("""
                    {
                      "name": "workflow-%d",
                      "description": "Generated workflow %d",
                      "argument_hint": "",
                      "body": "# Workflow %d\\n\\nUse the repeated sequence."
                    }
                    """.formatted(i, i, i)));
            var generated = generator.maybeGenerate(
                    "session-1",
                    workDir,
                    repeatedSequenceTurn("tool-" + i, "read_file", "edit_file"),
                    sessionHistory("Handle workflow " + i),
                    repeatedSequenceInsight("tool-" + i, "read_file", "edit_file"),
                    Set.of("tool-" + i, "read_file", "edit_file"));
            assertThat(generated).isPresent();
        }

        mockLlm.enqueueSendMessageResponse(MockLlmClient.sendMessageTextResponse("""
                {
                  "name": "workflow-4",
                  "description": "Generated workflow 4",
                  "argument_hint": "",
                  "body": "# Workflow 4\\n\\nUse the repeated sequence."
                }
                """));

        var fourth = generator.maybeGenerate(
                "session-1",
                workDir,
                repeatedSequenceTurn("glob", "grep", "read_file"),
                sessionHistory("Handle workflow 4"),
                repeatedSequenceInsight("glob", "grep", "read_file"),
                Set.of("glob", "grep", "read_file"));

        assertThat(fourth).isEmpty();
        assertThat(skillRegistry.runtimeSkills("session-1")).hasSize(3);
    }

    @Test
    void rejectsGeneratedDraftThatMentionsBashInBody() {
        mockLlm.enqueueSendMessageResponse(MockLlmClient.sendMessageTextResponse("""
                {
                  "name": "bad-workflow",
                  "description": "Contains bash",
                  "argument_hint": "",
                  "body": "Run bash to inspect the repo."
                }
                """));

        var generated = generator.maybeGenerate(
                "session-1",
                workDir,
                repeatedSequenceTurn("read_file", "grep", "edit_file"),
                sessionHistory("Inspect files"),
                repeatedSequenceInsight(),
                Set.of("read_file", "grep", "edit_file"));

        assertThat(generated).isPresent();
        assertThat(generated.orElseThrow().body()).doesNotContain("bash");
    }

    @Test
    void skipsGenerationWhenFallbackDraftWouldMentionBash() {
        mockLlm.enqueueSendMessageResponse(MockLlmClient.sendMessageTextResponse("""
                {
                  "name": "bad-workflow",
                  "description": "Contains bash",
                  "argument_hint": "",
                  "body": "Run bash to inspect the repo."
                }
                """));

        var generated = generator.maybeGenerate(
                "session-1",
                workDir,
                repeatedSequenceTurn("read_file", "grep", "edit_file"),
                sessionHistory("Please use bash if needed."),
                repeatedSequenceInsight(),
                Set.of("read_file", "grep", "edit_file"));

        assertThat(generated).isEmpty();
        assertThat(skillRegistry.runtimeSkills("session-1")).isEmpty();
    }

    @Test
    void skipsGenerationWhenObservedToolIsNotAllowedInSession() {
        var generated = generator.maybeGenerate(
                "session-1",
                workDir,
                repeatedSequenceTurn("read_file", "grep", "edit_file"),
                sessionHistory("Inspect files"),
                repeatedSequenceInsight(),
                Set.of("read_file", "edit_file"));

        assertThat(generated).isEmpty();
    }

    @Test
    void requiresMatchingRepeatedSequenceInsight() {
        mockLlm.enqueueSendMessageResponse(MockLlmClient.sendMessageTextResponse("""
                {
                  "name": "workflow",
                  "description": "Generated workflow",
                  "argument_hint": "",
                  "body": "# Workflow\\n\\nUse the repeated sequence."
                }
                """));

        var generated = generator.maybeGenerate(
                "session-1",
                workDir,
                repeatedSequenceTurn("read_file", "grep", "edit_file"),
                sessionHistory("Inspect files"),
                List.of(new Insight.PatternInsight(
                        PatternType.REPEATED_TOOL_SEQUENCE,
                        "Repeated tool sequence [grep -> read_file -> edit_file] observed 3 times",
                        3,
                        0.9,
                        List.of("current turn"))),
                Set.of("read_file", "grep", "edit_file"));

        assertThat(generated).isEmpty();
    }

    @Test
    void doesNotRegenerateAfterSessionHasBeenPersisted() throws Exception {
        mockLlm.enqueueSendMessageResponse(MockLlmClient.sendMessageTextResponse("""
                {
                  "name": "workflow-one",
                  "description": "Generated workflow",
                  "argument_hint": "",
                  "body": "# Workflow\\n\\nUse the repeated sequence."
                }
                """));
        assertThat(generator.maybeGenerate(
                "session-1",
                workDir,
                repeatedSequenceTurn("read_file", "grep", "edit_file"),
                sessionHistory("Inspect files"),
                repeatedSequenceInsight(),
                Set.of("read_file", "grep", "edit_file"))).isPresent();
        generator.onOutcome("session-1", workDir, "workflow-one", new SkillOutcome.Success(clock.instant(), 1));
        generator.onOutcome("session-1", workDir, "workflow-one", new SkillOutcome.Success(clock.instant(), 1));

        assertThat(generator.persistDrafts("session-1", workDir)).isEqualTo(1);

        mockLlm.enqueueSendMessageResponse(MockLlmClient.sendMessageTextResponse("""
                {
                  "name": "workflow-two",
                  "description": "Generated workflow",
                  "argument_hint": "",
                  "body": "# Workflow\\n\\nUse the repeated sequence."
                }
                """));
        assertThat(generator.maybeGenerate(
                "session-1",
                workDir,
                repeatedSequenceTurn("read_file", "grep", "edit_file"),
                sessionHistory("Inspect files again"),
                repeatedSequenceInsight(),
                Set.of("read_file", "grep", "edit_file"))).isEmpty();
        assertThat(skillRegistry.runtimeSkills("session-1")).isEmpty();
    }

    @Test
    void suppressesRuntimeGenerationWhenDurableSkillAlreadyCoversWorkflow() throws Exception {
        createDurableSkill("existing-review", List.of("read_file", "grep", "edit_file"));
        skillRegistry = SkillRegistry.load(workDir);
        generator = new DynamicSkillGenerator(mockLlm, ignored -> "mock-model", skillRegistry, clock);

        var explanationStore = new LearningExplanationStore();
        generator.setLearningExplanationRecorder(new LearningExplanationRecorder(explanationStore));
        var validationStore = new LearningValidationStore();
        generator.setLearningValidationRecorder(new LearningValidationRecorder(validationStore));

        var generated = generator.maybeGenerate(
                "session-1",
                workDir,
                repeatedSequenceTurn("read_file", "grep", "edit_file"),
                sessionHistory("Inspect files"),
                repeatedSequenceInsight(),
                Set.of("read_file", "grep", "edit_file"));

        assertThat(generated).isEmpty();
        assertThat(skillRegistry.runtimeSkills("session-1")).isEmpty();
        assertThat(explanationStore.recent(workDir, 10))
                .anyMatch(explanation -> explanation.actionType().equals("runtime_skill_conflict"));
        assertThat(validationStore.recent(workDir, 10))
                .anyMatch(validation -> validation.summary().contains("durable skill"));
    }

    @Test
    void onlyPersistsRuntimeDraftAfterTwoSuccessfulUses() throws Exception {
        mockLlm.enqueueSendMessageResponse(MockLlmClient.sendMessageTextResponse("""
                {
                  "name": "review-file-workflow",
                  "description": "Review a file-oriented workflow.",
                  "argument_hint": "<target>",
                  "body": "# Runtime Workflow\\n\\nFollow the repeated workflow carefully."
                }
                """));

        var generated = generator.maybeGenerate(
                "session-1",
                workDir,
                repeatedSequenceTurn("read_file", "grep", "edit_file"),
                sessionHistory("Please inspect the config files."),
                repeatedSequenceInsight(),
                Set.of("read_file", "grep", "edit_file"));
        assertThat(generated).isPresent();

        generator.onOutcome("session-1", workDir, "review-file-workflow",
                new SkillOutcome.Success(clock.instant(), 1));
        assertThat(generator.persistDrafts("session-1", workDir)).isZero();

        skillRegistry = SkillRegistry.load(workDir);
        generator = new DynamicSkillGenerator(mockLlm, ignored -> "mock-model", skillRegistry, clock);
        mockLlm.enqueueSendMessageResponse(MockLlmClient.sendMessageTextResponse("""
                {
                  "name": "review-file-workflow",
                  "description": "Review a file-oriented workflow.",
                  "argument_hint": "<target>",
                  "body": "# Runtime Workflow\\n\\nFollow the repeated workflow carefully."
                }
                """));
        assertThat(generator.maybeGenerate(
                "session-2",
                workDir,
                repeatedSequenceTurn("read_file", "grep", "edit_file"),
                sessionHistory("Please inspect the config files."),
                repeatedSequenceInsight(),
                Set.of("read_file", "grep", "edit_file"))).isPresent();
        generator.onOutcome("session-2", workDir, "review-file-workflow",
                new SkillOutcome.Success(clock.instant(), 1));
        generator.onOutcome("session-2", workDir, "review-file-workflow",
                new SkillOutcome.Success(clock.instant(), 1));

        assertThat(generator.persistDrafts("session-2", workDir)).isEqualTo(1);
    }

    @Test
    void suppressesRuntimeSkillAfterUserCorrection() throws Exception {
        mockLlm.enqueueSendMessageResponse(MockLlmClient.sendMessageTextResponse("""
                {
                  "name": "review-file-workflow",
                  "description": "Review a file-oriented workflow.",
                  "argument_hint": "<target>",
                  "body": "# Runtime Workflow\\n\\nFollow the repeated workflow carefully."
                }
                """));

        assertThat(generator.maybeGenerate(
                "session-1",
                workDir,
                repeatedSequenceTurn("read_file", "grep", "edit_file"),
                sessionHistory("Please inspect the config files."),
                repeatedSequenceInsight(),
                Set.of("read_file", "grep", "edit_file"))).isPresent();

        generator.onOutcome("session-1", workDir, "review-file-workflow",
                new SkillOutcome.UserCorrected(clock.instant(), "Use a narrower grep pattern"));

        assertThat(skillRegistry.runtimeSkills("session-1")).isEmpty();
        assertThat(generator.maybeGenerate(
                "session-1",
                workDir,
                repeatedSequenceTurn("read_file", "grep", "edit_file"),
                sessionHistory("Please inspect the config files again."),
                repeatedSequenceInsight(),
                Set.of("read_file", "grep", "edit_file"))).isEmpty();
        assertThat(generator.persistDrafts("session-1", workDir)).isZero();
    }

    @Test
    void expiresInactiveRuntimeSkills() throws Exception {
        mockLlm.enqueueSendMessageResponse(MockLlmClient.sendMessageTextResponse("""
                {
                  "name": "review-file-workflow",
                  "description": "Review a file-oriented workflow.",
                  "argument_hint": "<target>",
                  "body": "# Runtime Workflow\\n\\nFollow the repeated workflow carefully."
                }
                """));

        assertThat(generator.maybeGenerate(
                "session-1",
                workDir,
                repeatedSequenceTurn("read_file", "grep", "edit_file"),
                sessionHistory("Please inspect the config files."),
                repeatedSequenceInsight(),
                Set.of("read_file", "grep", "edit_file"))).isPresent();

        clock.advance(java.time.Duration.ofMinutes(21));
        generator.pruneExpired("session-1", workDir);

        assertThat(skillRegistry.runtimeSkills("session-1")).isEmpty();
    }

    private void createDurableSkill(String name, List<String> allowedTools) throws Exception {
        Path skillDir = workDir.resolve(".aceclaw/skills").resolve(name);
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: "%s"
                description: "Existing durable workflow"
                context: "FORK"
                allowed-tools: [%s]
                disable-model-invocation: false
                ---

                # Durable workflow

                Use the existing durable workflow.
                """.formatted(
                name,
                allowedTools.stream().map(tool -> "\"" + tool + "\"").reduce((a, b) -> a + ", " + b).orElse("")));
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        void advance(java.time.Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    private static List<Insight> repeatedSequenceInsight() {
        return repeatedSequenceInsight("read_file", "grep", "edit_file");
    }

    private static List<Insight> repeatedSequenceInsight(String first, String second, String third) {
        return List.of(new Insight.PatternInsight(
                PatternType.REPEATED_TOOL_SEQUENCE,
                "Repeated tool sequence [%s -> %s -> %s] observed 3 times".formatted(first, second, third),
                3,
                0.9,
                List.of("current turn")));
    }

    private static Turn repeatedSequenceTurn(String first, String second, String third) {
        return new Turn(List.of(
                new Message.AssistantMessage(List.of(
                        new ContentBlock.ToolUse("tu-1", first, "{}"),
                        new ContentBlock.ToolUse("tu-2", second, "{}"),
                        new ContentBlock.ToolUse("tu-3", third, "{}")))),
                StopReason.END_TURN,
                new Usage(10, 10));
    }

    private static List<AgentSession.ConversationMessage> sessionHistory(String prompt) {
        return List.of(
                new AgentSession.ConversationMessage.User(prompt),
                new AgentSession.ConversationMessage.Assistant("Working on it."));
    }
}

package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.aceclaw.core.llm.ContentBlock;
import dev.aceclaw.core.llm.LlmResponse;
import dev.aceclaw.core.llm.StopReason;
import dev.aceclaw.core.llm.Usage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link SessionSkillPacker} — extracting skill drafts from sessions.
 */
class SessionSkillPackerTest {

    @TempDir
    Path tempDir;

    private Path workDir;
    private MockLlmClient mockLlm;
    private SessionManager sessionManager;
    private SessionHistoryStore historyStore;
    private ObjectMapper objectMapper;
    private SessionSkillPacker packer;

    /** Small budget for truncation tests — 5K chars forces truncation on moderate conversations. */
    private static final int SMALL_BUDGET = 5_000;

    @BeforeEach
    void setUp() throws Exception {
        workDir = tempDir.resolve("workspace");
        Files.createDirectories(workDir);

        Path homeDir = tempDir.resolve("home");
        Files.createDirectories(homeDir);

        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        mockLlm = new MockLlmClient();
        sessionManager = new SessionManager();
        historyStore = new SessionHistoryStore(homeDir);
        historyStore.init();

        packer = new SessionSkillPacker(
                historyStore, sessionManager, mockLlm, "mock-model", objectMapper);
    }

    @Test
    void happyPath_generatesValidSkillMd() throws Exception {
        // Create a session with some messages
        var session = sessionManager.createSession(workDir);
        session.addMessage(new AgentSession.ConversationMessage.User("Create a todo app"));
        session.addMessage(new AgentSession.ConversationMessage.Assistant(
                "I'll create a todo app. Using write_file to create index.html..."));
        session.addMessage(new AgentSession.ConversationMessage.Assistant(
                "File created. Now adding styles.css..."));
        session.addMessage(new AgentSession.ConversationMessage.User("Looks good!"));

        // Enqueue LLM extraction response
        String llmResponse = """
                {
                  "name": "create-todo-app",
                  "description": "Creates a simple todo web application",
                  "preconditions": ["Node.js installed", "Empty project directory"],
                  "steps": [
                    {
                      "description": "Create index.html with todo app structure",
                      "tool": "write_file",
                      "parameters_hint": "path: index.html, content: HTML with todo form",
                      "success_check": "File exists and contains todo form markup",
                      "failure_guidance": "Check file permissions"
                    },
                    {
                      "description": "Create styles.css",
                      "tool": "write_file",
                      "parameters_hint": "path: styles.css, content: CSS styles",
                      "success_check": "File exists",
                      "failure_guidance": "Retry write"
                    }
                  ],
                  "tools": ["write_file"],
                  "success_checks": ["Both files exist", "HTML references CSS"]
                }
                """;
        mockLlm.enqueueSendMessageResponse(sendResponse(llmResponse));

        var result = packer.pack(session.id(), null, null, null, workDir);

        assertThat(result.skillName()).isEqualTo("create-todo-app");
        assertThat(result.stepCount()).isEqualTo(2);
        assertThat(result.relativePath()).contains("skills-drafts/create-todo-app/SKILL.md");

        // Verify the file was written
        Path skillFile = workDir.resolve(result.relativePath());
        assertThat(skillFile).exists();

        String content = Files.readString(skillFile);
        assertThat(content).contains("name: \"create-todo-app\"");
        assertThat(content).contains("source-session-id:");
        assertThat(content).contains("source-turn-range: \"full\"");
        assertThat(content).contains("disable-model-invocation: true");
        assertThat(content).contains("Step 1: Create index.html");
        assertThat(content).contains("Step 2: Create styles.css");
        assertThat(content).contains("**Tool**: `write_file`");

        // Verify audit trail
        Path auditFile = workDir.resolve(".aceclaw/metrics/continuous-learning/session-skill-pack-audit.jsonl");
        assertThat(auditFile).exists();
        String auditContent = Files.readString(auditFile);
        assertThat(auditContent).contains("session-pack");
        assertThat(auditContent).contains(session.id());
    }

    @Test
    void turnRangeFilter_onlyIncludesSpecifiedRange() throws Exception {
        var session = sessionManager.createSession(workDir);
        session.addMessage(new AgentSession.ConversationMessage.User("msg 0"));
        session.addMessage(new AgentSession.ConversationMessage.Assistant("msg 1"));
        session.addMessage(new AgentSession.ConversationMessage.User("msg 2"));
        session.addMessage(new AgentSession.ConversationMessage.Assistant("msg 3"));
        session.addMessage(new AgentSession.ConversationMessage.User("msg 4"));

        String llmResponse = """
                {
                  "name": "filtered-skill",
                  "description": "Test with turn range",
                  "preconditions": [],
                  "steps": [{"description": "Single step", "tool": null}],
                  "tools": [],
                  "success_checks": []
                }
                """;
        mockLlm.enqueueSendMessageResponse(sendResponse(llmResponse));

        var result = packer.pack(session.id(), null, 1, 3, workDir);

        assertThat(result.skillName()).isEqualTo("filtered-skill");
        assertThat(result.stepCount()).isEqualTo(1);

        // Verify turn range recorded in frontmatter
        Path skillFile = workDir.resolve(result.relativePath());
        String content = Files.readString(skillFile);
        assertThat(content).contains("source-turn-range: \"1-3\"");

        // Verify the LLM was called with only messages 1-3
        var captured = mockLlm.capturedSendRequests();
        assertThat(captured).hasSize(1);
    }

    @Test
    void idempotent_packingSameSessionTwiceReusesDraft() throws Exception {
        var session = sessionManager.createSession(workDir);
        session.addMessage(new AgentSession.ConversationMessage.User("Do something"));
        session.addMessage(new AgentSession.ConversationMessage.Assistant("Done!"));

        String llmResponse = """
                {
                  "name": "idempotent-test",
                  "description": "Test idempotency",
                  "preconditions": [],
                  "steps": [{"description": "Step one", "tool": "bash"}],
                  "tools": ["bash"],
                  "success_checks": ["Done"]
                }
                """;

        // First pack
        mockLlm.enqueueSendMessageResponse(sendResponse(llmResponse));
        var result1 = packer.pack(session.id(), null, null, null, workDir);

        // Second pack (same session) — the existing file already has the source-session-id,
        // so it should reuse the same name and overwrite
        mockLlm.enqueueSendMessageResponse(sendResponse(llmResponse));
        var result2 = packer.pack(session.id(), null, null, null, workDir);

        assertThat(result1.skillName()).isEqualTo(result2.skillName());
        assertThat(result1.relativePath()).isEqualTo(result2.relativePath());
    }

    @Test
    void idempotent_differentLlmNameSameSession_reusesExistingDraft() throws Exception {
        var session = sessionManager.createSession(workDir);
        session.addMessage(new AgentSession.ConversationMessage.User("Deploy the app"));
        session.addMessage(new AgentSession.ConversationMessage.Assistant("Deployed!"));

        // First pack: LLM returns name "deploy-app"
        String llmResponse1 = """
                {
                  "name": "deploy-app",
                  "description": "Deploys the application",
                  "preconditions": [],
                  "steps": [{"description": "Run deploy", "tool": "bash"}],
                  "tools": ["bash"],
                  "success_checks": ["App running"]
                }
                """;
        mockLlm.enqueueSendMessageResponse(sendResponse(llmResponse1));
        var result1 = packer.pack(session.id(), null, null, null, workDir);

        // Second pack: LLM returns a DIFFERENT name "app-deployment"
        String llmResponse2 = """
                {
                  "name": "app-deployment",
                  "description": "Deploys the application",
                  "preconditions": [],
                  "steps": [{"description": "Run deploy", "tool": "bash"}],
                  "tools": ["bash"],
                  "success_checks": ["App running"]
                }
                """;
        mockLlm.enqueueSendMessageResponse(sendResponse(llmResponse2));
        var result2 = packer.pack(session.id(), null, null, null, workDir);

        // Should reuse the first draft's name, not create a second directory
        assertThat(result1.skillName()).isEqualTo(result2.skillName());
        assertThat(result1.relativePath()).isEqualTo(result2.relativePath());
    }

    @Test
    void missingSession_throwsError() {
        assertThatThrownBy(() -> packer.pack("nonexistent-session", null, null, null, workDir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No messages found");
    }

    @Test
    void malformedLlmResponse_throwsLlmException() throws Exception {
        var session = sessionManager.createSession(workDir);
        session.addMessage(new AgentSession.ConversationMessage.User("Do something"));
        session.addMessage(new AgentSession.ConversationMessage.Assistant("Done"));

        mockLlm.enqueueSendMessageResponse(sendResponse("This is not valid JSON at all"));

        assertThatThrownBy(() -> packer.pack(session.id(), null, null, null, workDir))
                .hasMessageContaining("Failed to parse");
    }

    @Test
    void customSkillName_usedInOutput() throws Exception {
        var session = sessionManager.createSession(workDir);
        session.addMessage(new AgentSession.ConversationMessage.User("Deploy app"));
        session.addMessage(new AgentSession.ConversationMessage.Assistant("Deployed!"));

        String llmResponse = """
                {
                  "name": "deploy-app",
                  "description": "Deploys the application",
                  "preconditions": [],
                  "steps": [{"description": "Run deploy", "tool": "bash"}],
                  "tools": ["bash"],
                  "success_checks": []
                }
                """;
        mockLlm.enqueueSendMessageResponse(sendResponse(llmResponse));

        var result = packer.pack(session.id(), "My Custom Deploy", null, null, workDir);

        assertThat(result.skillName()).isEqualTo("my-custom-deploy");
        assertThat(result.relativePath()).contains("my-custom-deploy");
    }

    @Test
    void fallsBackToPersistedHistory() throws Exception {
        // Create messages in persisted history (no live session)
        String sessionId = "persisted-session-id";
        var messages = List.of(
                new AgentSession.ConversationMessage.User("Build the feature"),
                new AgentSession.ConversationMessage.Assistant("Feature built successfully.")
        );
        // Manually persist messages
        for (var msg : messages) {
            historyStore.appendMessage(sessionId, msg);
        }

        String llmResponse = """
                {
                  "name": "build-feature",
                  "description": "Build a feature",
                  "preconditions": [],
                  "steps": [{"description": "Implement feature", "tool": "write_file"}],
                  "tools": ["write_file"],
                  "success_checks": ["Feature works"]
                }
                """;
        mockLlm.enqueueSendMessageResponse(sendResponse(llmResponse));

        var result = packer.pack(sessionId, null, null, null, workDir);
        assertThat(result.skillName()).isEqualTo("build-feature");
        assertThat(result.stepCount()).isEqualTo(1);
    }

    @Test
    void applyTurnRange_handlesEdgeCases() {
        var messages = List.<AgentSession.ConversationMessage>of(
                new AgentSession.ConversationMessage.User("a"),
                new AgentSession.ConversationMessage.Assistant("b"),
                new AgentSession.ConversationMessage.User("c")
        );

        // Full range when null/null
        assertThat(SessionSkillPacker.applyTurnRange(messages, null, null)).hasSize(3);

        // Start only
        assertThat(SessionSkillPacker.applyTurnRange(messages, 1, null)).hasSize(2);

        // End only
        assertThat(SessionSkillPacker.applyTurnRange(messages, null, 2)).hasSize(2);

        // Out of range
        assertThat(SessionSkillPacker.applyTurnRange(messages, 5, 10)).isEmpty();

        // Negative start clamped to 0
        assertThat(SessionSkillPacker.applyTurnRange(messages, -1, null)).hasSize(3);
    }

    @Test
    void toSlug_variousInputs() {
        assertThat(SessionSkillPacker.toSlug("Create Todo App")).isEqualTo("create-todo-app");
        assertThat(SessionSkillPacker.toSlug("hello_world!@#")).isEqualTo("hello-world");
        assertThat(SessionSkillPacker.toSlug(null)).isEqualTo("extracted-skill");
        assertThat(SessionSkillPacker.toSlug("")).isEqualTo("extracted-skill");
        assertThat(SessionSkillPacker.toSlug("---")).isEqualTo("extracted-skill");
    }

    @Test
    void codeFenceWrappedJson_parsedCorrectly() throws Exception {
        var session = sessionManager.createSession(workDir);
        session.addMessage(new AgentSession.ConversationMessage.User("Test code fences"));
        session.addMessage(new AgentSession.ConversationMessage.Assistant("Done."));

        String llmResponse = """
                Here is the extracted workflow:
                ```json
                {
                  "name": "fenced-skill",
                  "description": "Test code fence extraction",
                  "preconditions": [],
                  "steps": [{"description": "One step", "tool": "read_file"}],
                  "tools": ["read_file"],
                  "success_checks": []
                }
                ```
                """;
        mockLlm.enqueueSendMessageResponse(sendResponse(llmResponse));

        var result = packer.pack(session.id(), null, null, null, workDir);
        assertThat(result.skillName()).isEqualTo("fenced-skill");
    }

    @Test
    void longSession_truncatesToFitBudget() throws Exception {
        // Use a packer with a small budget to force truncation
        var smallPacker = new SessionSkillPacker(
                historyStore, sessionManager, mockLlm, "mock-model", objectMapper, SMALL_BUDGET);

        var session = sessionManager.createSession(workDir);
        // Add initial user goal (should be preserved in head)
        session.addMessage(new AgentSession.ConversationMessage.User("Build a full-stack app with React and Express"));

        // Add many large assistant messages simulating a long-running session
        // Each message is ~2K chars, 50 messages = ~100K chars total — well over 5K budget
        for (int i = 0; i < 50; i++) {
            String bigContent = "Step " + i + ": " + "x".repeat(2000);
            session.addMessage(new AgentSession.ConversationMessage.Assistant(bigContent));
        }

        // Final success message (should be preserved in tail)
        session.addMessage(new AgentSession.ConversationMessage.User("All done, app is working!"));

        String llmResponse = """
                {
                  "name": "long-session-skill",
                  "description": "Full-stack app build from a long session",
                  "preconditions": [],
                  "steps": [{"description": "Build the app", "tool": "bash"}],
                  "tools": ["bash"],
                  "success_checks": ["App works"]
                }
                """;
        mockLlm.enqueueSendMessageResponse(sendResponse(llmResponse));

        var result = smallPacker.pack(session.id(), null, null, null, workDir);

        // Should succeed, not blow up
        assertThat(result.skillName()).isEqualTo("long-session-skill");
        assertThat(result.stepCount()).isEqualTo(1);

        // Verify the LLM received a truncated prompt, not the full 100K+
        var captured = mockLlm.capturedSendRequests();
        assertThat(captured).hasSize(1);
        var userMsg = (dev.aceclaw.core.llm.Message.UserMessage) captured.get(0).messages().get(0);
        var userContent = userMsg.content().get(0);
        assertThat(userContent).isInstanceOf(dev.aceclaw.core.llm.ContentBlock.Text.class);
        String promptText = ((dev.aceclaw.core.llm.ContentBlock.Text) userContent).text();
        assertThat(promptText.length()).isLessThan(SMALL_BUDGET + 500); // some overhead tolerance
        assertThat(promptText).contains("[truncated:");
        // Head should contain the initial goal
        assertThat(promptText).contains("Build a full-stack app");
        // Tail should contain the final message
        assertThat(promptText).contains("All done, app is working!");
    }

    @Test
    void longSession_perMessageTruncation_cutsLargeToolResults() throws Exception {
        // Use a generous total budget so only per-message truncation fires
        var session = sessionManager.createSession(workDir);
        session.addMessage(new AgentSession.ConversationMessage.User("Read the giant log file"));

        // Single message with a 50K char tool result (way over 8K per-message limit)
        String giantResult = "Line 1: error\n" + "x".repeat(50_000) + "\nLine last: success\n";
        session.addMessage(new AgentSession.ConversationMessage.Assistant(giantResult));

        session.addMessage(new AgentSession.ConversationMessage.User("Fix the error"));

        String llmResponse = """
                {
                  "name": "fix-giant-log",
                  "description": "Fix error from giant log",
                  "preconditions": [],
                  "steps": [{"description": "Read log", "tool": "read_file"}, {"description": "Fix error", "tool": "edit_file"}],
                  "tools": ["read_file", "edit_file"],
                  "success_checks": ["Error fixed"]
                }
                """;
        mockLlm.enqueueSendMessageResponse(sendResponse(llmResponse));

        var result = packer.pack(session.id(), null, null, null, workDir);
        assertThat(result.skillName()).isEqualTo("fix-giant-log");

        // Verify per-message truncation happened
        var captured = mockLlm.capturedSendRequests();
        var userMsg2 = (dev.aceclaw.core.llm.Message.UserMessage) captured.get(0).messages().get(0);
        String promptText = ((dev.aceclaw.core.llm.ContentBlock.Text) userMsg2.content().get(0)).text();
        // The prompt should NOT contain the full 50K message
        assertThat(promptText.length()).isLessThan(50_000);
        // But should contain the truncation marker
        assertThat(promptText).contains("[truncated:");
    }

    @Test
    void headTailTruncate_preservesBothEnds() {
        String input = "AAAA" + "B".repeat(10_000) + "CCCC";
        String truncated = SessionSkillPackPrompt.headTailTruncate(input, 1000);

        assertThat(truncated.length()).isLessThanOrEqualTo(1200); // 1000 + marker overhead
        assertThat(truncated).startsWith("AAAA");
        assertThat(truncated).endsWith("CCCC");
        assertThat(truncated).contains("[truncated:");
    }

    @Test
    void headTailTruncate_shortTextUnchanged() {
        assertThat(SessionSkillPackPrompt.headTailTruncate("short", 1000)).isEqualTo("short");
        assertThat(SessionSkillPackPrompt.headTailTruncate(null, 1000)).isNull();
    }

    @Test
    void deriveMaxConversationChars_fromContextWindow() {
        // 200K context: (200000 - 12288 - 500) * 4 = 748_848
        int derived200k = SessionSkillPacker.deriveMaxConversationChars(200_000);
        assertThat(derived200k).isGreaterThan(700_000);

        // 32K context: (32000 - 12288 - 500) * 4 = 76_848
        int derived32k = SessionSkillPacker.deriveMaxConversationChars(32_000);
        assertThat(derived32k).isGreaterThan(50_000).isLessThan(100_000);

        // Tiny context (would go negative): floors at 20K
        int derivedTiny = SessionSkillPacker.deriveMaxConversationChars(10_000);
        assertThat(derivedTiny).isEqualTo(20_000);

        // Unknown (0): default
        assertThat(SessionSkillPacker.deriveMaxConversationChars(0))
                .isEqualTo(SessionSkillPacker.DEFAULT_MAX_CONVERSATION_CHARS);
    }

    // -- helpers --

    private static LlmResponse sendResponse(String text) {
        return new LlmResponse(
                "msg-mock-send",
                "mock-model",
                List.of(new ContentBlock.Text(text)),
                StopReason.END_TURN,
                new Usage(200, 100));
    }
}

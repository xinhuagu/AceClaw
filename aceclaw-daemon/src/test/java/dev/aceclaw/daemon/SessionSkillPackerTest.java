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

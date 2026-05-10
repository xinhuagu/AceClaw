package dev.aceclaw.tools;

import dev.aceclaw.core.agent.SkillConfig;
import dev.aceclaw.core.agent.SkillContentResolver;
import dev.aceclaw.core.agent.SkillRegistry;
import dev.aceclaw.core.agent.SubAgentPermissionChecker;
import dev.aceclaw.core.agent.SubAgentRunner;
import dev.aceclaw.core.agent.Tool;
import dev.aceclaw.core.agent.ToolPermissionChecker;
import dev.aceclaw.core.agent.ToolPermissionResult;
import dev.aceclaw.core.agent.ToolRegistry;
import dev.aceclaw.core.llm.ContentBlock;
import dev.aceclaw.core.llm.LlmClient;
import dev.aceclaw.core.llm.LlmRequest;
import dev.aceclaw.core.llm.LlmResponse;
import dev.aceclaw.core.llm.StopReason;
import dev.aceclaw.core.llm.StreamEvent;
import dev.aceclaw.core.llm.StreamEventHandler;
import dev.aceclaw.core.llm.StreamSession;
import dev.aceclaw.core.llm.Usage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class SkillToolTest {

    @TempDir
    Path tempDir;

    @Test
    void sessionScopedRuntimeSkillCanBeInvokedWithinOwningSession() throws Exception {
        var registry = SkillRegistry.empty();
        var runtimeSkill = new SkillConfig(
                "runtime-review",
                "Runtime review helper",
                null,
                SkillConfig.ExecutionContext.INLINE,
                null,
                List.of("read_file", "grep"),
                4,
                true,
                false,
                "Inspect the files, summarize issues, and keep the answer concise.",
                tempDir.resolve(".aceclaw/runtime-skills/runtime-review"));
        registry.registerRuntime("session-a", runtimeSkill);

        var tool = new SkillTool(registry, new SkillContentResolver(tempDir), null);
        tool.setCurrentSessionId("session-a");

        var result = tool.execute("""
                {"name":"runtime-review"}
                """);

        assertThat(result.isError()).isFalse();
        assertThat(result.output()).contains("summarize issues");
    }

    @Test
    void forkedSkillThreadsParentSessionIdIntoSubAgentPermissionCheck() throws Exception {
        // Regression test for the Codex P1 finding on commit 03472f43b0:
        // SkillTool.executeFork previously called subAgentRunner.run(...) via
        // the legacy 4-arg overload, which silently dropped the parent
        // session id. Since #457's fail-closed null-session guard, this
        // meant every non-read-only tool a forked skill tried to use was
        // denied — mirroring the original P1 that motivated #457 for
        // TaskTool, but in the symmetric SkillTool code path which was
        // overlooked when the TaskTool fix landed.
        //
        // The spy here captures the sessionId argument the loop hands to
        // the permission checker; the assertion is that the parent session
        // id reaches it, not null.
        var capturedSessionIds = new CopyOnWriteArrayList<String>();
        ToolPermissionChecker spy = (toolName, inputJson, sessionId) -> {
            capturedSessionIds.add(sessionId == null ? "<null>" : sessionId);
            // ALLOW so the loop progresses through one tool call to end-of-turn.
            return ToolPermissionResult.ALLOWED;
        };

        // Sub-agent runner with the spy attached and one tool registered for
        // the forked skill to invoke.
        var parentRegistry = new ToolRegistry();
        parentRegistry.register(new Tool() {
            @Override public String name() { return "write_file"; }
            @Override public String description() { return "stub"; }
            @Override public com.fasterxml.jackson.databind.JsonNode inputSchema() {
                return new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            }
            @Override public Tool.ToolResult execute(String inputJson) {
                return new Tool.ToolResult("ok", false);
            }
        });
        var runner = new SubAgentRunner(
                new ToolUseOnceMockLlm(),
                parentRegistry, "mock-model", tempDir, 4096, 0, spy, null);

        // SkillTool wired to that runner, with one FORK-mode skill.
        var skillRegistry = SkillRegistry.empty();
        skillRegistry.registerRuntime("session-A", new SkillConfig(
                "review-and-write",
                "Test fork-mode skill",
                null,
                SkillConfig.ExecutionContext.FORK,
                null,
                List.of("write_file"),
                4,
                true,
                false,
                "Write a file.",
                tempDir.resolve(".aceclaw/runtime-skills/review-and-write")));

        var skillTool = new SkillTool(skillRegistry, new SkillContentResolver(tempDir), runner);
        skillTool.setCurrentSessionId("session-A");

        skillTool.execute("""
                {"name":"review-and-write"}
                """);

        assertThat(capturedSessionIds)
                .as("forked skill's sub-agent must see the parent's session id, not null")
                .containsExactly("session-A");
    }

    /**
     * Mock LLM that emits one {@code write_file} tool_use on the first call
     * and an end-of-turn text on the second. Same shape as the one inside
     * {@code SubAgentRunnerTest}; duplicated here because that one is
     * package-private to its test class.
     */
    private static final class ToolUseOnceMockLlm implements LlmClient {
        private boolean firstCall = true;

        @Override public LlmResponse sendMessage(LlmRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override public StreamSession streamMessage(LlmRequest request) {
            return new StreamSession() {
                @Override public void onEvent(StreamEventHandler handler) {
                    handler.onMessageStart(new StreamEvent.MessageStart("msg", "mock"));
                    if (firstCall) {
                        firstCall = false;
                        var toolUse = new ContentBlock.ToolUse("tu_1", "write_file", "{}");
                        handler.onContentBlockStart(new StreamEvent.ContentBlockStart(0, toolUse));
                        handler.onContentBlockStop(new StreamEvent.ContentBlockStop(0));
                        handler.onMessageDelta(new StreamEvent.MessageDelta(
                                StopReason.TOOL_USE, new Usage(10, 5)));
                    } else {
                        handler.onContentBlockStart(new StreamEvent.ContentBlockStart(0,
                                new ContentBlock.Text("")));
                        handler.onTextDelta(new StreamEvent.TextDelta("done"));
                        handler.onContentBlockStop(new StreamEvent.ContentBlockStop(0));
                        handler.onMessageDelta(new StreamEvent.MessageDelta(
                                StopReason.END_TURN, new Usage(5, 2)));
                    }
                    handler.onComplete(new StreamEvent.StreamComplete());
                }
                @Override public void cancel() {}
            };
        }

        @Override public String provider() { return "mock"; }
        @Override public String defaultModel() { return "mock-model"; }
    }

    @Test
    void sessionScopedRuntimeSkillIsHiddenFromOtherSessions() throws Exception {
        var registry = SkillRegistry.empty();
        registry.registerRuntime("session-a", new SkillConfig(
                "runtime-review",
                "Runtime review helper",
                null,
                SkillConfig.ExecutionContext.INLINE,
                null,
                List.of("read_file"),
                4,
                true,
                false,
                "Inspect files.",
                tempDir.resolve(".aceclaw/runtime-skills/runtime-review")));

        var tool = new SkillTool(registry, new SkillContentResolver(tempDir), null);
        tool.setCurrentSessionId("session-b");

        var result = tool.execute("""
                {"name":"runtime-review"}
                """);

        assertThat(result.isError()).isTrue();
        assertThat(result.output()).contains("Unknown skill");
    }
}

package dev.aceclaw.tools;

import dev.aceclaw.core.agent.SkillConfig;
import dev.aceclaw.core.agent.SkillContentResolver;
import dev.aceclaw.core.agent.SkillRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

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

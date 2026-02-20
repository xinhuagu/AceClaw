package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.aceclaw.core.agent.HookEvent;
import dev.aceclaw.core.agent.HookResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link CommandHookExecutor} using real shell scripts.
 */
@DisabledOnOs(OS.WINDOWS)
class CommandHookExecutorTest {

    @TempDir
    static Path tempDir;

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static Path scriptDir;

    @BeforeAll
    static void createScripts() throws IOException {
        scriptDir = tempDir.resolve("scripts");
        Files.createDirectories(scriptDir);

        // Script that exits 0 with allow decision
        writeScript("allow.sh", """
                #!/bin/bash
                echo '{"decision":"allow"}'
                """);

        // Script that exits 0 with deny decision
        writeScript("deny.sh", """
                #!/bin/bash
                echo '{"decision":"deny","additionalContext":"Blocked by policy"}'
                """);

        // Script that exits 2 (block)
        writeScript("block.sh", """
                #!/bin/bash
                echo "Not allowed by security policy" >&2
                exit 2
                """);

        // Script that exits 1 (non-blocking error)
        writeScript("error.sh", """
                #!/bin/bash
                echo "Something went wrong" >&2
                exit 1
                """);

        // Script that reads stdin and echoes it
        writeScript("echo_stdin.sh", """
                #!/bin/bash
                cat
                """);

        // Script that takes too long (for timeout test)
        writeScript("slow.sh", """
                #!/bin/bash
                sleep 30
                echo '{"decision":"allow"}'
                """);

        // Script that outputs JSON with updatedInput
        writeScript("modify_input.sh", """
                #!/bin/bash
                echo '{"decision":"allow","updatedInput":{"command":"echo safe"},"additionalContext":"Modified by hook"}'
                """);

        // Script that outputs malformed JSON
        writeScript("bad_json.sh", """
                #!/bin/bash
                echo 'this is not json'
                """);

        // Script that outputs empty string
        writeScript("empty_output.sh", """
                #!/bin/bash
                exit 0
                """);
    }

    @Test
    void exit0ProceedWithAllowDecision() {
        var executor = buildExecutor("PreToolUse", "bash", scriptPath("allow.sh"));
        var event = new HookEvent.PreToolUse("s1", "/tmp", "bash", emptyInput());

        var result = executor.execute(event);
        assertThat(result).isInstanceOf(HookResult.Proceed.class);
    }

    @Test
    void exit0WithDenyDecisionBlocks() {
        var executor = buildExecutor("PreToolUse", "bash", scriptPath("deny.sh"));
        var event = new HookEvent.PreToolUse("s1", "/tmp", "bash", emptyInput());

        var result = executor.execute(event);
        assertThat(result).isInstanceOf(HookResult.Block.class);
        assertThat(((HookResult.Block) result).reason()).isEqualTo("Blocked by policy");
    }

    @Test
    void exit2Blocks() {
        var executor = buildExecutor("PreToolUse", "bash", scriptPath("block.sh"));
        var event = new HookEvent.PreToolUse("s1", "/tmp", "bash", emptyInput());

        var result = executor.execute(event);
        assertThat(result).isInstanceOf(HookResult.Block.class);
        assertThat(((HookResult.Block) result).reason()).contains("Not allowed by security policy");
    }

    @Test
    void exit1IsNonBlockingError() {
        // Non-zero, non-2 exit codes are non-blocking errors — execution continues
        var executor = buildExecutor("PreToolUse", "bash", scriptPath("error.sh"));
        var event = new HookEvent.PreToolUse("s1", "/tmp", "bash", emptyInput());

        var result = executor.execute(event);
        // Error is non-blocking, so overall result is still Proceed
        assertThat(result).isInstanceOf(HookResult.Proceed.class);
    }

    @Test
    void stdinPipedCorrectly() throws Exception {
        var executor = buildExecutor("PreToolUse", "bash", scriptPath("echo_stdin.sh"));
        var input = objectMapper.createObjectNode();
        input.put("command", "echo hello");
        var event = new HookEvent.PreToolUse("sess-123", "/work/dir", "bash", input);

        var result = executor.execute(event);
        assertThat(result).isInstanceOf(HookResult.Proceed.class);
        var proceed = (HookResult.Proceed) result;

        // The script echoes stdin back, so stdout should contain the JSON we piped in
        assertThat(proceed.stdout()).isNotNull();
        var echoedJson = objectMapper.readTree(proceed.stdout());
        assertThat(echoedJson.get("session_id").asText()).isEqualTo("sess-123");
        assertThat(echoedJson.get("cwd").asText()).isEqualTo("/work/dir");
        assertThat(echoedJson.get("hook_event_name").asText()).isEqualTo("PreToolUse");
        assertThat(echoedJson.get("tool_name").asText()).isEqualTo("bash");
        assertThat(echoedJson.get("tool_input").get("command").asText()).isEqualTo("echo hello");
    }

    @Test
    void timeoutKillsProcess() {
        // Build a hook with 1-second timeout
        var hookConfig = new HookConfig("command", scriptPath("slow.sh"), 1);
        var matcher = new HookMatcher(Pattern.compile("bash"), List.of(hookConfig));
        var registry = HookRegistry.load(buildRawConfig("PreToolUse", matcher));

        var executor = new CommandHookExecutor(registry, objectMapper, tempDir);
        var event = new HookEvent.PreToolUse("s1", "/tmp", "bash", emptyInput());

        var result = executor.execute(event);
        // Timeout is a non-blocking error — execution continues with Proceed
        assertThat(result).isInstanceOf(HookResult.Proceed.class);
    }

    @Test
    void stdoutJsonParsedWithUpdatedInput() {
        var executor = buildExecutor("PreToolUse", "bash", scriptPath("modify_input.sh"));
        var event = new HookEvent.PreToolUse("s1", "/tmp", "bash", emptyInput());

        var result = executor.execute(event);
        assertThat(result).isInstanceOf(HookResult.Proceed.class);
        var proceed = (HookResult.Proceed) result;
        assertThat(proceed.updatedInput()).isNotNull();
        assertThat(proceed.updatedInput().get("command").asText()).isEqualTo("echo safe");
        assertThat(proceed.additionalContext()).isEqualTo("Modified by hook");
    }

    @Test
    void malformedStdoutContinues() {
        var executor = buildExecutor("PreToolUse", "bash", scriptPath("bad_json.sh"));
        var event = new HookEvent.PreToolUse("s1", "/tmp", "bash", emptyInput());

        var result = executor.execute(event);
        // Malformed JSON should still proceed (non-fatal)
        assertThat(result).isInstanceOf(HookResult.Proceed.class);
    }

    @Test
    void emptyStdoutProceeds() {
        var executor = buildExecutor("PreToolUse", "bash", scriptPath("empty_output.sh"));
        var event = new HookEvent.PreToolUse("s1", "/tmp", "bash", emptyInput());

        var result = executor.execute(event);
        assertThat(result).isInstanceOf(HookResult.Proceed.class);
    }

    @Test
    void preToolUseStopsOnFirstBlock() {
        // Two hooks: first blocks, second should not run
        var blockConfig = new HookConfig("command", scriptPath("block.sh"), 60);
        var allowConfig = new HookConfig("command", scriptPath("allow.sh"), 60);
        var matcher = new HookMatcher(Pattern.compile("bash"), List.of(blockConfig, allowConfig));
        var registry = HookRegistry.load(buildRawConfig("PreToolUse", matcher));

        var executor = new CommandHookExecutor(registry, objectMapper, tempDir);
        var event = new HookEvent.PreToolUse("s1", "/tmp", "bash", emptyInput());

        var result = executor.execute(event);
        assertThat(result).isInstanceOf(HookResult.Block.class);
    }

    @Test
    void postToolUseContinuesOnError() {
        // PostToolUse should run all hooks even if one errors
        var errorConfig = new HookConfig("command", scriptPath("error.sh"), 60);
        var allowConfig = new HookConfig("command", scriptPath("allow.sh"), 60);
        var matcher = new HookMatcher(Pattern.compile("bash"), List.of(errorConfig, allowConfig));
        var registry = HookRegistry.load(buildRawConfig("PostToolUse", matcher));

        var executor = new CommandHookExecutor(registry, objectMapper, tempDir);
        var event = new HookEvent.PostToolUse("s1", "/tmp", "bash", emptyInput(), "output");

        // Should not be a Block — PostToolUse never blocks
        var result = executor.execute(event);
        assertThat(result).isInstanceOf(HookResult.Proceed.class);
    }

    @Test
    void noMatchReturnsEmptyProceed() {
        var executor = buildExecutor("PreToolUse", "bash", scriptPath("allow.sh"));
        var event = new HookEvent.PreToolUse("s1", "/tmp", "read_file", emptyInput());

        var result = executor.execute(event);
        assertThat(result).isInstanceOf(HookResult.Proceed.class);
        var proceed = (HookResult.Proceed) result;
        assertThat(proceed.stdout()).isNull();
    }

    // -- Helpers --

    private static void writeScript(String name, String content) throws IOException {
        var path = scriptDir.resolve(name);
        Files.writeString(path, content);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private static String scriptPath(String name) {
        return scriptDir.resolve(name).toAbsolutePath().toString();
    }

    private static ObjectNode emptyInput() {
        return new ObjectMapper().createObjectNode();
    }

    private CommandHookExecutor buildExecutor(String eventName, String toolPattern, String command) {
        var hookConfig = HookConfig.command(command);
        var matcher = new HookMatcher(Pattern.compile(toolPattern), List.of(hookConfig));
        var registry = HookRegistry.load(buildRawConfig(eventName, matcher));
        return new CommandHookExecutor(registry, objectMapper, tempDir);
    }

    /**
     * Builds a raw config map from a HookMatcher, to use with HookRegistry.load().
     */
    private static Map<String, List<AceClawConfig.HookMatcherFormat>> buildRawConfig(
            String eventName, HookMatcher matcher) {
        var matcherFormats = new ArrayList<AceClawConfig.HookMatcherFormat>();
        var mf = new AceClawConfig.HookMatcherFormat();
        mf.matcher = matcher.matcher() != null ? matcher.matcher().pattern() : null;
        var hookFormats = new ArrayList<AceClawConfig.HookConfigFormat>();
        for (var hc : matcher.hooks()) {
            var hf = new AceClawConfig.HookConfigFormat();
            hf.type = hc.type();
            hf.command = hc.command();
            hf.timeout = hc.timeout();
            hookFormats.add(hf);
        }
        mf.hooks = hookFormats;
        matcherFormats.add(mf);

        var map = new HashMap<String, List<AceClawConfig.HookMatcherFormat>>();
        map.put(eventName, matcherFormats);
        return map;
    }
}

package dev.aceclaw.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.agent.CancellationToken;
import dev.aceclaw.core.agent.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BashExecToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path workDir;

    private BashExecTool tool;

    @BeforeEach
    void setUp() {
        tool = new BashExecTool(workDir);
    }

    @Test
    void nameIsBash() {
        assertThat(tool.name()).isEqualTo("bash");
    }

    @Test
    void descriptionIsNotEmpty() {
        assertThat(tool.description()).isNotBlank();
    }

    @Test
    void echoCommand() throws Exception {
        var input = MAPPER.writeValueAsString(
                MAPPER.createObjectNode().put("command", "echo hello"));

        var result = tool.execute(input);

        assertThat(result.isError()).isFalse();
        assertThat(result.output()).contains("hello");
    }

    @Test
    void missingCommandReturnsError() throws Exception {
        var input = MAPPER.writeValueAsString(MAPPER.createObjectNode());

        var result = tool.execute(input);

        assertThat(result.isError()).isTrue();
        assertThat(result.output()).contains("Missing required parameter");
    }

    @Test
    void blankCommandReturnsError() throws Exception {
        var input = MAPPER.writeValueAsString(
                MAPPER.createObjectNode().put("command", "   "));

        var result = tool.execute(input);

        assertThat(result.isError()).isTrue();
    }

    @Test
    void nonZeroExitCodeIsError() throws Exception {
        // "exit 1" works on both bash and cmd.exe (cmd: "exit /b 1" also works,
        // but "exit 1" causes cmd to close with code 1 too)
        var input = MAPPER.writeValueAsString(
                MAPPER.createObjectNode().put("command", "exit 1"));

        var result = tool.execute(input);

        assertThat(result.isError()).isTrue();
        assertThat(result.output()).contains("exit code: 1");
    }

    @Test
    void customTimeoutIsRespected() throws Exception {
        // A fast command with a custom timeout should succeed
        var node = MAPPER.createObjectNode();
        node.put("command", "echo fast");
        node.put("timeout", 5);
        var input = MAPPER.writeValueAsString(node);

        var result = tool.execute(input);

        assertThat(result.isError()).isFalse();
        assertThat(result.output()).contains("fast");
    }

    @Test
    void workingDirectoryIsUsed() throws Exception {
        // pwd on Unix, cd on Windows — both print the current directory
        var cmd = BashExecTool.IS_WINDOWS ? "cd" : "pwd";
        var input = MAPPER.writeValueAsString(
                MAPPER.createObjectNode().put("command", cmd));

        var result = tool.execute(input);

        assertThat(result.isError()).isFalse();
        assertThat(result.output().trim()).contains(workDir.toRealPath().toString());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void grepNoMatchIsNotError() throws Exception {
        // grep exit code 1 = no match — should NOT be isError
        Files.writeString(workDir.resolve("empty.txt"), "nothing here\n");
        var input = MAPPER.writeValueAsString(
                MAPPER.createObjectNode().put("command", "grep 'NONEXISTENT_xyz_42' empty.txt"));

        var result = tool.execute(input);

        assertThat(result.isError()).isFalse();
        assertThat(result.output()).contains("no match found");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void grepRealErrorIsStillError() throws Exception {
        // grep exit code 2 = real error (e.g. invalid option)
        var input = MAPPER.writeValueAsString(
                MAPPER.createObjectNode().put("command", "grep --invalid-option-xyz foo"));

        var result = tool.execute(input);

        assertThat(result.isError()).isTrue();
        assertThat(result.output()).contains("exit code: 2");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void diffExitCode1IsNotError() throws Exception {
        // diff exit code 1 = files differ — use temp files for portability (no process substitution)
        Files.writeString(workDir.resolve("a.txt"), "a\n");
        Files.writeString(workDir.resolve("b.txt"), "b\n");
        var input = MAPPER.writeValueAsString(
                MAPPER.createObjectNode().put("command", "diff a.txt b.txt"));

        var result = tool.execute(input);

        assertThat(result.isError()).isFalse();
        assertThat(result.output()).contains("files differ");
    }

    @Test
    void isBenignExit1CommandDetectsGrep() {
        assertThat(BashExecTool.isBenignExit1Command("grep -n 'pattern' file.txt")).isTrue();
        assertThat(BashExecTool.isBenignExit1Command("/usr/bin/grep -r foo .")).isTrue();
        assertThat(BashExecTool.isBenignExit1Command("egrep pattern file")).isTrue();
        assertThat(BashExecTool.isBenignExit1Command("fgrep literal file")).isTrue();
        assertThat(BashExecTool.isBenignExit1Command("diff a.txt b.txt")).isTrue();
    }

    @Test
    void isBenignExit1CommandHandlesWindowsPaths() {
        assertThat(BashExecTool.isBenignExit1Command("C:\\Git\\usr\\bin\\grep.exe -r foo .")).isTrue();
        assertThat(BashExecTool.isBenignExit1Command("C:\\Windows\\diff.exe a b")).isTrue();
        assertThat(BashExecTool.isBenignExit1Command("C:\\Python\\python.exe script.py")).isFalse();
    }

    @Test
    void isBenignExit1CommandRejectsOtherCommands() {
        assertThat(BashExecTool.isBenignExit1Command("python script.py")).isFalse();
        assertThat(BashExecTool.isBenignExit1Command("exit 1")).isFalse();
        assertThat(BashExecTool.isBenignExit1Command("ls /nonexistent")).isFalse();
        assertThat(BashExecTool.isBenignExit1Command(null)).isFalse();
        assertThat(BashExecTool.isBenignExit1Command("")).isFalse();
    }

    @Test
    void isBenignExit1CommandHandlesPipelines() {
        // In a pipeline, the shell returns the last command's exit code
        assertThat(BashExecTool.isBenignExit1Command("cat file.txt | grep pattern")).isTrue();
        assertThat(BashExecTool.isBenignExit1Command("echo hello | grep -v world")).isTrue();
        // Last command is not grep — should be false
        assertThat(BashExecTool.isBenignExit1Command("grep pattern file | wc -l")).isFalse();
    }

    @Test
    void isBenignExit1CommandIgnoresQuotedPipes() {
        // Pipe inside quotes is not a pipeline — grep is the only command
        assertThat(BashExecTool.isBenignExit1Command("grep 'a|b' file.txt")).isTrue();
        assertThat(BashExecTool.isBenignExit1Command("grep \"foo|bar\" file.txt")).isTrue();
        // || is logical OR, not pipeline — grep is still the base command
        assertThat(BashExecTool.isBenignExit1Command("grep pattern file || echo fallback")).isTrue();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void sleepPollPatternProducesWarning() throws Exception {
        var input = MAPPER.writeValueAsString(
                MAPPER.createObjectNode().put("command", "sleep 60 && echo done"));

        var result = tool.execute(input);

        assertThat(result.output()).startsWith("Warning: using 'sleep' to wait wastes resources.");
        assertThat(result.output()).contains("done");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void sleepNotAtStartNoWarning() throws Exception {
        var input = MAPPER.writeValueAsString(
                MAPPER.createObjectNode().put("command", "echo hello && sleep 5"));

        var result = tool.execute(input);

        assertThat(result.output()).doesNotContain("Warning");
        assertThat(result.isError()).isFalse();
    }

    @Test
    void sleepPollPatternDetection() {
        assertThat(BashExecTool.SLEEP_POLL_PATTERN.matcher("sleep 120 && curl ...").find()).isTrue();
        assertThat(BashExecTool.SLEEP_POLL_PATTERN.matcher("  sleep 60 && echo hi").find()).isTrue();
        assertThat(BashExecTool.SLEEP_POLL_PATTERN.matcher("echo hello && sleep 5").find()).isFalse();
        assertThat(BashExecTool.SLEEP_POLL_PATTERN.matcher("echo sleep 10 && ok").find()).isFalse();
    }

    @Test
    void inputSchemaHasRequiredCommand() {
        var schema = tool.inputSchema();

        assertThat(schema.has("required")).isTrue();
        assertThat(schema.get("required").toString()).contains("command");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void stdinRedirectedSoReadingStdinReturnsEof() throws Exception {
        // "head -1" reads one line from stdin — with /dev/null it gets EOF immediately
        var node = MAPPER.createObjectNode();
        node.put("command", "head -1");
        node.put("timeout", 5);
        var input = MAPPER.writeValueAsString(node);

        var result = tool.execute(input);

        // Should complete (not hang) — output is empty because stdin was /dev/null
        assertThat(result.output()).isEmpty();
        assertThat(result.isError()).isFalse();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void timeoutActuallyFiresAndKillsChildProcess() throws Exception {
        // Write PID to a file so we can verify the child was killed
        var pidFile = workDir.resolve("sleep.pid");
        var node = MAPPER.createObjectNode();
        node.put("command", "echo $$ > " + pidFile + " && sleep 300");
        node.put("timeout", 1);
        var input = MAPPER.writeValueAsString(node);

        long start = System.currentTimeMillis();
        var result = tool.execute(input);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result.isError()).isTrue();
        assertThat(result.output()).contains("timed out");
        // Should finish within ~3s (1s timeout + overhead), not 300s
        assertThat(elapsed).isLessThan(10_000);

        // Verify the child process is actually dead
        assertThat(Files.exists(pidFile)).as("PID file should have been written").isTrue();
        long pid = Long.parseLong(Files.readString(pidFile).strip());
        Thread.sleep(500); // brief grace period for OS cleanup
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
                .as("child process (PID %d) should be dead after timeout", pid)
                .isFalse();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void cancellationKillsRunningProcess() throws Exception {
        var token = new CancellationToken();
        tool.setCancellationToken(token);

        // Write PID to a file so we can verify the child was killed
        var pidFile = workDir.resolve("cancel.pid");
        var node = MAPPER.createObjectNode();
        node.put("command", "echo $$ > " + pidFile + " && sleep 300");
        node.put("timeout", 60);
        var input = MAPPER.writeValueAsString(node);

        // Run execute in a separate thread, cancel after 500ms
        var resultHolder = new Tool.ToolResult[1];
        var execThread = Thread.ofVirtual().name("cancel-test").start(() -> {
            try {
                resultHolder[0] = tool.execute(input);
            } catch (Exception e) {
                resultHolder[0] = new Tool.ToolResult("Exception: " + e.getMessage(), true);
            }
        });

        Thread.sleep(500);
        token.cancel();
        execThread.join(10_000);

        assertThat(resultHolder[0]).isNotNull();
        assertThat(resultHolder[0].isError()).isTrue();
        assertThat(resultHolder[0].output()).contains("cancelled");

        // Verify the child process is actually dead
        assertThat(Files.exists(pidFile)).as("PID file should have been written").isTrue();
        long pid = Long.parseLong(Files.readString(pidFile).strip());
        Thread.sleep(500); // brief grace period for OS cleanup
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
                .as("child process (PID %d) should be dead after cancellation", pid)
                .isFalse();
    }
}

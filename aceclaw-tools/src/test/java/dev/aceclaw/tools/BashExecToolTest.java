package dev.aceclaw.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.agent.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    void grepNoMatchIsNotError() throws Exception {
        // grep exit code 1 = no match — should NOT be isError
        var input = MAPPER.writeValueAsString(
                MAPPER.createObjectNode().put("command", "grep 'NONEXISTENT_xyz_42' /dev/null"));

        var result = tool.execute(input);

        assertThat(result.isError()).isFalse();
        assertThat(result.output()).contains("no match found");
    }

    @Test
    void grepRealErrorIsStillError() throws Exception {
        // grep exit code 2 = real error (e.g. invalid option)
        var input = MAPPER.writeValueAsString(
                MAPPER.createObjectNode().put("command", "grep --invalid-option-xyz foo"));

        var result = tool.execute(input);

        assertThat(result.isError()).isTrue();
        assertThat(result.output()).contains("exit code: 2");
    }

    @Test
    void diffExitCode1IsNotError() throws Exception {
        // diff exit code 1 = files differ
        var input = MAPPER.writeValueAsString(
                MAPPER.createObjectNode().put("command",
                        "diff <(echo 'a') <(echo 'b')"));

        var result = tool.execute(input);

        assertThat(result.isError()).isFalse();
        assertThat(result.output()).contains("no match found");
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
    void inputSchemaHasRequiredCommand() {
        var schema = tool.inputSchema();

        assertThat(schema.has("required")).isTrue();
        assertThat(schema.get("required").toString()).contains("command");
    }
}

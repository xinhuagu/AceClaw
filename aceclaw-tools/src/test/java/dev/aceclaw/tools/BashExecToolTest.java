package dev.aceclaw.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.agent.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Locale;

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
        var isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("win");
        var cmd = isWindows ? "cd" : "pwd";
        var input = MAPPER.writeValueAsString(
                MAPPER.createObjectNode().put("command", cmd));

        var result = tool.execute(input);

        assertThat(result.isError()).isFalse();
        // Temp dir path should appear in output (realpath may differ, so check contains)
        assertThat(Path.of(result.output().trim()).toRealPath())
                .isEqualTo(workDir.toRealPath());
    }

    @Test
    void inputSchemaHasRequiredCommand() {
        var schema = tool.inputSchema();

        assertThat(schema.has("required")).isTrue();
        assertThat(schema.get("required").toString()).contains("command");
    }
}

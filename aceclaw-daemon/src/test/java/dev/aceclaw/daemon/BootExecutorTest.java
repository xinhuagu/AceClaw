package dev.aceclaw.daemon;

import dev.aceclaw.core.agent.ToolRegistry;
import dev.aceclaw.core.llm.*;
import dev.aceclaw.tools.GlobSearchTool;
import dev.aceclaw.tools.GrepSearchTool;
import dev.aceclaw.tools.ListDirTool;
import dev.aceclaw.tools.ReadFileTool;
import dev.aceclaw.tools.WriteFileTool;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link BootExecutor} — BOOT.md discovery and execution at daemon startup.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class BootExecutorTest {

    @TempDir
    Path tempDir;

    private Path homeDir;
    private Path workDir;
    private MockLlmClient mockLlm;
    private ToolRegistry toolRegistry;

    @BeforeEach
    void setUp() throws IOException {
        homeDir = tempDir.resolve("home");
        workDir = tempDir.resolve("workspace");
        Files.createDirectories(homeDir);
        Files.createDirectories(workDir);

        mockLlm = new MockLlmClient();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new ReadFileTool(workDir));
        toolRegistry.register(new GlobSearchTool(workDir));
        toolRegistry.register(new GrepSearchTool(workDir));
        toolRegistry.register(new ListDirTool(workDir));
        toolRegistry.register(new WriteFileTool(workDir));
    }

    @Test
    @Order(1)
    void noBootMd_returnsNotExecuted() {
        var result = BootExecutor.execute(
                homeDir, workDir, mockLlm, toolRegistry,
                "mock-model", "system prompt", 4096, 0, 30);

        assertThat(result.executed()).isFalse();
        assertThat(result.filesFound()).isZero();
        assertThat(result.summary()).contains("No BOOT.md found");
        assertThat(result.elapsed()).isNotNull();
    }

    @Test
    @Order(2)
    void projectBootMd_discoveredAndExecuted() throws IOException {
        // Create project BOOT.md
        Path projectBootDir = workDir.resolve(".aceclaw");
        Files.createDirectories(projectBootDir);
        Files.writeString(projectBootDir.resolve("BOOT.md"), "List the files in the current directory");

        // Mock LLM response
        mockLlm.enqueueResponse(MockLlmClient.textResponse("Listed files successfully."));

        var result = BootExecutor.execute(
                homeDir, workDir, mockLlm, toolRegistry,
                "mock-model", "system prompt", 4096, 0, 30);

        assertThat(result.executed()).isTrue();
        assertThat(result.filesFound()).isEqualTo(1);
        assertThat(result.summary()).contains("OK");

        // Verify the LLM received the boot prompt
        var requests = mockLlm.capturedRequests();
        assertThat(requests).hasSize(1);
        String userPrompt = extractUserPrompt(requests.getFirst());
        assertThat(userPrompt).contains("[BOOT]");
        assertThat(userPrompt).contains("List the files");
    }

    @Test
    @Order(3)
    void globalBootMd_discoveredAndExecuted() throws IOException {
        // Create global BOOT.md (in homeDir)
        Files.writeString(homeDir.resolve("BOOT.md"), "Check workspace health");

        mockLlm.enqueueResponse(MockLlmClient.textResponse("Workspace is healthy."));

        var result = BootExecutor.execute(
                homeDir, workDir, mockLlm, toolRegistry,
                "mock-model", "system prompt", 4096, 0, 30);

        assertThat(result.executed()).isTrue();
        assertThat(result.filesFound()).isEqualTo(1);
        assertThat(result.summary()).contains("OK");
    }

    @Test
    @Order(4)
    void bothBootMd_bothExecuted() throws IOException {
        // Create both project and global BOOT.md
        Path projectBootDir = workDir.resolve(".aceclaw");
        Files.createDirectories(projectBootDir);
        Files.writeString(projectBootDir.resolve("BOOT.md"), "Project boot task");
        Files.writeString(homeDir.resolve("BOOT.md"), "Global boot task");

        // Enqueue two responses (one for each file)
        mockLlm.enqueueResponse(MockLlmClient.textResponse("Project boot done."));
        mockLlm.enqueueResponse(MockLlmClient.textResponse("Global boot done."));

        var result = BootExecutor.execute(
                homeDir, workDir, mockLlm, toolRegistry,
                "mock-model", "system prompt", 4096, 0, 30);

        assertThat(result.executed()).isTrue();
        assertThat(result.filesFound()).isEqualTo(2);
        assertThat(mockLlm.capturedRequests()).hasSize(2);

        // Verify project was executed first
        var firstPrompt = extractUserPrompt(mockLlm.capturedRequests().get(0));
        assertThat(firstPrompt).contains("Project boot task");

        var secondPrompt = extractUserPrompt(mockLlm.capturedRequests().get(1));
        assertThat(secondPrompt).contains("Global boot task");
    }

    @Test
    @Order(5)
    void writeToolDenied_bootContinues() throws IOException {
        // Create BOOT.md that will trigger a write_file tool call
        Path projectBootDir = workDir.resolve(".aceclaw");
        Files.createDirectories(projectBootDir);
        Files.writeString(projectBootDir.resolve("BOOT.md"), "Write a file to /tmp/test.txt");

        // LLM attempts to use write_file, which should be denied, then responds with text
        mockLlm.enqueueResponse(MockLlmClient.toolUseResponse(
                "I'll write the file.", "tu-1", "write_file",
                "{\"path\":\"/tmp/test.txt\",\"content\":\"hello\"}"));
        mockLlm.enqueueResponse(MockLlmClient.textResponse(
                "The write_file tool was denied during boot. Only read-only tools are available."));

        var result = BootExecutor.execute(
                homeDir, workDir, mockLlm, toolRegistry,
                "mock-model", "system prompt", 4096, 0, 30);

        assertThat(result.executed()).isTrue();
        assertThat(result.filesFound()).isEqualTo(1);
        assertThat(result.summary()).contains("OK");
    }

    @Test
    @Order(6)
    void llmFailure_bootFailsGracefully() throws IOException {
        // Create BOOT.md
        Path projectBootDir = workDir.resolve(".aceclaw");
        Files.createDirectories(projectBootDir);
        Files.writeString(projectBootDir.resolve("BOOT.md"), "Do something");

        // LLM returns an error
        mockLlm.enqueueResponse(MockLlmClient.errorResponse("Service unavailable"));

        var result = BootExecutor.execute(
                homeDir, workDir, mockLlm, toolRegistry,
                "mock-model", "system prompt", 4096, 0, 30);

        // Boot should fail gracefully, not crash
        assertThat(result.filesFound()).isEqualTo(1);
        assertThat(result.summary()).contains("error");
    }

    @Test
    @Order(7)
    void emptyBootMd_skipped() throws IOException {
        Path projectBootDir = workDir.resolve(".aceclaw");
        Files.createDirectories(projectBootDir);
        Files.writeString(projectBootDir.resolve("BOOT.md"), "   ");

        var result = BootExecutor.execute(
                homeDir, workDir, mockLlm, toolRegistry,
                "mock-model", "system prompt", 4096, 0, 30);

        // Empty file found but not executed
        assertThat(result.filesFound()).isEqualTo(1);
        assertThat(result.summary()).contains("OK");
        assertThat(mockLlm.capturedRequests()).isEmpty();
    }

    @Test
    @Order(8)
    void discoveryOrder_projectFirst() throws IOException {
        // Verify discovery returns project before global
        Path projectBootDir = workDir.resolve(".aceclaw");
        Files.createDirectories(projectBootDir);
        Files.writeString(projectBootDir.resolve("BOOT.md"), "project");
        Files.writeString(homeDir.resolve("BOOT.md"), "global");

        var files = BootExecutor.discoverBootFiles(homeDir, workDir);
        assertThat(files).hasSize(2);
        assertThat(files.get(0).toString()).contains(".aceclaw");
        assertThat(files.get(1).toString()).contains("home");
    }

    @Test
    @Order(9)
    void bootPermissionChecker_readToolsAllowed() {
        var checker = new BootExecutor.BootPermissionChecker();

        assertThat(checker.check("read_file", "{}").allowed()).isTrue();
        assertThat(checker.check("glob", "{}").allowed()).isTrue();
        assertThat(checker.check("grep", "{}").allowed()).isTrue();
        assertThat(checker.check("list_directory", "{}").allowed()).isTrue();
    }

    @Test
    @Order(10)
    void bootPermissionChecker_writeToolsDenied() {
        var checker = new BootExecutor.BootPermissionChecker();

        assertThat(checker.check("write_file", "{}").allowed()).isFalse();
        assertThat(checker.check("edit_file", "{}").allowed()).isFalse();
        assertThat(checker.check("bash", "{}").allowed()).isFalse();
        assertThat(checker.check("web_fetch", "{}").allowed()).isFalse();

        // Verify denial message mentions the tool name
        var result = checker.check("bash", "{}");
        assertThat(result.reason()).contains("bash");
        assertThat(result.reason()).contains("not allowed during boot");
    }

    @Test
    @Order(11)
    void nullWorkingDir_onlyGlobalDiscovered() throws IOException {
        Files.writeString(homeDir.resolve("BOOT.md"), "global only");

        var files = BootExecutor.discoverBootFiles(homeDir, null);
        assertThat(files).hasSize(1);
        assertThat(files.getFirst().toString()).contains("home");
    }

    private static String extractUserPrompt(LlmRequest request) {
        return request.messages().stream()
                .filter(m -> m instanceof Message.UserMessage)
                .map(m -> ((Message.UserMessage) m).content().stream()
                        .filter(b -> b instanceof ContentBlock.Text)
                        .map(b -> ((ContentBlock.Text) b).text())
                        .reduce("", (a, b) -> a + b))
                .findFirst()
                .orElse("");
    }
}

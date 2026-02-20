package dev.aceclaw.daemon.heartbeat;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link HeartbeatLoader} — HEARTBEAT.md parsing and discovery.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class HeartbeatLoaderTest {

    @TempDir
    Path tempDir;

    private Path homeDir;
    private Path workDir;

    @BeforeEach
    void setUp() throws IOException {
        homeDir = tempDir.resolve("home");
        workDir = tempDir.resolve("workspace");
        Files.createDirectories(homeDir);
        Files.createDirectories(workDir);
    }

    @Test
    @Order(1)
    void parseValidMultiTaskHeartbeat() {
        String content = """
                # Heartbeat Tasks

                ## Check outdated dependencies
                - schedule: 0 9 * * 1-5
                - timeout: 120
                - tools: bash, read_file

                Check if any dependencies have known vulnerabilities or major updates.

                ## Summarize recent commits
                - schedule: 0 18 * * *
                - timeout: 60

                Summarize today's git commits and list any files with merge conflicts.
                """;

        var tasks = HeartbeatLoader.parse(content);

        assertThat(tasks).hasSize(2);

        var task1 = tasks.get(0);
        assertThat(task1.name()).isEqualTo("Check outdated dependencies");
        assertThat(task1.schedule()).isEqualTo("0 9 * * 1-5");
        assertThat(task1.timeoutSeconds()).isEqualTo(120);
        assertThat(task1.allowedTools()).containsExactlyInAnyOrder("bash", "read_file");
        assertThat(task1.prompt()).contains("Check if any dependencies");

        var task2 = tasks.get(1);
        assertThat(task2.name()).isEqualTo("Summarize recent commits");
        assertThat(task2.schedule()).isEqualTo("0 18 * * *");
        assertThat(task2.timeoutSeconds()).isEqualTo(60);
        assertThat(task2.allowedTools()).isEmpty();
        assertThat(task2.prompt()).contains("Summarize today's git commits");
    }

    @Test
    @Order(2)
    void parseSingleTask() {
        String content = """
                ## Run tests
                - schedule: 0 12 * * *

                Run the full test suite and report any failures.
                """;

        var tasks = HeartbeatLoader.parse(content);

        assertThat(tasks).hasSize(1);
        assertThat(tasks.getFirst().name()).isEqualTo("Run tests");
        assertThat(tasks.getFirst().schedule()).isEqualTo("0 12 * * *");
        assertThat(tasks.getFirst().prompt()).contains("Run the full test suite");
    }

    @Test
    @Order(3)
    void missingSchedule_skipped() {
        String content = """
                ## No schedule task
                - timeout: 60

                This task has no schedule and should be skipped.
                """;

        var tasks = HeartbeatLoader.parse(content);
        assertThat(tasks).isEmpty();
    }

    @Test
    @Order(4)
    void invalidCron_skipped() {
        String content = """
                ## Bad cron
                - schedule: not a valid cron

                This has an invalid cron expression.
                """;

        var tasks = HeartbeatLoader.parse(content);
        assertThat(tasks).isEmpty();
    }

    @Test
    @Order(5)
    void emptyPrompt_skipped() {
        String content = """
                ## Empty prompt task
                - schedule: 0 9 * * *
                """;

        var tasks = HeartbeatLoader.parse(content);
        assertThat(tasks).isEmpty();
    }

    @Test
    @Order(6)
    void optionalTimeoutDefaults() {
        String content = """
                ## Default timeout
                - schedule: 0 9 * * *

                Task with default timeout.
                """;

        var tasks = HeartbeatLoader.parse(content);

        assertThat(tasks).hasSize(1);
        assertThat(tasks.getFirst().timeoutSeconds()).isEqualTo(HeartbeatTask.DEFAULT_TIMEOUT_SECONDS);
    }

    @Test
    @Order(7)
    void optionalToolsParsing() {
        String content = """
                ## Tools task
                - schedule: 0 9 * * *
                - tools: bash, write_file, edit_file

                Task with tools.
                """;

        var tasks = HeartbeatLoader.parse(content);

        assertThat(tasks).hasSize(1);
        assertThat(tasks.getFirst().allowedTools())
                .containsExactlyInAnyOrder("bash", "write_file", "edit_file");
    }

    @Test
    @Order(8)
    void fileDiscovery_projectAndGlobal() throws IOException {
        Path projectDir = workDir.resolve(".aceclaw");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("HEARTBEAT.md"), "## T1\n- schedule: 0 9 * * *\ndo stuff");
        Files.writeString(homeDir.resolve("HEARTBEAT.md"), "## T2\n- schedule: 0 18 * * *\nother stuff");

        var files = HeartbeatLoader.discoverFiles(homeDir, workDir);

        assertThat(files).hasSize(2);
        assertThat(files.get(0).toString()).contains(".aceclaw");
        assertThat(files.get(1).toString()).contains("home");
    }

    @Test
    @Order(9)
    void fileDiscovery_projectOnly() throws IOException {
        Path projectDir = workDir.resolve(".aceclaw");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("HEARTBEAT.md"), "## T1\n- schedule: 0 9 * * *\ndo stuff");

        var files = HeartbeatLoader.discoverFiles(homeDir, workDir);
        assertThat(files).hasSize(1);
    }

    @Test
    @Order(10)
    void fileDiscovery_globalOnly() throws IOException {
        Files.writeString(homeDir.resolve("HEARTBEAT.md"), "## T1\n- schedule: 0 9 * * *\ndo stuff");

        var files = HeartbeatLoader.discoverFiles(homeDir, workDir);
        assertThat(files).hasSize(1);
    }

    @Test
    @Order(11)
    void fileDiscovery_none() {
        var files = HeartbeatLoader.discoverFiles(homeDir, workDir);
        assertThat(files).isEmpty();
    }

    @Test
    @Order(12)
    void nullContent_emptyList() {
        assertThat(HeartbeatLoader.parse(null)).isEmpty();
        assertThat(HeartbeatLoader.parse("")).isEmpty();
        assertThat(HeartbeatLoader.parse("   ")).isEmpty();
    }

    @Test
    @Order(13)
    void loadFromFiles() throws IOException {
        Path projectDir = workDir.resolve(".aceclaw");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("HEARTBEAT.md"), """
                ## Project task
                - schedule: 0 9 * * 1-5

                Check project dependencies.
                """);
        Files.writeString(homeDir.resolve("HEARTBEAT.md"), """
                ## Global task
                - schedule: 0 22 * * *

                Run nightly cleanup.
                """);

        var tasks = HeartbeatLoader.load(homeDir, workDir);

        assertThat(tasks).hasSize(2);
        assertThat(tasks.get(0).name()).isEqualTo("Project task");
        assertThat(tasks.get(1).name()).isEqualTo("Global task");
    }

    @Test
    @Order(14)
    void toolsCsvWithSpaces() {
        String content = """
                ## Spaced tools
                - schedule: 0 9 * * *
                - tools:  bash ,  write_file , edit_file

                Task with spaced CSV tools.
                """;

        var tasks = HeartbeatLoader.parse(content);

        assertThat(tasks).hasSize(1);
        assertThat(tasks.getFirst().allowedTools())
                .containsExactlyInAnyOrder("bash", "write_file", "edit_file");
    }

    @Test
    @Order(15)
    void mixedValidAndInvalid_onlyValidReturned() {
        String content = """
                ## Valid task
                - schedule: 0 9 * * *

                Do something valid.

                ## Invalid no schedule

                Missing schedule entirely.

                ## Another valid
                - schedule: 30 2 * * *

                Another valid task.
                """;

        var tasks = HeartbeatLoader.parse(content);

        assertThat(tasks).hasSize(2);
        assertThat(tasks.get(0).name()).isEqualTo("Valid task");
        assertThat(tasks.get(1).name()).isEqualTo("Another valid");
    }
}

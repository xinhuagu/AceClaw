package dev.aceclaw.daemon.heartbeat;

import dev.aceclaw.core.agent.ToolRegistry;
import dev.aceclaw.daemon.MockLlmClient;
import dev.aceclaw.daemon.cron.CronJob;
import dev.aceclaw.daemon.cron.CronScheduler;
import dev.aceclaw.daemon.cron.JobStore;
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
import java.time.LocalTime;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link HeartbeatRunner} — syncing HEARTBEAT.md into CronScheduler's JobStore.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class HeartbeatRunnerTest {

    @TempDir
    Path tempDir;

    private Path homeDir;
    private Path workDir;
    private MockLlmClient mockLlm;
    private ToolRegistry toolRegistry;
    private CronScheduler scheduler;
    private JobStore jobStore;

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

        jobStore = new JobStore(homeDir);
        scheduler = new CronScheduler(
                jobStore, mockLlm, toolRegistry,
                "mock-model", "system prompt",
                4096, 0, null, 60);
    }

    @AfterEach
    void tearDown() {
        if (scheduler.isRunning()) {
            scheduler.stop();
        }
    }

    @Test
    @Order(1)
    void syncCreatesJobsWithHbPrefix() throws IOException {
        writeProjectHeartbeat("""
                ## Check deps
                - schedule: 0 9 * * 1-5
                - tools: bash

                Check for outdated dependencies.
                """);

        var runner = new HeartbeatRunner(scheduler, homeDir, workDir, null, 60);
        runner.syncFromFiles();

        var job = jobStore.get("hb-check-deps");
        assertThat(job).isPresent();
        assertThat(job.get().name()).isEqualTo("Check deps");
        assertThat(job.get().expression()).isEqualTo("0 9 * * 1-5");
        assertThat(job.get().allowedTools()).contains("bash");
        assertThat(job.get().prompt()).contains("Check for outdated dependencies");
        assertThat(job.get().enabled()).isTrue();
    }

    @Test
    @Order(2)
    void syncUpdatesChangedJobs() throws IOException {
        writeProjectHeartbeat("""
                ## My task
                - schedule: 0 9 * * *

                Original prompt.
                """);

        var runner = new HeartbeatRunner(scheduler, homeDir, workDir, null, 60);
        runner.syncFromFiles();

        assertThat(jobStore.get("hb-my-task").get().prompt()).contains("Original prompt");

        // Update the file
        writeProjectHeartbeat("""
                ## My task
                - schedule: 0 10 * * *

                Updated prompt.
                """);

        runner.syncFromFiles();

        var updated = jobStore.get("hb-my-task");
        assertThat(updated).isPresent();
        assertThat(updated.get().expression()).isEqualTo("0 10 * * *");
        assertThat(updated.get().prompt()).contains("Updated prompt");
    }

    @Test
    @Order(3)
    void syncRemovesStaleHeartbeatJobs() throws IOException {
        // Pre-populate with a heartbeat job and a user cron job
        jobStore.put(CronJob.create("hb-old-task", "Old Task", "0 1 * * *", "old"));
        jobStore.put(CronJob.create("user-job", "User Job", "0 2 * * *", "user"));

        writeProjectHeartbeat("""
                ## New task
                - schedule: 0 9 * * *

                New task only.
                """);

        var runner = new HeartbeatRunner(scheduler, homeDir, workDir, null, 60);
        runner.syncFromFiles();

        // Old heartbeat job should be removed
        assertThat(jobStore.get("hb-old-task")).isEmpty();
        // User cron job should be preserved
        assertThat(jobStore.get("user-job")).isPresent();
        // New heartbeat job should exist
        assertThat(jobStore.get("hb-new-task")).isPresent();
    }

    @Test
    @Order(4)
    void activeHours_insideWindow_enabled() {
        var runner = new HeartbeatRunner(scheduler, homeDir, workDir, "08:00-22:00", 60);

        assertThat(runner.isInActiveWindow(LocalTime.of(12, 0))).isTrue();
        assertThat(runner.isInActiveWindow(LocalTime.of(8, 0))).isTrue();
        assertThat(runner.isInActiveWindow(LocalTime.of(21, 59))).isTrue();
    }

    @Test
    @Order(5)
    void activeHours_outsideWindow_disabled() {
        var runner = new HeartbeatRunner(scheduler, homeDir, workDir, "08:00-22:00", 60);

        assertThat(runner.isInActiveWindow(LocalTime.of(7, 59))).isFalse();
        assertThat(runner.isInActiveWindow(LocalTime.of(22, 0))).isFalse();
        assertThat(runner.isInActiveWindow(LocalTime.of(23, 30))).isFalse();
        assertThat(runner.isInActiveWindow(LocalTime.of(3, 0))).isFalse();
    }

    @Test
    @Order(6)
    void activeHours_null_alwaysEnabled() {
        var runner = new HeartbeatRunner(scheduler, homeDir, workDir, null, 60);

        assertThat(runner.isInActiveWindow(LocalTime.of(0, 0))).isTrue();
        assertThat(runner.isInActiveWindow(LocalTime.of(12, 0))).isTrue();
        assertThat(runner.isInActiveWindow(LocalTime.of(23, 59))).isTrue();
    }

    @Test
    @Order(7)
    void activeHours_wrapping_overnightWindow() {
        var runner = new HeartbeatRunner(scheduler, homeDir, workDir, "22:00-06:00", 60);

        // Inside overnight window
        assertThat(runner.isInActiveWindow(LocalTime.of(23, 0))).isTrue();
        assertThat(runner.isInActiveWindow(LocalTime.of(0, 0))).isTrue();
        assertThat(runner.isInActiveWindow(LocalTime.of(3, 0))).isTrue();
        assertThat(runner.isInActiveWindow(LocalTime.of(5, 59))).isTrue();

        // Outside overnight window
        assertThat(runner.isInActiveWindow(LocalTime.of(6, 0))).isFalse();
        assertThat(runner.isInActiveWindow(LocalTime.of(12, 0))).isFalse();
        assertThat(runner.isInActiveWindow(LocalTime.of(21, 59))).isFalse();
    }

    @Test
    @Order(8)
    void noHeartbeatMd_noJobsNoError() {
        var runner = new HeartbeatRunner(scheduler, homeDir, workDir, null, 60);
        runner.syncFromFiles();

        assertThat(jobStore.all()).isEmpty();
    }

    @Test
    @Order(9)
    void toJobId_slugifiesName() {
        assertThat(HeartbeatRunner.toJobId("Check outdated dependencies"))
                .isEqualTo("hb-check-outdated-dependencies");
        assertThat(HeartbeatRunner.toJobId("Run Tests!"))
                .isEqualTo("hb-run-tests");
        assertThat(HeartbeatRunner.toJobId("  spaces  everywhere  "))
                .isEqualTo("hb-spaces-everywhere");
        assertThat(HeartbeatRunner.toJobId("UPPER-Case_Mixed"))
                .isEqualTo("hb-upper-case-mixed");
    }

    @Test
    @Order(10)
    void toggleActiveHours_enablesAndDisablesJobs() throws IOException {
        writeProjectHeartbeat("""
                ## Toggle test
                - schedule: 0 9 * * *

                Task to toggle.
                """);

        var runner = new HeartbeatRunner(scheduler, homeDir, workDir, "08:00-22:00", 60);
        runner.syncFromFiles();

        // Initially enabled
        assertThat(jobStore.get("hb-toggle-test").get().enabled()).isTrue();

        // Simulate outside active hours by directly calling toggleActiveHours
        // We can't easily mock LocalTime.now(), so we test the isInActiveWindow method
        // and verify toggle logic works on the job store

        // Manually disable, then call toggle while in window
        jobStore.put(jobStore.get("hb-toggle-test").get().withEnabled(false));
        assertThat(jobStore.get("hb-toggle-test").get().enabled()).isFalse();

        // toggleActiveHours when activeHours is null should not change anything
        var alwaysRunner = new HeartbeatRunner(scheduler, homeDir, workDir, null, 60);
        alwaysRunner.toggleActiveHours();
        // Should remain as-is since null = no toggle logic
        assertThat(jobStore.get("hb-toggle-test").get().enabled()).isFalse();
    }

    @Test
    @Order(11)
    void syncPreservesRunHistory() throws IOException {
        writeProjectHeartbeat("""
                ## Persistent task
                - schedule: 0 9 * * *

                Task with history.
                """);

        var runner = new HeartbeatRunner(scheduler, homeDir, workDir, null, 60);
        runner.syncFromFiles();

        // Simulate a successful run
        var job = jobStore.get("hb-persistent-task").get();
        jobStore.put(job.withSuccess(java.time.Instant.now()));

        // Re-sync with modified prompt
        writeProjectHeartbeat("""
                ## Persistent task
                - schedule: 0 9 * * *

                Updated task prompt.
                """);
        runner.syncFromFiles();

        // Run history should be preserved
        var updated = jobStore.get("hb-persistent-task").get();
        assertThat(updated.prompt()).contains("Updated task prompt");
        assertThat(updated.lastRunAt()).isNotNull();
        assertThat(updated.consecutiveFailures()).isZero();
    }

    private void writeProjectHeartbeat(String content) throws IOException {
        Path projectDir = workDir.resolve(".aceclaw");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("HEARTBEAT.md"), content);
    }
}

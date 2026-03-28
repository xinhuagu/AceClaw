package dev.aceclaw.daemon.cron;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class CronToolTest {

    @TempDir
    Path tempDir;

    private JobStore jobStore;
    private CronTool tool;

    @BeforeEach
    void setUp() throws IOException {
        Path homeDir = tempDir.resolve("home");
        Files.createDirectories(homeDir);
        jobStore = new JobStore(homeDir);
        jobStore.load();
        tool = new CronTool(
                jobStore,
                () -> true,
                Clock.fixed(Instant.parse("2026-02-27T08:00:00Z"), ZoneId.of("UTC")));
    }

    @Test
    void addThenListThenStatus() throws Exception {
        var add = tool.execute("""
                {
                  "action":"add",
                  "id":"daily-news",
                  "name":"Daily AI News",
                  "expression":"0 8 * * *",
                  "prompt":"Collect top AI news",
                  "allowedTools":["web_search","read_file"]
                }
                """);
        assertThat(add.isError()).isFalse();
        assertThat(add.output()).contains("Cron job saved: daily-news");

        var list = tool.execute("{\"action\":\"list\"}");
        assertThat(list.isError()).isFalse();
        assertThat(list.output()).contains("daily-news");
        assertThat(list.output()).contains("expr=0 8 * * *");

        var status = tool.execute("{\"action\":\"status\",\"id\":\"daily-news\"}");
        assertThat(status.isError()).isFalse();
        assertThat(status.output()).contains("Job: daily-news");
        assertThat(status.output()).contains("Allowed tools:");
        assertThat(status.output()).contains("read_file");
        assertThat(status.output()).contains("web_search");
    }

    @Test
    void addRejectsInvalidCronExpression() throws Exception {
        var result = tool.execute("""
                {"action":"add","id":"bad","expression":"not a cron","prompt":"x"}
                """);
        assertThat(result.isError()).isTrue();
        assertThat(result.output()).contains("Invalid cron expression");
    }

    @Test
    void addRejectsMissingId() throws Exception {
        var result = tool.execute("""
                {"action":"add","expression":"0 8 * * *","prompt":"x"}
                """);
        assertThat(result.isError()).isTrue();
        assertThat(result.output()).contains("Missing required parameter for add: id");
    }

    @Test
    void addRejectsMissingExpression() throws Exception {
        var result = tool.execute("""
                {"action":"add","id":"daily-news","prompt":"x"}
                """);
        assertThat(result.isError()).isTrue();
        assertThat(result.output()).contains("Missing required parameter for add: expression");
    }

    @Test
    void executeRejectsEmptyInput() throws Exception {
        var blank = tool.execute("");
        assertThat(blank.isError()).isTrue();
        assertThat(blank.output()).contains("Empty or invalid JSON input");

        var nullInput = tool.execute(null);
        assertThat(nullInput.isError()).isTrue();
        assertThat(nullInput.output()).contains("Empty or invalid JSON input");
    }

    @Test
    void removeRejectsHeartbeatJobs() throws Exception {
        jobStore.put(CronJob.create("hb-daily", "Heartbeat", "*/10 * * * *", "x"));
        jobStore.save();

        var result = tool.execute("{\"action\":\"remove\",\"id\":\"hb-daily\"}");
        assertThat(result.isError()).isTrue();
        assertThat(result.output()).contains("Heartbeat jobs");
    }

    @Test
    void statusShowsSchedulerHealth() throws Exception {
        jobStore.put(CronJob.create("j1", "Job 1", "*/5 * * * *", "x"));
        jobStore.put(CronJob.create("j2", "Job 2", "*/10 * * * *", "y").withEnabled(false));
        jobStore.save();

        var result = tool.execute("{\"action\":\"status\"}");
        assertThat(result.isError()).isFalse();
        assertThat(result.output()).contains("Cron scheduler: running");
        assertThat(result.output()).contains("Total jobs: 2");
        assertThat(result.output()).contains("Enabled: 1");
    }

    // =========================================================================
    // Workspace isolation tests
    // =========================================================================

    @Test
    void sameJobIdInDifferentWorkspacesAreIsolated() throws Exception {
        // Add same id "deploy" in two different workspaces
        jobStore.put(CronJob.create("deploy", "Deploy A", "/workspace/a", "0 0 * * *", "deploy A"));
        jobStore.put(CronJob.create("deploy", "Deploy B", "/workspace/b", "0 0 * * *", "deploy B"));
        jobStore.save();

        assertThat(jobStore.size()).isEqualTo(2);
        assertThat(jobStore.get("/workspace/a", "deploy")).isPresent()
                .hasValueSatisfying(j -> assertThat(j.name()).isEqualTo("Deploy A"));
        assertThat(jobStore.get("/workspace/b", "deploy")).isPresent()
                .hasValueSatisfying(j -> assertThat(j.name()).isEqualTo("Deploy B"));
    }

    @Test
    void removeOnlyAffectsTargetWorkspace() throws Exception {
        jobStore.put(CronJob.create("task", "Task A", "/workspace/a", "0 0 * * *", "do A"));
        jobStore.put(CronJob.create("task", "Task B", "/workspace/b", "0 0 * * *", "do B"));
        jobStore.save();

        boolean removed = jobStore.remove("/workspace/a", "task");
        assertThat(removed).isTrue();
        assertThat(jobStore.get("/workspace/a", "task")).isEmpty();
        assertThat(jobStore.get("/workspace/b", "task")).isPresent();
    }

    @Test
    void listShowsWorkspaceJobsPlusGlobalHeartbeats() throws Exception {
        jobStore.put(CronJob.create("hb-check", "Heartbeat", "*/10 * * * *", "check")); // null workspace
        jobStore.put(CronJob.create("my-job", "My Job", "/workspace/a", "0 8 * * *", "run"));
        jobStore.put(CronJob.create("other-job", "Other", "/workspace/b", "0 9 * * *", "run"));
        jobStore.save();

        // Workspace A should see: hb-check (global) + my-job (workspace A), NOT other-job
        var wsAJobs = jobStore.forWorkspace("/workspace/a");
        assertThat(wsAJobs).extracting(CronJob::id).containsExactlyInAnyOrder("hb-check", "my-job");
    }

    @Test
    void addJobViaToolSetsCurrentWorkspace() throws Exception {
        CronTool.setWorkspaceContext("/projects/myapp");
        try {
            var result = tool.execute("""
                {"action":"add","id":"nightly","expression":"0 2 * * *","prompt":"run nightly build"}
                """);
            assertThat(result.isError()).isFalse();

            var job = jobStore.get("/projects/myapp", "nightly");
            assertThat(job).isPresent();
            assertThat(job.get().workspace()).isEqualTo("/projects/myapp");
        } finally {
            CronTool.clearWorkspaceContext();
        }
    }

    @Test
    void removeJobViaToolOnlyRemovesFromCurrentWorkspace() throws Exception {
        jobStore.put(CronJob.create("shared", "Shared A", "/workspace/a", "0 0 * * *", "A"));
        jobStore.put(CronJob.create("shared", "Shared B", "/workspace/b", "0 0 * * *", "B"));
        jobStore.save();

        CronTool.setWorkspaceContext("/workspace/a");
        try {
            var result = tool.execute("{\"action\":\"remove\",\"id\":\"shared\"}");
            assertThat(result.isError()).isFalse();
        } finally {
            CronTool.clearWorkspaceContext();
        }

        // workspace/a's "shared" is gone, workspace/b's remains
        assertThat(jobStore.get("/workspace/a", "shared")).isEmpty();
        assertThat(jobStore.get("/workspace/b", "shared")).isPresent();
    }

    @Test
    void statusOneCanResolveGlobalHeartbeatJob() throws Exception {
        // Heartbeat job with null workspace (global)
        jobStore.put(CronJob.create("hb-check", "Heartbeat Check", "*/5 * * * *", "check health"));
        jobStore.save();

        // Query from a workspace context — should still find the global heartbeat
        CronTool.setWorkspaceContext("/workspace/a");
        try {
            var result = tool.execute("{\"action\":\"status\",\"id\":\"hb-check\"}");
            assertThat(result.isError()).isFalse();
            assertThat(result.output()).contains("hb-check");
        } finally {
            CronTool.clearWorkspaceContext();
        }
    }

    @Test
    void schedulerWriteBackUsesWorkspaceScopedKey() throws Exception {
        // Two jobs with same id in different workspaces
        var jobA = CronJob.create("deploy", "Deploy A", "/ws/a", "0 0 * * *", "deploy A");
        var jobB = CronJob.create("deploy", "Deploy B", "/ws/b", "0 0 * * *", "deploy B");
        jobStore.put(jobA);
        jobStore.put(jobB);
        jobStore.save();

        // Simulate scheduler writing back success for workspace A's job
        var updatedA = jobA.withSuccess(java.time.Instant.now(), "deployed ok");
        jobStore.put(updatedA);

        // Workspace B's job should be unchanged
        var bJob = jobStore.get("/ws/b", "deploy");
        assertThat(bJob).isPresent();
        assertThat(bJob.get().lastOutput()).isNull(); // not overwritten
    }
}

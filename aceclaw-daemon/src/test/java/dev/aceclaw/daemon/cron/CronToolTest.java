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
}

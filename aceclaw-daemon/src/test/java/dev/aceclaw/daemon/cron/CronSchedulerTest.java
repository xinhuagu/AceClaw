package dev.aceclaw.daemon.cron;

import dev.aceclaw.core.agent.ToolRegistry;
import dev.aceclaw.core.llm.*;
import dev.aceclaw.daemon.MockLlmClient;
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
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link CronScheduler}, {@link JobStore}, and {@link CronPermissionChecker}.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CronSchedulerTest {

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

    // -- JobStore tests --

    @Test
    @Order(1)
    void jobStore_loadSaveRoundTrip() throws IOException {
        var store = new JobStore(homeDir);
        var job = CronJob.create("test-job", "Test Job", "*/5 * * * *", "Do something");
        store.put(job);
        store.save();

        // Reload from disk
        var store2 = new JobStore(homeDir);
        store2.load();

        assertThat(store2.size()).isEqualTo(1);
        var loaded = store2.get("test-job");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().name()).isEqualTo("Test Job");
        assertThat(loaded.get().expression()).isEqualTo("*/5 * * * *");
        assertThat(loaded.get().prompt()).isEqualTo("Do something");
    }

    @Test
    @Order(2)
    void jobStore_emptyLoadNoFile() throws IOException {
        var store = new JobStore(homeDir);
        store.load();
        assertThat(store.size()).isZero();
        assertThat(store.all()).isEmpty();
    }

    @Test
    @Order(3)
    void jobStore_enabledFilter() throws IOException {
        var store = new JobStore(homeDir);
        store.put(CronJob.create("job1", "Job 1", "* * * * *", "prompt1"));
        store.put(CronJob.create("job2", "Job 2", "* * * * *", "prompt2").withEnabled(false));
        store.put(CronJob.create("job3", "Job 3", "* * * * *", "prompt3"));

        assertThat(store.all()).hasSize(3);
        assertThat(store.enabled()).hasSize(2);
        assertThat(store.enabled().stream().map(CronJob::id).toList())
                .containsExactly("job1", "job3");
    }

    @Test
    @Order(4)
    void jobStore_remove() throws IOException {
        var store = new JobStore(homeDir);
        store.put(CronJob.create("job1", "Job 1", "* * * * *", "prompt1"));
        assertThat(store.size()).isEqualTo(1);

        assertThat(store.remove("job1")).isTrue();
        assertThat(store.size()).isZero();
        assertThat(store.remove("nonexistent")).isFalse();
    }

    @Test
    @Order(5)
    void jobStore_invalidCronSkippedOnLoad() throws IOException {
        // Write a jobs.json with an invalid expression
        var cronDir = homeDir.resolve("cron");
        Files.createDirectories(cronDir);
        Files.writeString(cronDir.resolve("jobs.json"),
                "[{\"id\":\"bad\",\"name\":\"Bad\",\"expression\":\"invalid\",\"prompt\":\"x\"," +
                "\"allowedTools\":[],\"timeoutSeconds\":300,\"maxIterations\":15," +
                "\"enabled\":true,\"retryBackoff\":[30],\"consecutiveFailures\":0}]");

        var store = new JobStore(homeDir);
        store.load();
        assertThat(store.size()).isZero(); // Invalid expression skipped
    }

    // -- CronJob tests --

    @Test
    @Order(6)
    void cronJob_withSuccess() {
        var job = CronJob.create("j1", "Job", "* * * * *", "prompt");
        var failed = job.withFailure("error 1").withFailure("error 2");
        assertThat(failed.consecutiveFailures()).isEqualTo(2);
        assertThat(failed.lastError()).isEqualTo("error 2");

        var success = failed.withSuccess(Instant.now());
        assertThat(success.consecutiveFailures()).isZero();
        assertThat(success.lastError()).isNull();
        assertThat(success.lastRunAt()).isNotNull();
    }

    @Test
    @Order(7)
    void cronJob_circuitBreaker() {
        var job = CronJob.create("j1", "Job", "* * * * *", "prompt");
        assertThat(job.isCircuitBroken()).isFalse();

        for (int i = 0; i < CronJob.CIRCUIT_BREAKER_THRESHOLD; i++) {
            job = job.withFailure("error");
        }
        assertThat(job.isCircuitBroken()).isTrue();
    }

    // -- CronPermissionChecker tests --

    @Test
    @Order(8)
    void permissionChecker_readOnlyAlwaysAllowed() {
        var checker = new CronPermissionChecker("test-job", Set.of());

        assertThat(checker.check("read_file", "{}").allowed()).isTrue();
        assertThat(checker.check("glob", "{}").allowed()).isTrue();
        assertThat(checker.check("grep", "{}").allowed()).isTrue();
        assertThat(checker.check("list_directory", "{}").allowed()).isTrue();
    }

    @Test
    @Order(9)
    void permissionChecker_writeToolsDeniedByDefault() {
        var checker = new CronPermissionChecker("test-job", Set.of());

        assertThat(checker.check("write_file", "{}").allowed()).isFalse();
        assertThat(checker.check("bash", "{}").allowed()).isFalse();
        assertThat(checker.check("edit_file", "{}").allowed()).isFalse();
    }

    @Test
    @Order(10)
    void permissionChecker_customAllowedTools() {
        var checker = new CronPermissionChecker("test-job", Set.of("bash", "write_file"));

        assertThat(checker.check("bash", "{}").allowed()).isTrue();
        assertThat(checker.check("write_file", "{}").allowed()).isTrue();
        assertThat(checker.check("edit_file", "{}").allowed()).isFalse();
    }

    @Test
    @Order(11)
    void permissionChecker_denialMessageIncludesJobId() {
        var checker = new CronPermissionChecker("my-cron-job", Set.of());
        var result = checker.check("bash", "{}");

        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("my-cron-job");
        assertThat(result.reason()).contains("bash");
    }

    // -- CronScheduler integration tests --

    @Test
    @Order(12)
    void scheduler_startStop() throws IOException {
        var jobStore = new JobStore(homeDir);
        var scheduler = new CronScheduler(
                jobStore, mockLlm, toolRegistry,
                "mock-model", "system prompt",
                4096, 0, null, 60);

        assertThat(scheduler.isRunning()).isFalse();
        scheduler.start();
        assertThat(scheduler.isRunning()).isTrue();
        scheduler.stop();
        assertThat(scheduler.isRunning()).isFalse();
    }

    @Test
    @Order(13)
    void scheduler_executesJobOnTick() throws IOException, InterruptedException {
        var jobStore = new JobStore(homeDir);
        // Create a job that should fire immediately (lastRunAt = EPOCH, expression = every minute)
        var job = CronJob.create("test-exec", "Test Exec", "* * * * *", "Do the thing");
        jobStore.put(job);
        jobStore.save(); // Persist to disk before scheduler.start() calls load()

        mockLlm.enqueueResponse(MockLlmClient.textResponse("Done!"));

        var scheduler = new CronScheduler(
                jobStore, mockLlm, toolRegistry,
                "mock-model", "system prompt",
                4096, 0, null, 1);
        scheduler.start();

        // Wait for tick to execute
        Thread.sleep(2500);
        scheduler.stop();

        // Verify job was executed
        assertThat(mockLlm.capturedRequests()).isNotEmpty();

        // Verify the prompt includes [CRON] prefix
        var request = mockLlm.capturedRequests().getFirst();
        String userPrompt = extractUserPrompt(request);
        assertThat(userPrompt).contains("[CRON]");
        assertThat(userPrompt).contains("Do the thing");

        // Verify job state was updated (success)
        var updatedJob = jobStore.get("test-exec");
        assertThat(updatedJob).isPresent();
        assertThat(updatedJob.get().lastRunAt()).isNotNull();
        assertThat(updatedJob.get().lastError()).isNull();
        assertThat(updatedJob.get().consecutiveFailures()).isZero();
    }

    @Test
    @Order(14)
    void scheduler_circuitBrokenJobSkipped() throws IOException {
        var jobStore = new JobStore(homeDir);
        // Create a job with enough failures to trip circuit breaker
        var job = CronJob.create("broken", "Broken Job", "* * * * *", "prompt");
        for (int i = 0; i < CronJob.CIRCUIT_BREAKER_THRESHOLD; i++) {
            job = job.withFailure("error");
        }
        jobStore.put(job);

        var scheduler = new CronScheduler(
                jobStore, mockLlm, toolRegistry,
                "mock-model", "system prompt",
                4096, 0, null, 60);
        scheduler.start();

        // Trigger tick manually
        scheduler.tick();
        scheduler.stop();

        // No LLM call should have been made
        assertThat(mockLlm.capturedRequests()).isEmpty();
    }

    @Test
    @Order(15)
    void scheduler_disabledJobSkipped() throws IOException {
        var jobStore = new JobStore(homeDir);
        var job = CronJob.create("disabled", "Disabled", "* * * * *", "prompt").withEnabled(false);
        jobStore.put(job);

        var scheduler = new CronScheduler(
                jobStore, mockLlm, toolRegistry,
                "mock-model", "system prompt",
                4096, 0, null, 60);
        scheduler.start();

        scheduler.tick();
        scheduler.stop();

        assertThat(mockLlm.capturedRequests()).isEmpty();
    }

    @Test
    @Order(16)
    void scheduler_failureRecordedOnError() throws IOException {
        var jobStore = new JobStore(homeDir);
        var job = new CronJob("fail-job", "Fail Job", "* * * * *", "prompt",
                Set.of(), 300, 15, true, List.of(), null, null, 0);
        jobStore.put(job);
        jobStore.save(); // Persist to disk before scheduler.start() calls load()

        mockLlm.enqueueResponse(MockLlmClient.errorResponse("LLM is down"));

        var scheduler = new CronScheduler(
                jobStore, mockLlm, toolRegistry,
                "mock-model", "system prompt",
                4096, 0, null, 60);
        scheduler.start();

        scheduler.tick();
        scheduler.stop();

        var updatedJob = jobStore.get("fail-job");
        assertThat(updatedJob).isPresent();
        assertThat(updatedJob.get().consecutiveFailures()).isEqualTo(1);
        assertThat(updatedJob.get().lastError()).isNotNull();
    }

    // -- Helpers --

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

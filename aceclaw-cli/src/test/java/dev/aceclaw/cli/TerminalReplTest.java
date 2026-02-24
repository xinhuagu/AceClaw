package dev.aceclaw.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Tests slash command handling in {@link TerminalRepl}.
 *
 * <p>Since the REPL is tightly coupled to JLine for I/O, we test the
 * slash command dispatch directly via the package-private
 * {@code handleSlashCommand} method.
 */
class TerminalReplTest {

    @TempDir
    Path tempDir;

    private TerminalRepl repl;
    private StringWriter outputBuffer;
    private PrintWriter out;

    @BeforeEach
    void setUp() {
        // DaemonClient is needed but slash commands that don't touch it won't fail
        // We pass null — commands that need it (/tools, /compact) will NPE,
        // but we only test commands that don't require the client
        var sessionInfo = new TerminalRepl.SessionInfo("1.0.0", "claude-sonnet-4-5-20250929",
                "/tmp/project", 200_000, "main");
        repl = new TerminalRepl(null, "test-session", sessionInfo);
        outputBuffer = new StringWriter();
        out = new PrintWriter(outputBuffer);
    }

    @Test
    void help_producesHelpOutput() {
        boolean shouldExit = repl.handleSlashCommand(out, "/help", null);
        assertThat(shouldExit).isFalse();
        String output = outputBuffer.toString();
        assertThat(output).contains("Available commands");
        assertThat(output).contains("/help");
        assertThat(output).contains("/exit");
        assertThat(output).contains("/model");
        assertThat(output).contains("/tools");
        assertThat(output).contains("/tasks");
        assertThat(output).contains("/bg");
        assertThat(output).contains("/fg");
        assertThat(output).contains("/cancel");
    }

    @Test
    void questionMark_isHelpAlias() {
        boolean shouldExit = repl.handleSlashCommand(out, "/?", null);
        assertThat(shouldExit).isFalse();
        assertThat(outputBuffer.toString()).contains("Available commands");
    }

    @Test
    void clear_returnsFalse() {
        boolean shouldExit = repl.handleSlashCommand(out, "/clear", null);
        assertThat(shouldExit).isFalse();
        // Outputs ANSI clear screen sequence
        assertThat(outputBuffer.toString()).contains("\033[2J");
    }

    @Test
    void modelWithNoArg_showsNotConnectedWhenNullClient() {
        boolean shouldExit = repl.handleSlashCommand(out, "/model", null);
        assertThat(shouldExit).isFalse();
        assertThat(outputBuffer.toString()).contains("Not connected to daemon");
    }

    @Test
    void modelWithArg_showsNotConnectedWhenNullClient() {
        boolean shouldExit = repl.handleSlashCommand(out, "/model gpt-4o", null);
        assertThat(shouldExit).isFalse();
        assertThat(outputBuffer.toString()).contains("Not connected to daemon");
    }

    @Test
    void exit_returnsTrue() {
        boolean shouldExit = repl.handleSlashCommand(out, "/exit", null);
        assertThat(shouldExit).isTrue();
        assertThat(outputBuffer.toString()).contains("Goodbye");
    }

    @Test
    void quit_returnsTrue() {
        boolean shouldExit = repl.handleSlashCommand(out, "/quit", null);
        assertThat(shouldExit).isTrue();
    }

    @Test
    void unknownCommand_showsWarning() {
        boolean shouldExit = repl.handleSlashCommand(out, "/foo", null);
        assertThat(shouldExit).isFalse();
        assertThat(outputBuffer.toString()).contains("Unknown command");
    }

    @Test
    void status_showsSessionInfo() {
        boolean shouldExit = repl.handleSlashCommand(out, "/status", null);
        assertThat(shouldExit).isFalse();
        String output = outputBuffer.toString();
        assertThat(output).contains("Session Status");
        assertThat(output).contains("claude-sonnet-4-5-20250929");
        assertThat(output).contains("/tmp/project");
    }

    @Test
    void tasks_showsNoTasks() {
        boolean shouldExit = repl.handleSlashCommand(out, "/tasks", null);
        assertThat(shouldExit).isFalse();
        assertThat(outputBuffer.toString()).contains("No tasks");
    }

    @Test
    void bg_showsNoForegroundTask() {
        boolean shouldExit = repl.handleSlashCommand(out, "/bg", null);
        assertThat(shouldExit).isFalse();
        assertThat(outputBuffer.toString()).contains("No foreground task");
    }

    @Test
    void cancel_showsNoForegroundTask() {
        boolean shouldExit = repl.handleSlashCommand(out, "/cancel", null);
        assertThat(shouldExit).isFalse();
        assertThat(outputBuffer.toString()).contains("No foreground task");
    }

    @Test
    void replayStatusSummary_emptyFile_returnsPending() throws Exception {
        var statusRepl = newReplForProject(tempDir);
        Path replay = tempDir.resolve(".aceclaw/metrics/continuous-learning/replay-latest.json");
        Files.createDirectories(replay.getParent());
        Files.writeString(replay, "");

        String summary = (String) invokePrivate(statusRepl, "replayStatusSummary",
                new Class<?>[]{Path.class}, replay);
        assertThat(summary).isEqualTo("pending");
    }

    @Test
    void releaseStatusSummary_emptyFile_returnsNone() throws Exception {
        var statusRepl = newReplForProject(tempDir);
        Path state = tempDir.resolve(".aceclaw/metrics/continuous-learning/skill-release-state.json");
        Files.createDirectories(state.getParent());
        Files.writeString(state, "");

        String summary = (String) invokePrivate(statusRepl, "releaseStatusSummary",
                new Class<?>[]{Path.class}, state);
        assertThat(summary).isEqualTo("none");
    }

    @Test
    void candidateStatusSummary_malformedJson_returnsReadError() throws Exception {
        var statusRepl = newReplForProject(tempDir);
        Path candidates = tempDir.resolve(".aceclaw/memory/candidates.jsonl");
        Files.createDirectories(candidates.getParent());
        Files.writeString(candidates, "{bad json}\n");

        String summary = (String) invokePrivate(statusRepl, "candidateStatusSummary",
                new Class<?>[]{Path.class}, candidates);
        assertThat(summary).isEqualTo("read-error");
    }

    @Test
    void buildContinuousLearningStatusLine_concurrentAccess_isStable() throws Exception {
        var statusRepl = newReplForProject(tempDir);
        writeLearningArtifacts(tempDir);

        var pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        return (String) invokePrivate(statusRepl, "buildContinuousLearningStatusLine",
                                new Class<?>[]{});
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
            for (Future<String> future : futures) {
                String line = future.get(5, TimeUnit.SECONDS);
                assertThat(line).isNotBlank();
                assertThat(line).contains("learning");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static TerminalRepl newReplForProject(Path project) {
        var sessionInfo = new TerminalRepl.SessionInfo(
                "1.0.0", "claude-sonnet-4-5-20250929", project.toString(), 200_000, "main");
        return new TerminalRepl(null, "test-session", sessionInfo);
    }

    private static Object invokePrivate(
            TerminalRepl repl, String method, Class<?>[] types, Object... args) throws Exception {
        var m = TerminalRepl.class.getDeclaredMethod(method, types);
        m.setAccessible(true);
        return m.invoke(repl, args);
    }

    private static void writeLearningArtifacts(Path root) throws Exception {
        Path replay = root.resolve(".aceclaw/metrics/continuous-learning/replay-latest.json");
        Path release = root.resolve(".aceclaw/metrics/continuous-learning/skill-release-state.json");
        Path candidates = root.resolve(".aceclaw/memory/candidates.jsonl");
        Files.createDirectories(replay.getParent());
        Files.createDirectories(candidates.getParent());

        Files.writeString(replay, """
                {
                  "metrics": {
                    "token_estimation_error_ratio_max": {"value": 0.12, "status": "measured"}
                  }
                }
                """);
        Files.writeString(release, """
                {
                  "releases": [
                    {"stage":"SHADOW"},
                    {"stage":"CANARY"},
                    {"stage":"ACTIVE"}
                  ]
                }
                """);
        Files.writeString(candidates, """
                {"id":"1","state":"PROMOTED"}
                {"id":"2","state":"DEMOTED"}
                {"id":"3","state":"SHADOW"}
                """);
    }
}

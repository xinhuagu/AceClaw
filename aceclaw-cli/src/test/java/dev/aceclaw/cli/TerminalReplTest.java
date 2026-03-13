package dev.aceclaw.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
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
        assertThat(output).contains("/project");
        assertThat(output).contains("/skills");
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
    void project_showsSessionProject() {
        boolean shouldExit = repl.handleSlashCommand(out, "/project", null);
        assertThat(shouldExit).isFalse();
        String output = outputBuffer.toString();
        assertThat(output).contains("Session Project");
        assertThat(output).contains("/tmp/project");
    }

    @Test
    void skillsDrafts_listsGeneratedDrafts() throws Exception {
        var statusRepl = newReplForProject(tempDir);
        writeSkillDraftArtifacts(tempDir);

        boolean shouldExit = statusRepl.handleSlashCommand(out, "/skills drafts", null);

        assertThat(shouldExit).isFalse();
        String output = outputBuffer.toString();
        assertThat(output).contains("Skill Drafts");
        assertThat(output).contains("retry-safe");
        assertThat(output).contains("candidate=cand-123");
        assertThat(output).contains("verdict=hold");
        assertThat(output).contains("release=shadow");
        assertThat(output).contains("manual-review=yes");
    }

    @Test
    void skillsInspect_showsDraftDetails() throws Exception {
        var statusRepl = newReplForProject(tempDir);
        writeSkillDraftArtifacts(tempDir);

        boolean shouldExit = statusRepl.handleSlashCommand(out, "/skills inspect retry-safe", null);

        assertThat(shouldExit).isFalse();
        String output = outputBuffer.toString();
        assertThat(output).contains("Skill Draft");
        assertThat(output).contains("retry-safe");
        assertThat(output).contains("cand-123");
        assertThat(output).contains("manual review needed");
        assertThat(output).contains("STATIC_ALLOWED_TOOLS_POLICY_VIOLATION");
    }

    @Test
    void skillsDraftsPending_filtersToManualReviewItems() throws Exception {
        var statusRepl = newReplForProject(tempDir);
        writeSkillDraftArtifacts(tempDir);
        writeActiveSkillDraftArtifacts(tempDir);

        boolean shouldExit = statusRepl.handleSlashCommand(out, "/skills drafts pending", null);

        assertThat(shouldExit).isFalse();
        String output = outputBuffer.toString();
        assertThat(output).contains("Pending Skill Drafts");
        assertThat(output).contains("retry-safe");
        assertThat(output).doesNotContain("fully-active");
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
    void replayStatusSummary_coreMetricsPresent_returnsCompactCoreSummary() throws Exception {
        var statusRepl = newReplForProject(tempDir);
        Path replay = tempDir.resolve(".aceclaw/metrics/continuous-learning/replay-latest.json");
        Files.createDirectories(replay.getParent());
        Files.writeString(replay, """
                {
                  "metrics": {
                    "promotion_rate": {"value": 0.21, "status": "measured"},
                    "demotion_rate": {"value": 0.07, "status": "measured"},
                    "anti_pattern_false_positive_rate": {"value": 0.04, "status": "measured"},
                    "rollback_rate": {"value": 0.02, "status": "measured"}
                  }
                }
                """);

        String summary = (String) invokePrivate(statusRepl, "replayStatusSummary",
                new Class<?>[]{Path.class}, replay);
        assertThat(summary).isEqualTo("measured:p=0.21,d=0.07,fp=0.04,r=0.02");
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
    void candidateStatusSummary_includesShadowCount() throws Exception {
        var statusRepl = newReplForProject(tempDir);
        Path candidates = tempDir.resolve(".aceclaw/memory/candidates.jsonl");
        Files.createDirectories(candidates.getParent());
        Files.writeString(candidates, """
                {"id":"1","state":"PROMOTED"}
                {"id":"2","state":"DEMOTED"}
                {"id":"3","state":"SHADOW"}
                """);

        String summary = (String) invokePrivate(statusRepl, "candidateStatusSummary",
                new Class<?>[]{Path.class}, candidates);
        assertThat(summary).isEqualTo("3(p:1,s:1,d:1)");
    }

    @Test
    void skillDraftStatusSummary_includesVerdictCounts() throws Exception {
        var statusRepl = newReplForProject(tempDir);
        writeSkillDraftArtifacts(tempDir);

        String summary = (String) invokePrivate(statusRepl, "skillDraftStatusSummary",
                new Class<?>[]{Path.class}, tempDir);
        assertThat(summary).isEqualTo("1(p:0,h:1,b:0,n:0)");
    }

    @Test
    void renderSkillDraftEventNotice_enqueuesNotice() throws Exception {
        var statusRepl = newReplForProject(tempDir);
        var mapper = new ObjectMapper();
        ObjectNode event = mapper.createObjectNode();
        event.put("type", "validation_changed");
        event.put("trigger", "draft-generated");
        event.put("skillName", "retry-safe");
        event.put("draftPath", ".aceclaw/skills-drafts/retry-safe/SKILL.md");
        event.put("candidateId", "cand-123");
        event.put("verdict", "hold");
        event.put("releaseStage", "shadow");
        event.put("paused", false);
        var reasons = mapper.createArrayNode();
        reasons.add("STATIC_ALLOWED_TOOLS_POLICY_VIOLATION: allowed-tools includes unsafe or malformed values");
        event.set("reasons", reasons);

        Object parsed = invokePrivate(statusRepl, "parseSkillDraftEvent",
                new Class<?>[]{com.fasterxml.jackson.databind.JsonNode.class}, event);
        invokePrivate(statusRepl, "renderSkillDraftEventNotice",
                new Class<?>[]{parsed.getClass()}, parsed);
        invokePrivate(statusRepl, "drainUiEventsIntoState", new Class<?>[]{});

        @SuppressWarnings("unchecked")
        var notices = (java.util.Deque<Object>) getPrivateField(statusRepl, "uiNoticeBuffer");
        assertThat(notices).isNotEmpty();
        assertThat(notices.getLast().toString()).contains("retry-safe");
        assertThat(notices.getLast().toString()).contains("STATIC_ALLOWED_TOOLS_POLICY_VIOLATION");
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

    @Test
    void clampAttributedLine_handlesAnsiAndTruncation() throws Exception {
        var input = org.jline.utils.AttributedString.fromAnsi(
                "prefix\u001B[32mmiddle\u001B[0m");
        var clamped = (org.jline.utils.AttributedString) invokePrivate(
                repl,
                "clampAttributedLine",
                new Class<?>[]{org.jline.utils.AttributedString.class, int.class, int.class},
                input, 80, 80);
        assertThat(clamped.toAnsi()).isNotBlank();
        assertThat(clamped.columnLength()).isEqualTo(input.columnLength());
    }

    @Test
    void normalizeStatusPanelField_collapsesMultilineAndCapsLength() throws Exception {
        String raw = "line1\nline2\tline3 " + "x".repeat(300);

        String normalized = (String) invokePrivate(
                repl,
                "normalizeStatusPanelField",
                new Class<?>[]{String.class},
                raw);

        assertThat(normalized).doesNotContain("\n").doesNotContain("\r").doesNotContain("\t");
        assertThat(normalized).startsWith("line1 line2 line3 ");
        // Truncated by display width (80 columns), not char count
        assertThat(TerminalTheme.displayWidth(normalized)).isLessThanOrEqualTo(80);
        assertThat(normalized).endsWith("...");
    }

    @Test
    void normalizeStatusPanelField_cjkTruncatedByDisplayWidth() throws Exception {
        // 50 CJK chars = 100 display columns, should be truncated to 80
        String raw = "\u4e2d\u6587".repeat(50);

        String normalized = (String) invokePrivate(
                repl,
                "normalizeStatusPanelField",
                new Class<?>[]{String.class},
                raw);

        assertThat(TerminalTheme.displayWidth(normalized)).isLessThanOrEqualTo(80);
        assertThat(normalized).endsWith("...");
        // char count should be less than 50 CJK chars since we truncated by display width
        int contentLen = normalized.length() - 3; // minus "..."
        assertThat(contentLen).isLessThan(50);
    }

    /**
     * Regression test for issue #185: renderSchedulerEventNote for a completed
     * cron event with a markdown table summary should not produce trailing blank
     * lines that push the prompt down via printAbove.
     */
    @Test
    void renderSchedulerEventNote_completedWithTableSummary_noTrailingBlankLines() throws Exception {
        var mapper = new ObjectMapper();
        ObjectNode event = mapper.createObjectNode();
        event.put("type", "completed");
        event.put("jobId", "test-job");
        event.put("summary", """
                ## Results

                | Status | Count |
                |--------|-------|
                | OK     | 5     |
                | FAIL   | 0     |
                """);

        String result = (String) invokePrivate(repl, "renderSchedulerEventNote",
                new Class<?>[]{com.fasterxml.jackson.databind.JsonNode.class}, event);

        assertThat(result).isNotNull();
        // The rendered note should not end with multiple blank lines
        String[] lines = result.split("\n");
        // Count trailing empty lines
        int trailingEmpty = 0;
        for (int i = lines.length - 1; i >= 0; i--) {
            if (lines[i].isBlank()) {
                trailingEmpty++;
            } else {
                break;
            }
        }
        assertThat(trailingEmpty)
                .as("Completed cron event should not have excessive trailing blank lines")
                .isLessThanOrEqualTo(1);
    }

    @Test
    void buildStatusString_permissionModalShowsQueuedCount() throws Exception {
        var bridge = (PermissionBridge) getPrivateField(repl, "permissionBridge");
        var req = new PermissionBridge.PermissionRequest("7", "bash", "run script", "req-active");

        var pool = Executors.newSingleThreadExecutor();
        try {
            Future<PermissionBridge.PermissionAnswer> future = pool.submit(() -> bridge.requestPermission(req));
            for (int i = 0; i < 20 && bridge.pendingCount() < 1; i++) {
                Thread.sleep(20);
            }

            setPrivateField(repl, "activePermissionRequestId", "req-active");
            setPrivateEnumField(repl, "consoleMode", "PERMISSION_MODAL");

            String status = (String) invokePrivate(repl, "buildStatusString",
                    new Class<?>[]{});
            assertThat(status).contains("permission-modal");
            assertThat(status).contains("(+1 queued)");

            bridge.submitAnswer("req-active", new PermissionBridge.PermissionAnswer(false, false));
            future.get(2, TimeUnit.SECONDS);
        } finally {
            releasePendingPermissions(bridge);
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

    private static Object getPrivateField(TerminalRepl repl, String name) throws Exception {
        Field field = TerminalRepl.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(repl);
    }

    private static void setPrivateField(TerminalRepl repl, String name, Object value) throws Exception {
        Field field = TerminalRepl.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(repl, value);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void setPrivateEnumField(TerminalRepl repl, String name, String enumValue) throws Exception {
        Field field = TerminalRepl.class.getDeclaredField(name);
        field.setAccessible(true);
        Class<?> type = field.getType();
        field.set(repl, Enum.valueOf((Class<? extends Enum>) type.asSubclass(Enum.class), enumValue));
    }

    private static void releasePendingPermissions(PermissionBridge bridge) {
        for (var req : bridge.pendingSnapshot()) {
            bridge.submitAnswer(req.requestId(), new PermissionBridge.PermissionAnswer(false, false));
        }
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
                    "promotion_rate": {"value": 0.25, "status": "measured"},
                    "demotion_rate": {"value": 0.05, "status": "measured"},
                    "anti_pattern_false_positive_rate": {"value": 0.03, "status": "measured"},
                    "rollback_rate": {"value": 0.01, "status": "measured"},
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

    private static void writeSkillDraftArtifacts(Path root) throws Exception {
        Path draft = root.resolve(".aceclaw/skills-drafts/retry-safe/SKILL.md");
        Path validation = root.resolve(".aceclaw/metrics/continuous-learning/skill-draft-validation-audit.jsonl");
        Path release = root.resolve(".aceclaw/metrics/continuous-learning/skill-release-state.json");
        Files.createDirectories(draft.getParent());
        Files.createDirectories(validation.getParent());

        Files.writeString(draft, """
                ---
                name: "retry-safe"
                description: "Retry safe external calls"
                allowed-tools: ["bash"]
                disable-model-invocation: true
                source-candidate-id: "cand-123"
                ---

                # Draft Skill
                """);
        Files.writeString(validation, """
                {"draftPath":".aceclaw/skills-drafts/retry-safe/SKILL.md","verdict":"hold","reasons":[{"code":"STATIC_ALLOWED_TOOLS_POLICY_VIOLATION","message":"allowed-tools includes unsafe or malformed values"}]}
                """);
        Files.writeString(release, """
                {
                  "releases": [
                    {"skillName":"retry-safe","stage":"shadow","paused":false}
                  ]
                }
                """);
    }

    private static void writeActiveSkillDraftArtifacts(Path root) throws Exception {
        Path draft = root.resolve(".aceclaw/skills-drafts/fully-active/SKILL.md");
        Path release = root.resolve(".aceclaw/metrics/continuous-learning/skill-release-state.json");
        Files.createDirectories(draft.getParent());
        Files.writeString(draft, """
                ---
                name: "fully-active"
                description: "Already active"
                disable-model-invocation: true
                source-candidate-id: "cand-999"
                ---

                # Draft Skill
                """);
        Files.writeString(release, """
                {
                  "releases": [
                    {"skillName":"retry-safe","stage":"shadow","paused":false},
                    {"skillName":"fully-active","stage":"active","paused":false}
                  ]
                }
                """);
    }
}

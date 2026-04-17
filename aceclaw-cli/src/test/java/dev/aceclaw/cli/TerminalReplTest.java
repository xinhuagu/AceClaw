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
        assertThat(output).contains("/context");
        assertThat(output).contains("/learning");
        assertThat(output).contains("/learning signals");
        assertThat(output).contains("/learning reviews");
        assertThat(output).contains("/learning review <action> <type> <id> [note]");
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
        assertThat(output).contains("Pressure:");
        assertThat(output).contains("Peak:");
        assertThat(output).contains("Pruning:");
        assertThat(output).contains("trend=");
    }

    @Test
    void contextWithNoClient_showsNotConnectedWhenNullClient() {
        boolean shouldExit = repl.handleSlashCommand(out, "/context list", null);
        assertThat(shouldExit).isFalse();
        assertThat(outputBuffer.toString()).contains("Not connected to daemon");
    }

    @Test
    void learningWithNoClient_showsNotConnectedWhenNullClient() {
        boolean shouldExit = repl.handleSlashCommand(out, "/learning", null);
        assertThat(shouldExit).isFalse();
        assertThat(outputBuffer.toString()).contains("Not connected to daemon");
    }

    @Test
    void learningSignalsWithNoClient_showsNotConnectedWhenNullClient() {
        boolean shouldExit = repl.handleSlashCommand(out, "/learning signals", null);
        assertThat(shouldExit).isFalse();
        assertThat(outputBuffer.toString()).contains("Not connected to daemon");
    }

    @Test
    void learningReviewsWithNoClient_showsNotConnectedWhenNullClient() {
        boolean shouldExit = repl.handleSlashCommand(out, "/learning reviews", null);
        assertThat(shouldExit).isFalse();
        assertThat(outputBuffer.toString()).contains("Not connected to daemon");
    }

    @Test
    void learningReviewApplyWithNoClient_showsNotConnectedWhenNullClient() {
        boolean shouldExit = repl.handleSlashCommand(out, "/learning review suppress trend foo too-noisy", null);
        assertThat(shouldExit).isFalse();
        assertThat(outputBuffer.toString()).contains("Not connected to daemon");
    }

    @Test
    void renderLearningSignals_keepsFullTargetIdentifierCopyable() throws Exception {
        var mapper = new ObjectMapper();
        var signals = mapper.createArrayNode();
        var signal = mapper.createObjectNode();
        signal.put("targetType", "runtime_skill");
        signal.put("targetId", "retry-flow-with-very-long-identifier");
        signal.put("summary", "Retry flow learned from repeated successful repair sequence.");
        signal.put("reviewAction", "pin");
        signals.add(signal);

        invokePrivate(repl, "renderLearningSignals",
                new Class<?>[]{PrintWriter.class, com.fasterxml.jackson.databind.JsonNode.class},
                out, signals);

        String output = outputBuffer.toString();
        assertThat(output).contains("runtime_skill:retry-flow-with-very-long-identifier");
        assertThat(output).contains("[pin]");
    }

    @Test
    void renderContextList_showsPromptSectionsAndCompactionSummary() throws Exception {
        var mapper = new ObjectMapper();
        var root = mapper.createObjectNode();
        root.put("totalChars", 24000);
        root.put("estimatedTokens", 6000);
        root.put("systemPromptSharePct", 12.0);
        var focus = mapper.createObjectNode();
        focus.put("querySummary", "Update src/main/App.java and verify AppService behavior");
        var focusFiles = mapper.createArrayNode();
        focusFiles.add("src/main/App.java");
        focus.set("activeFilePaths", focusFiles);
        var focusSymbols = mapper.createArrayNode();
        focusSymbols.add("AppService");
        focus.set("activeSymbols", focusSymbols);
        var focusPlan = mapper.createArrayNode();
        focusPlan.add("code change requested");
        focusPlan.add("verification requested");
        focus.set("planSignals", focusPlan);
        root.set("requestFocus", focus);
        var budget = mapper.createObjectNode();
        budget.put("maxTotalChars", 28000);
        budget.put("maxPerTierChars", 8000);
        root.set("budget", budget);
        var activePaths = mapper.createArrayNode();
        activePaths.add("src/main/App.java\u001B]2;pwnd\u0007");
        activePaths.add("src/test/AppTest.java");
        root.set("activeFilePaths", activePaths);
        var truncated = mapper.createArrayNode();
        truncated.add("skills\u001B]2;pwnd\u0007");
        root.set("truncatedSectionKeys", truncated);
        var sections = mapper.createArrayNode();
        var baseSection = mapper.createObjectNode();
        baseSection.put("key", "\u001B[31mbase\u001B[0m");
        baseSection.put("sourceType", "base");
        baseSection.put("scopeType", "always-on");
        baseSection.put("inclusionReason", "Core operating policy is always included.");
        baseSection.put("priority", 95);
        baseSection.put("protected", true);
        baseSection.put("originalChars", 12000);
        baseSection.put("finalChars", 12000);
        baseSection.put("estimatedTokens", 3000);
        baseSection.put("included", true);
        baseSection.put("truncated", false);
        baseSection.set("evidence", mapper.createArrayNode());
        sections.add(baseSection);

        var taskFocusSection = mapper.createObjectNode();
        taskFocusSection.put("key", "task-focus");
        taskFocusSection.put("sourceType", "task-focus");
        taskFocusSection.put("scopeType", "task-local");
        taskFocusSection.put("inclusionReason", "Current request focus was derived from the query, active files, and symbols.");
        taskFocusSection.put("priority", 89);
        taskFocusSection.put("protected", false);
        taskFocusSection.put("originalChars", 800);
        taskFocusSection.put("finalChars", 800);
        taskFocusSection.put("estimatedTokens", 200);
        taskFocusSection.put("included", true);
        taskFocusSection.put("truncated", false);
        taskFocusSection.set("evidence", mapper.createArrayNode()
                .add("files=src/main/App.java")
                .add("symbols=AppService"));
        sections.add(taskFocusSection);

        var skillsSection = mapper.createObjectNode();
        skillsSection.put("key", "skills");
        skillsSection.put("sourceType", "skills");
        skillsSection.put("scopeType", "always-on");
        skillsSection.put("inclusionReason", "Available skills are exposed so the agent can choose reusable workflows.");
        skillsSection.put("priority", 58);
        skillsSection.put("protected", false);
        skillsSection.put("originalChars", 10000);
        skillsSection.put("finalChars", 4000);
        skillsSection.put("estimatedTokens", 1000);
        skillsSection.put("included", true);
        skillsSection.put("truncated", true);
        skillsSection.set("evidence", mapper.createArrayNode().add("symbols=AppService"));
        sections.add(skillsSection);

        var learnedSection = mapper.createObjectNode();
        learnedSection.put("key", "memory:Auto-Memory");
        learnedSection.put("sourceType", "learned-signals");
        learnedSection.put("scopeType", "always-on");
        learnedSection.put("inclusionReason", "Learned signals were ranked against the current request hint.");
        learnedSection.put("priority", 60);
        learnedSection.put("protected", false);
        learnedSection.put("originalChars", 2000);
        learnedSection.put("finalChars", 1500);
        learnedSection.put("estimatedTokens", 375);
        learnedSection.put("included", true);
        learnedSection.put("truncated", true);
        learnedSection.set("evidence", mapper.createArrayNode().add("query=Update src/main/App.java"));
        sections.add(learnedSection);
        root.set("sections", sections);

        var monitor = (ContextMonitor) getPrivateField(repl, "contextMonitor");
        monitor.recordStreamingUsage(175_000);
        monitor.recordCompaction(175_000, 80_000, "SUMMARIZED");

        invokePrivate(repl, "renderContextList",
                new Class<?>[]{PrintWriter.class, com.fasterxml.jackson.databind.JsonNode.class},
                out, root);

        String output = outputBuffer.toString();
        String plain = stripAnsi(output);
        assertThat(output).doesNotContain("\u001B]2;pwnd");
        assertThat(output).doesNotContain("\u0007");
        assertThat(plain).contains("Context Overview");
        assertThat(plain).contains("System prompt:");
        assertThat(plain).contains("Window share:");
        assertThat(plain).contains("Active paths:");
        assertThat(plain).contains("Truncated:");
        assertThat(plain).contains("Request Focus");
        assertThat(plain).contains("Query:");
        assertThat(plain).contains("Files:");
        assertThat(plain).contains("Symbols:");
        assertThat(plain).contains("Plan:");
        assertThat(plain).contains("Last compact:");
        assertThat(plain).contains("Saved:");
        assertThat(plain).contains("Injection Cost");
        assertThat(plain).contains("Effectiveness");
        assertThat(plain).contains("Memory reuse:");
        assertThat(plain).contains("Learned fit:");
        assertThat(plain).contains("src/main/App.java");
        assertThat(plain).contains("base");
        assertThat(plain).contains("task-local / task-focus");
        assertThat(plain).contains("skills");
        assertThat(plain).contains("learned-signals");
        assertThat(plain).contains("truncated");
        assertThat(plain).contains("protected");
        assertThat(plain).contains("Use /context detail <key>");
        assertThat(plain).doesNotContain("pwnd");
    }

    @Test
    void renderContextDetail_showsSectionContent() throws Exception {
        var mapper = new ObjectMapper();
        var root = mapper.createObjectNode();
        var detail = mapper.createObjectNode();
        detail.put("key", "rules\u001B]2;pwnd\u0007");
        detail.put("sourceType", "rules");
        detail.put("scopeType", "task-local");
        detail.put("inclusionReason", "Path-based rules matched the files currently in focus.");
        detail.put("priority", 88);
        detail.put("protected", false);
        detail.put("originalChars", 1200);
        detail.put("finalChars", 900);
        detail.put("estimatedTokens", 225);
        detail.put("truncated", true);
        detail.put("content", ("Prefer AssertJ assertions.\u001B]2;pwnd\u0007\n" + "line\n").repeat(2_000));
        detail.set("evidence", mapper.createArrayNode()
                .add("files=src/main/App.java")
                .add("query=update AppService tests"));
        root.set("detail", detail);

        invokePrivate(repl, "renderContextDetail",
                new Class<?>[]{PrintWriter.class, com.fasterxml.jackson.databind.JsonNode.class, String.class},
                out, root, "rules");

        String output = outputBuffer.toString();
        String plain = stripAnsi(output);
        assertThat(output).doesNotContain("\u001B]2;pwnd");
        assertThat(output).doesNotContain("\u0007");
        assertThat(plain).contains("Context Detail");
        assertThat(plain).contains("rules");
        assertThat(plain).contains("Source:");
        assertThat(plain).contains("Scope:");
        assertThat(plain).contains("Why:");
        assertThat(plain).contains("Evidence:");
        assertThat(plain).contains("Prefer AssertJ assertions.");
        assertThat(plain).contains("true");
        assertThat(plain).contains("...[truncated]");
        assertThat(plain).doesNotContain("pwnd");
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
    void skillDraftStatusSummary_includesVerdictCountsAndDominantReason() throws Exception {
        var statusRepl = newReplForProject(tempDir);
        writeSkillDraftArtifacts(tempDir);

        String summary = (String) invokePrivate(statusRepl, "skillDraftStatusSummary",
                new Class<?>[]{Path.class}, tempDir);
        assertThat(summary).isEqualTo("1(p:0,h:1,b:0,n:0) [STATIC_ALLOWED_TOOLS_POLICY_VIOLATION]");
    }

    @Test
    void skillDraftStatusSummary_corruptSnapshotYieldsPendingNotStaleAudit() throws Exception {
        var statusRepl = newReplForProject(tempDir);
        writeSkillDraftArtifacts(tempDir);
        // Snapshot file exists but is unparseable. We deliberately DO NOT fall back to the
        // audit tail — that would silently serve stale verdicts, the exact bug this PR fixes.
        // Instead drafts read as "pending", signaling to the user that current state is unknown.
        Path snapshot = tempDir.resolve(".aceclaw/metrics/continuous-learning/skill-draft-validation-snapshot.json");
        Files.writeString(snapshot, "{ not json");

        String summary = (String) invokePrivate(statusRepl, "skillDraftStatusSummary",
                new Class<?>[]{Path.class}, tempDir);
        assertThat(summary).isEqualTo("1(p:0,h:0,b:0,n:1)");
    }

    @Test
    void skillDraftStatusSummary_dominantReasonOnlyReflectsHoldDrafts() throws Exception {
        // Two drafts: one HOLD (replay gate failed), one BLOCK (missing description).
        // The bracketed dominant-reason indicator is meant to diagnose stuck HOLD state, not
        // BLOCK (which is a draft-file defect visible from `b:N` and inspected separately).
        var statusRepl = newReplForProject(tempDir);
        Path holdDraft = tempDir.resolve(".aceclaw/skills-drafts/hold-one/SKILL.md");
        Path blockDraft = tempDir.resolve(".aceclaw/skills-drafts/block-one/SKILL.md");
        Files.createDirectories(holdDraft.getParent());
        Files.createDirectories(blockDraft.getParent());
        Files.writeString(holdDraft, """
                ---
                name: "hold-one"
                description: "Held by replay"
                disable-model-invocation: true
                ---
                # Hold
                """);
        Files.writeString(blockDraft, """
                ---
                name: "block-one"
                description: "Blocked by missing model field override policy"
                disable-model-invocation: true
                ---
                # Block
                """);
        Path snapshot = tempDir.resolve(".aceclaw/metrics/continuous-learning/skill-draft-validation-snapshot.json");
        Files.createDirectories(snapshot.getParent());
        Files.writeString(snapshot, """
                {
                  "updatedAt": "2026-04-17T12:00:00Z",
                  "trigger": "auto-promotion",
                  "drafts": [
                    {
                      "draftPath": ".aceclaw/skills-drafts/hold-one/SKILL.md",
                      "verdict": "hold",
                      "reasons": [
                        {"gate":"replay","code":"REPLAY_GATE_FAILED","outcome":"hold","message":"token_err exceeds threshold"}
                      ]
                    },
                    {
                      "draftPath": ".aceclaw/skills-drafts/block-one/SKILL.md",
                      "verdict": "block",
                      "reasons": [
                        {"gate":"static","code":"STATIC_FRONTMATTER_MISSING","outcome":"block","message":"missing frontmatter"}
                      ]
                    }
                  ]
                }
                """);

        String summary = (String) invokePrivate(statusRepl, "skillDraftStatusSummary",
                new Class<?>[]{Path.class}, tempDir);
        // BLOCK reason must not leak into the bracketed hint.
        assertThat(summary).isEqualTo("2(p:0,h:1,b:1,n:0) [REPLAY_GATE_FAILED]");
    }

    @Test
    void skillDraftStatusSummary_prefersSnapshotOverStaleAudit() throws Exception {
        var statusRepl = newReplForProject(tempDir);
        writeSkillDraftArtifacts(tempDir);
        // Audit says HOLD/REPLAY_REPORT_MISSING (stale), snapshot says HOLD/REPLAY_GATE_FAILED (current).
        // The status line must reflect the snapshot so users see the actual current gate failure.
        Path snapshot = tempDir.resolve(".aceclaw/metrics/continuous-learning/skill-draft-validation-snapshot.json");
        Files.writeString(snapshot, """
                {
                  "updatedAt": "2026-04-17T12:00:00Z",
                  "trigger": "auto-promotion",
                  "drafts": [
                    {
                      "draftPath": ".aceclaw/skills-drafts/retry-safe/SKILL.md",
                      "verdict": "hold",
                      "reasons": [
                        {"gate":"replay","code":"REPLAY_GATE_FAILED","outcome":"hold","message":"token_estimation_error_ratio_p95 exceeds threshold"}
                      ]
                    }
                  ]
                }
                """);

        String summary = (String) invokePrivate(statusRepl, "skillDraftStatusSummary",
                new Class<?>[]{Path.class}, tempDir);
        assertThat(summary).isEqualTo("1(p:0,h:1,b:0,n:0) [REPLAY_GATE_FAILED]");
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

    @Test
    void wrapPermissionModalText_wrapsLongDescriptionsWithoutTruncating() throws Exception {
        String description = "Allow running a very long maintenance command with detailed flags "
                + "and target directories.\n"
                + "Second line contains extra context 包含中文说明 and more audit details.";

        @SuppressWarnings("unchecked")
        List<String> lines = (List<String>) invokePrivate(repl, "wrapPermissionModalText",
                new Class<?>[]{String.class, int.class}, description, 24);

        assertThat(lines).hasSizeGreaterThan(2);
        assertThat(lines)
                .allSatisfy(line -> assertThat(TerminalTheme.displayWidth(line)).isLessThanOrEqualTo(24));
        assertThat(String.join("\n", lines)).contains("target");
        assertThat(String.join("\n", lines)).contains("directories.");
        assertThat(String.join("\n", lines)).contains("包含中文说明");
        assertThat(lines.get(lines.size() - 1)).contains("details.");
    }

    @Test
    void buildStatusString_showsPressureAndCompactionBadges() throws Exception {
        var monitor = (ContextMonitor) getPrivateField(repl, "contextMonitor");
        monitor.recordStreamingUsage(175_000);
        monitor.recordCompaction(175_000, 80_000, "SUMMARIZED");
        monitor.recordStreamingUsage(180_000);

        String status = (String) invokePrivate(repl, "buildStatusString", new Class<?>[]{});

        assertThat(status).contains("ctx-compact");
        assertThat(status).contains("cmp#1");
        assertThat(status).contains("SUMMARIZED");
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
                    "token_estimation_error_ratio_p95": {"value": 0.12, "status": "measured"}
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

    private static String stripAnsi(String text) {
        return text.replaceAll("\\u001B\\[[0-?]*[ -/]*[@-~]", "");
    }

    // ── MCP carousel tests ──────────────────────────────────────────────

    /**
     * Creates an McpStatusSnapshot via reflection (private inner record).
     */
    @SuppressWarnings("unchecked")
    private static Object newMcpStatusSnapshot(int configured, int connected, int failed,
                                                int tools, List<?> servers) throws Exception {
        Class<?> snapshotClass = Class.forName(TerminalRepl.class.getName() + "$McpStatusSnapshot");
        var ctor = snapshotClass.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        return ctor.newInstance(configured, connected, failed, tools, servers);
    }

    private static Object newMcpServerStatus(String name, String status, int tools, String lastError) throws Exception {
        Class<?> serverClass = Class.forName(TerminalRepl.class.getName() + "$McpServerStatus");
        var ctor = serverClass.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        return ctor.newInstance(name, status, tools, lastError);
    }

    @SuppressWarnings("unchecked")
    private List<String> callAppendMcpServerLines() throws Exception {
        var lines = new ArrayList<String>();
        invokePrivate(repl, "appendMcpServerLines",
                new Class<?>[]{List.class}, lines);
        return lines;
    }

    @Test
    void mcpCarousel_twoOrFewerServers_noScrolling() throws Exception {
        var servers = List.of(
                newMcpServerStatus("server-a", "connected", 3, ""),
                newMcpServerStatus("server-b", "connected", 2, ""));
        setPrivateField(repl, "cachedMcpStatus",
                newMcpStatusSnapshot(2, 2, 0, 5, servers));

        var lines = callAppendMcpServerLines();
        var plain = lines.stream().map(TerminalReplTest::stripAnsi).toList();

        assertThat(plain).hasSize(2);
        assertThat(plain.get(0)).contains("server-a");
        assertThat(plain.get(1)).contains("server-b");
        // No scroll indicator
        assertThat(plain).noneMatch(l -> l.contains("\u21BB"));
    }

    @Test
    void mcpCarousel_moreThanTwoConnected_showsScrollIndicator() throws Exception {
        var servers = List.of(
                newMcpServerStatus("alpha", "connected", 2, ""),
                newMcpServerStatus("beta", "connected", 3, ""),
                newMcpServerStatus("gamma", "connected", 1, ""));
        setPrivateField(repl, "cachedMcpStatus",
                newMcpStatusSnapshot(3, 3, 0, 6, servers));

        var lines = callAppendMcpServerLines();
        var plain = lines.stream().map(TerminalReplTest::stripAnsi).toList();

        // 2 server lines + 1 scroll indicator line
        assertThat(plain).hasSize(3);
        assertThat(plain.get(2)).contains("\u21BB");
        assertThat(plain.get(2)).contains("/3");
    }

    @Test
    void mcpCarousel_firstRender_startsAtOffsetZero() throws Exception {
        var servers = List.of(
                newMcpServerStatus("alpha", "connected", 2, ""),
                newMcpServerStatus("beta", "connected", 3, ""),
                newMcpServerStatus("gamma", "connected", 1, ""));
        setPrivateField(repl, "cachedMcpStatus",
                newMcpStatusSnapshot(3, 3, 0, 6, servers));
        // Ensure fresh state (mcpLastScrollTick = 0, mcpScrollOffset = 0)
        setPrivateField(repl, "mcpScrollOffset", 0);
        setPrivateField(repl, "mcpLastScrollTick", 0L);

        var lines = callAppendMcpServerLines();
        var plain = lines.stream().map(TerminalReplTest::stripAnsi).toList();

        // First two alphabetically: alpha, beta (offset 0)
        assertThat(plain.get(0)).contains("alpha");
        assertThat(plain.get(1)).contains("beta");

        // Offset should still be 0 after first render (only timestamp initialized)
        int offset = (int) getPrivateField(repl, "mcpScrollOffset");
        assertThat(offset).isEqualTo(0);
    }

    @Test
    void mcpCarousel_advancesAfterInterval() throws Exception {
        var servers = List.of(
                newMcpServerStatus("alpha", "connected", 2, ""),
                newMcpServerStatus("beta", "connected", 3, ""),
                newMcpServerStatus("gamma", "connected", 1, ""));
        setPrivateField(repl, "cachedMcpStatus",
                newMcpStatusSnapshot(3, 3, 0, 6, servers));

        // First render: initializes timestamp, offset stays 0
        setPrivateField(repl, "mcpScrollOffset", 0);
        setPrivateField(repl, "mcpLastScrollTick", 0L);
        callAppendMcpServerLines();

        // Simulate time passing beyond the scroll interval
        long pastTick = System.currentTimeMillis() - 4_000;
        setPrivateField(repl, "mcpLastScrollTick", pastTick);

        var lines = callAppendMcpServerLines();
        var plain = lines.stream().map(TerminalReplTest::stripAnsi).toList();

        // After advance, offset becomes 1: beta, gamma
        assertThat(plain.get(0)).contains("beta");
        assertThat(plain.get(1)).contains("gamma");
    }

    @Test
    void mcpCarousel_failedServersPinned_connectedServersScroll() throws Exception {
        var servers = List.of(
                newMcpServerStatus("alpha", "connected", 2, ""),
                newMcpServerStatus("broken", "failed", 0, "connection refused"),
                newMcpServerStatus("beta", "connected", 3, ""),
                newMcpServerStatus("gamma", "connected", 1, ""));
        setPrivateField(repl, "cachedMcpStatus",
                newMcpStatusSnapshot(4, 3, 1, 6, servers));

        // Fresh state
        setPrivateField(repl, "mcpScrollOffset", 0);
        setPrivateField(repl, "mcpLastScrollTick", 0L);

        var lines = callAppendMcpServerLines();
        var plain = lines.stream().map(TerminalReplTest::stripAnsi).toList();

        // First line: pinned failed server
        assertThat(plain.get(0)).contains("broken");
        assertThat(plain.get(0)).contains("failed");
        // Second line: first scrollable connected server (only 1 scroll slot remains)
        assertThat(plain.get(1)).contains("alpha");
        // Third line: scroll indicator for remaining connected servers
        assertThat(plain.get(2)).contains("\u21BB");
        assertThat(plain.get(2)).contains("/3");
    }

    @Test
    void mcpCarousel_allFailed_noScrolling() throws Exception {
        var servers = List.of(
                newMcpServerStatus("srv-a", "failed", 0, "timeout"),
                newMcpServerStatus("srv-b", "failed", 0, "refused"));
        setPrivateField(repl, "cachedMcpStatus",
                newMcpStatusSnapshot(2, 0, 2, 0, servers));

        var lines = callAppendMcpServerLines();
        var plain = lines.stream().map(TerminalReplTest::stripAnsi).toList();

        assertThat(plain).hasSize(2);
        assertThat(plain.get(0)).contains("srv-a").contains("failed");
        assertThat(plain.get(1)).contains("srv-b").contains("failed");
        assertThat(plain).noneMatch(l -> l.contains("\u21BB"));
    }

    @Test
    void mcpCarousel_noServers_emptyOutput() throws Exception {
        setPrivateField(repl, "cachedMcpStatus",
                newMcpStatusSnapshot(0, 0, 0, 0, List.of()));

        var lines = callAppendMcpServerLines();
        assertThat(lines).isEmpty();
    }

    @Test
    void mcpCarousel_offsetWrapsAround() throws Exception {
        var servers = List.of(
                newMcpServerStatus("alpha", "connected", 2, ""),
                newMcpServerStatus("beta", "connected", 3, ""),
                newMcpServerStatus("gamma", "connected", 1, ""));
        setPrivateField(repl, "cachedMcpStatus",
                newMcpStatusSnapshot(3, 3, 0, 6, servers));

        // Set offset to last position so next advance wraps to 0
        setPrivateField(repl, "mcpScrollOffset", 2);
        long pastTick = System.currentTimeMillis() - 4_000;
        setPrivateField(repl, "mcpLastScrollTick", pastTick);

        var lines = callAppendMcpServerLines();
        var plain = lines.stream().map(TerminalReplTest::stripAnsi).toList();

        // Offset wraps: (2+1) % 3 = 0 → back to alpha, beta
        assertThat(plain.get(0)).contains("alpha");
        assertThat(plain.get(1)).contains("beta");
    }

    @Test
    void mcpCarousel_allSlotsPinned_showsOverflowCount() throws Exception {
        var servers = List.of(
                newMcpServerStatus("fail-a", "failed", 0, "err"),
                newMcpServerStatus("fail-b", "starting", 0, ""),
                newMcpServerStatus("ok-c", "connected", 5, ""));
        setPrivateField(repl, "cachedMcpStatus",
                newMcpStatusSnapshot(3, 1, 1, 5, servers));

        var lines = callAppendMcpServerLines();
        var plain = lines.stream().map(TerminalReplTest::stripAnsi).toList();

        // Both slots consumed by pinned (failed + starting)
        assertThat(plain.get(0)).contains("fail-a");
        assertThat(plain.get(1)).contains("fail-b");
        // Overflow shows the connected server count
        assertThat(plain.get(2)).contains("+1 more mcp server(s)");
    }

    @Test
    void mcpCarousel_moreThanTwoPinned_showsPinnedOverflow() throws Exception {
        var servers = List.of(
                newMcpServerStatus("fail-a", "failed", 0, "timeout"),
                newMcpServerStatus("fail-b", "failed", 0, "refused"),
                newMcpServerStatus("fail-c", "starting", 0, ""),
                newMcpServerStatus("ok-d", "connected", 5, ""));
        setPrivateField(repl, "cachedMcpStatus",
                newMcpStatusSnapshot(4, 1, 2, 5, servers));

        var lines = callAppendMcpServerLines();
        var plain = lines.stream().map(TerminalReplTest::stripAnsi).toList();

        // Two highest-priority pinned shown (both failed, alphabetical)
        assertThat(plain.get(0)).contains("fail-a");
        assertThat(plain.get(1)).contains("fail-b");
        // Pinned overflow for the third pinned server (starting)
        assertThat(plain.get(2)).contains("+1 more pinned mcp server(s)");
        // Scrollable server overflow also shown
        assertThat(plain.get(3)).contains("+1 more mcp server(s)");
    }

    // ── MCP carousel integration tests (through buildStatusPanelLines) ──

    @SuppressWarnings("unchecked")
    private List<String> callBuildStatusPanelLines() throws Exception {
        return (List<String>) invokePrivate(repl, "buildStatusPanelLines", new Class<?>[]{});
    }

    @Test
    void mcpCarousel_survivesFullPanelBuild() throws Exception {
        // Set up 4 connected MCP servers → triggers carousel in buildStatusPanelLines
        var servers = List.of(
                newMcpServerStatus("alpha", "connected", 2, ""),
                newMcpServerStatus("beta", "connected", 3, ""),
                newMcpServerStatus("gamma", "connected", 1, ""),
                newMcpServerStatus("delta", "connected", 4, ""));
        setPrivateField(repl, "cachedMcpStatus",
                newMcpStatusSnapshot(4, 4, 0, 10, servers));
        // Fresh carousel state
        setPrivateField(repl, "mcpScrollOffset", 0);
        setPrivateField(repl, "mcpLastScrollTick", 0L);

        var lines = callBuildStatusPanelLines();
        var plain = lines.stream().map(TerminalReplTest::stripAnsi).toList();

        // Baseline: header(1) + learning(1) + cron(1) + tasks(1) = 4
        // MCP adds: 2 server lines + 1 scroll indicator = 3
        // Total = 7, well within FIXED_STATUS_LINE_COUNT (9)
        assertThat(plain).hasSizeGreaterThanOrEqualTo(7);

        // MCP server lines and scroll indicator are present in the full panel
        assertThat(plain).anyMatch(l -> l.contains("mcp alpha"));
        assertThat(plain).anyMatch(l -> l.contains("mcp beta"));
        assertThat(plain).anyMatch(l -> l.contains("\u21BB"));
        assertThat(plain).anyMatch(l -> l.contains("/4"));
    }

    @Test
    void mcpCarousel_panelLineBudget_mcpNotTruncated() throws Exception {
        // Verify MCP carousel lines fit within the 9-line panel budget.
        // renderStatusFrame adds +1 separator line, so effective content budget = 8.
        // Worst case without cron expansion: header(1) + learning(1) + cron(1) + tasks(1) +
        //   mcp servers(2) + scroll indicator(1) = 7. Should be fine.
        var servers = List.of(
                newMcpServerStatus("srv-1", "connected", 5, ""),
                newMcpServerStatus("srv-2", "connected", 3, ""),
                newMcpServerStatus("srv-3", "connected", 2, ""));
        setPrivateField(repl, "cachedMcpStatus",
                newMcpStatusSnapshot(3, 3, 0, 10, servers));
        setPrivateField(repl, "mcpScrollOffset", 0);
        setPrivateField(repl, "mcpLastScrollTick", 0L);

        var lines = callBuildStatusPanelLines();

        // renderStatusFrame prepends 1 separator line then truncates to maxLines.
        // FIXED_STATUS_LINE_COUNT = 9, so content must be <= 8 to survive.
        int fixedLineCount = 9;
        assertThat(lines.size())
                .as("buildStatusPanelLines output must fit within panel budget (separator takes 1)")
                .isLessThanOrEqualTo(fixedLineCount - 1);

        // MCP scroll indicator is present (not truncated)
        var plain = lines.stream().map(TerminalReplTest::stripAnsi).toList();
        assertThat(plain).anyMatch(l -> l.contains("\u21BB"));
    }

    @Test
    void mcpCarousel_failedPinned_visibleInFullPanel() throws Exception {
        // Failed server pinned at top, connected servers scroll — verify through full panel build
        var servers = List.of(
                newMcpServerStatus("broken", "failed", 0, "connection refused"),
                newMcpServerStatus("alpha", "connected", 2, ""),
                newMcpServerStatus("beta", "connected", 3, ""),
                newMcpServerStatus("gamma", "connected", 1, ""));
        setPrivateField(repl, "cachedMcpStatus",
                newMcpStatusSnapshot(4, 3, 1, 6, servers));
        setPrivateField(repl, "mcpScrollOffset", 0);
        setPrivateField(repl, "mcpLastScrollTick", 0L);

        var lines = callBuildStatusPanelLines();
        var plain = lines.stream().map(TerminalReplTest::stripAnsi).toList();

        // Failed server must be visible in the panel
        assertThat(plain).anyMatch(l -> l.contains("broken") && l.contains("failed"));
        // One scrollable server visible in the remaining slot
        assertThat(plain).anyMatch(l -> l.contains("mcp alpha"));
        // Scroll indicator for the other 2 connected servers
        assertThat(plain).anyMatch(l -> l.contains("\u21BB") && l.contains("/3"));
    }
}

package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ValidationGateEngineTest {

    @TempDir
    Path tempDir;

    @Test
    void passWhenAllGatesSatisfied() throws Exception {
        writeDraft("""
                ---
                name: "retry-safe"
                description: "Retry with bounded timeout"
                context: "INLINE"
                allowed-tools: [bash, read_file]
                disable-model-invocation: true
                ---

                # Draft Skill
                ## Strategy
                - Use bounded retries for flaky commands
                """);
        writeReplayReport(0.10);

        var engine = new ValidationGateEngine(
                fixedClock(), false, true,
                Path.of(".aceclaw/metrics/continuous-learning/replay-latest.json"),
                0.65);

        var summary = engine.validateAll(tempDir, "manual");
        assertThat(summary.totalDrafts()).isEqualTo(1);
        assertThat(summary.passCount()).isEqualTo(1);
        assertThat(summary.holdCount()).isZero();
        assertThat(summary.blockCount()).isZero();
        assertThat(summary.decisions().getFirst().reasons()).isEmpty();
    }

    @Test
    void holdWhenReplayMissingInNonStrictMode() throws Exception {
        writeDraft("""
                ---
                name: "retry-safe"
                description: "Retry with bounded timeout"
                allowed-tools: [bash]
                disable-model-invocation: true
                ---

                # Draft Skill
                ## Strategy
                - Keep retries bounded
                """);

        var engine = new ValidationGateEngine(
                fixedClock(), false, true,
                Path.of(".aceclaw/metrics/continuous-learning/replay-latest.json"),
                0.65);
        var summary = engine.validateAll(tempDir, "manual");
        var decision = summary.decisions().getFirst();

        assertThat(decision.verdict()).isEqualTo(ValidationGateEngine.Verdict.HOLD);
        assertThat(decision.reasons())
                .extracting(ValidationGateEngine.ReasonCode::code)
                .contains("REPLAY_REPORT_MISSING");
    }

    @Test
    void blockWhenReplayMissingInStrictMode() throws Exception {
        writeDraft("""
                ---
                name: "retry-safe"
                description: "Retry with bounded timeout"
                allowed-tools: [bash]
                disable-model-invocation: true
                ---

                # Draft Skill
                ## Strategy
                - Keep retries bounded
                """);

        var engine = new ValidationGateEngine(
                fixedClock(), true, true,
                Path.of(".aceclaw/metrics/continuous-learning/replay-latest.json"),
                0.65);
        var summary = engine.validateAll(tempDir, "manual");
        assertThat(summary.decisions().getFirst().verdict()).isEqualTo(ValidationGateEngine.Verdict.BLOCK);
    }

    @Test
    void blockWhenDisableModelInvocationIsFalse() throws Exception {
        writeDraft("""
                ---
                name: "retry-safe"
                description: "Retry with bounded timeout"
                allowed-tools: [bash]
                disable-model-invocation: false
                ---

                # Draft Skill
                ## Strategy
                - Keep retries bounded
                """);
        writeReplayReport(0.10);

        var engine = new ValidationGateEngine(
                fixedClock(), false, true,
                Path.of(".aceclaw/metrics/continuous-learning/replay-latest.json"),
                0.65);
        var summary = engine.validateAll(tempDir, "manual");

        assertThat(summary.decisions().getFirst().verdict()).isEqualTo(ValidationGateEngine.Verdict.BLOCK);
        assertThat(summary.decisions().getFirst().reasons())
                .extracting(ValidationGateEngine.ReasonCode::code)
                .contains("SAFETY_DISABLE_MODEL_INVOCATION_REQUIRED");
    }

    @Test
    void deterministicVerdictForSameInput() throws Exception {
        writeDraft("""
                ---
                name: "retry-safe"
                description: "Retry with bounded timeout"
                allowed-tools: [bash, read_file]
                disable-model-invocation: true
                ---

                # Draft Skill
                ## Strategy
                - Keep retries bounded
                """);
        writeReplayReport(0.12);

        var engine = new ValidationGateEngine(
                fixedClock(), false, true,
                Path.of(".aceclaw/metrics/continuous-learning/replay-latest.json"),
                0.65);
        var first = engine.validateAll(tempDir, "manual");
        var second = engine.validateAll(tempDir, "manual");

        assertThat(first.decisions()).containsExactlyElementsOf(second.decisions());
    }

    private void writeDraft(String content) throws Exception {
        Path skillFile = tempDir.resolve(".aceclaw/skills-drafts/retry-safe/SKILL.md");
        Files.createDirectories(skillFile.getParent());
        Files.writeString(skillFile, content);
    }

    private void writeReplayReport(double tokenErrorRatio) throws Exception {
        Path replay = tempDir.resolve(".aceclaw/metrics/continuous-learning/replay-latest.json");
        Files.createDirectories(replay.getParent());
        Files.writeString(replay, """
                {
                  "metrics": {
                    "replay_success_rate_delta": {"value": 0.05, "status": "measured"},
                    "replay_token_delta": {"value": 32, "status": "measured"},
                    "replay_failure_distribution_delta": {"value": 0.03, "status": "measured"},
                    "token_estimation_error_ratio_p95": {"value": %s, "status": "measured"}
                  }
                }
                """.formatted(tokenErrorRatio));
    }

    @Test
    void snapshotReflectsCurrentVerdictEvenWhenAuditIsDeduped() throws Exception {
        // First run produces REPLAY_REPORT_MISSING.
        writeDraft("""
                ---
                name: "retry-safe"
                description: "Retry with bounded timeout"
                allowed-tools: [bash, read_file]
                disable-model-invocation: true
                ---

                # Draft Skill
                ## Strategy
                - Keep retries bounded
                """);
        var engine = new ValidationGateEngine(
                fixedClock(), false, true,
                Path.of(".aceclaw/metrics/continuous-learning/replay-latest.json"),
                0.65);
        engine.validateAll(tempDir, "first-run");

        // Second run: replay report now exists but exceeds the threshold.
        // The verdict stays HOLD, so writeAudit dedups — audit does NOT get a new entry.
        // The snapshot, however, must reflect the new reason code.
        writeReplayReport(1.43);
        engine.validateAll(tempDir, "second-run");

        Path snapshotPath = tempDir.resolve(
                ".aceclaw/metrics/continuous-learning/skill-draft-validation-snapshot.json");
        assertThat(snapshotPath).isRegularFile();

        var mapper = new ObjectMapper();
        var root = mapper.readTree(snapshotPath.toFile());
        assertThat(root.path("trigger").asText()).isEqualTo("second-run");
        var drafts = root.path("drafts");
        assertThat(drafts.isArray()).isTrue();
        assertThat(drafts.size()).isEqualTo(1);
        var draft = drafts.get(0);
        assertThat(draft.path("verdict").asText()).isEqualTo("hold");
        var reasons = draft.path("reasons");
        assertThat(reasons.isArray()).isTrue();
        assertThat(reasons.get(0).path("code").asText()).isEqualTo("REPLAY_GATE_FAILED");

        // Audit file still reflects only the original verdict change (dedup intact).
        Path auditPath = tempDir.resolve(
                ".aceclaw/metrics/continuous-learning/skill-draft-validation-audit.jsonl");
        var auditLines = Files.readAllLines(auditPath);
        assertThat(auditLines).hasSize(1);
        assertThat(auditLines.getFirst()).contains("REPLAY_REPORT_MISSING");
    }

    @Test
    void validateSingleDraftMergesIntoSnapshotWithoutErasingSiblings() throws Exception {
        // Two drafts; validateAll populates snapshot with both.
        Path firstDraft = tempDir.resolve(".aceclaw/skills-drafts/retry-safe/SKILL.md");
        Files.createDirectories(firstDraft.getParent());
        Files.writeString(firstDraft, """
                ---
                name: "retry-safe"
                description: "Retry with bounded timeout"
                allowed-tools: [bash, read_file]
                disable-model-invocation: true
                ---
                # Draft Skill
                """);
        Path secondDraft = tempDir.resolve(".aceclaw/skills-drafts/other/SKILL.md");
        Files.createDirectories(secondDraft.getParent());
        Files.writeString(secondDraft, """
                ---
                name: "other"
                description: "Another draft"
                allowed-tools: [bash]
                disable-model-invocation: true
                ---
                # Draft Skill
                """);
        writeReplayReport(0.10);

        var engine = new ValidationGateEngine(
                fixedClock(), false, true,
                Path.of(".aceclaw/metrics/continuous-learning/replay-latest.json"),
                0.65);
        engine.validateAll(tempDir, "initial");

        // Re-validate only the first draft; snapshot must still contain both entries.
        engine.validateSingleDraft(tempDir, firstDraft, "manual-recheck");

        Path snapshotPath = tempDir.resolve(
                ".aceclaw/metrics/continuous-learning/skill-draft-validation-snapshot.json");
        var root = new ObjectMapper().readTree(snapshotPath.toFile());
        assertThat(root.path("drafts").size()).isEqualTo(2);
    }

    @Test
    void validateSingleDraftSeedsSnapshotFromAuditWhenSnapshotMissing() throws Exception {
        // Regression guard for a reviewer-found hole: if skill.draft.validate (RPC) is called
        // before any full validateAll — or after the snapshot has been deleted — mergeSnapshot
        // previously produced a snapshot containing only the targeted draft. The TUI trusts
        // the snapshot over the audit, so every sibling would regress to "pending" despite
        // having known prior verdicts in the audit. Fix: seed from audit when snapshot absent.
        Path firstDraft = tempDir.resolve(".aceclaw/skills-drafts/retry-safe/SKILL.md");
        Files.createDirectories(firstDraft.getParent());
        Files.writeString(firstDraft, """
                ---
                name: "retry-safe"
                description: "Retry with bounded timeout"
                allowed-tools: [bash, read_file]
                disable-model-invocation: true
                ---
                # Draft Skill
                """);
        Path siblingDraft = tempDir.resolve(".aceclaw/skills-drafts/sibling/SKILL.md");
        Files.createDirectories(siblingDraft.getParent());
        Files.writeString(siblingDraft, """
                ---
                name: "sibling"
                description: "Unrelated draft"
                allowed-tools: [bash]
                disable-model-invocation: true
                ---
                # Sibling
                """);

        // Pre-existing audit with sibling's last-known verdict. No snapshot file exists.
        Path auditPath = tempDir.resolve(
                ".aceclaw/metrics/continuous-learning/skill-draft-validation-audit.jsonl");
        Files.createDirectories(auditPath.getParent());
        Files.writeString(auditPath, """
                {"draftPath":".aceclaw/skills-drafts/sibling/SKILL.md","verdict":"hold","reasons":[{"gate":"replay","code":"REPLAY_REPORT_MISSING","outcome":"hold","message":"replay missing"}]}
                """);
        writeReplayReport(0.10);

        var engine = new ValidationGateEngine(
                fixedClock(), false, true,
                Path.of(".aceclaw/metrics/continuous-learning/replay-latest.json"),
                0.65);
        engine.validateSingleDraft(tempDir, firstDraft, "rpc-single");

        Path snapshotPath = tempDir.resolve(
                ".aceclaw/metrics/continuous-learning/skill-draft-validation-snapshot.json");
        var root = new ObjectMapper().readTree(snapshotPath.toFile());
        var drafts = root.path("drafts");
        assertThat(drafts.size()).isEqualTo(2);

        // Sibling keeps its audit-seeded verdict rather than disappearing into "pending".
        boolean siblingFound = false;
        for (JsonNode d : drafts) {
            if (".aceclaw/skills-drafts/sibling/SKILL.md".equals(d.path("draftPath").asText())) {
                siblingFound = true;
                assertThat(d.path("verdict").asText()).isEqualTo("hold");
            }
        }
        assertThat(siblingFound).isTrue();
    }

    @Test
    void validateAllClearsSnapshotWhenDraftsDirectoryDisappears() throws Exception {
        // Seed a snapshot as if a previous run had produced HOLD decisions.
        writeDraft("""
                ---
                name: "retry-safe"
                description: "Retry with bounded timeout"
                allowed-tools: [bash]
                disable-model-invocation: true
                ---
                # Draft Skill
                """);
        var engine = new ValidationGateEngine(
                fixedClock(), false, true,
                Path.of(".aceclaw/metrics/continuous-learning/replay-latest.json"),
                0.65);
        engine.validateAll(tempDir, "first-run");

        // User wipes the drafts directory. Next validateAll must clear the snapshot so
        // downstream consumers don't keep serving the stale HOLD entries.
        Path draftsDir = tempDir.resolve(".aceclaw/skills-drafts");
        Files.walk(draftsDir)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });

        engine.validateAll(tempDir, "post-wipe");

        Path snapshotPath = tempDir.resolve(
                ".aceclaw/metrics/continuous-learning/skill-draft-validation-snapshot.json");
        assertThat(snapshotPath).isRegularFile();
        var root = new ObjectMapper().readTree(snapshotPath.toFile());
        assertThat(root.path("trigger").asText()).isEqualTo("post-wipe");
        assertThat(root.path("drafts").isArray()).isTrue();
        assertThat(root.path("drafts").size()).isZero();
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-02-24T00:00:00Z"), ZoneOffset.UTC);
    }
}

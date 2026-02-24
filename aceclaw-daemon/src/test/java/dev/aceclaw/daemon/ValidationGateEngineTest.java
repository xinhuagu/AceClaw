package dev.aceclaw.daemon;

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
                    "token_estimation_error_ratio_max": {"value": %s, "status": "measured"}
                  }
                }
                """.formatted(tokenErrorRatio));
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-02-24T00:00:00Z"), ZoneOffset.UTC);
    }
}

package dev.aceclaw.daemon;

import dev.aceclaw.memory.CandidateKind;
import dev.aceclaw.memory.CandidateState;
import dev.aceclaw.memory.CandidateStateMachine;
import dev.aceclaw.memory.CandidateStore;
import dev.aceclaw.memory.MemoryEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AutoReleaseControllerTest {

    @TempDir
    Path tempDir;

    @Test
    void canaryThenActiveAcrossTwoEvaluations() throws Exception {
        var t0 = Instant.parse("2026-02-24T00:00:00Z");
        var store = newStore(tempDir);
        var candidate = promoteCandidate(store, t0);
        writeDraft(tempDir, "retry-skill", candidate.id());
        writeReplayReport(tempDir, 0.10);

        var validation = new ValidationGateEngine(
                Clock.fixed(t0, ZoneOffset.UTC), false, true,
                Path.of(".aceclaw/metrics/continuous-learning/replay-latest.json"), 0.65);
        var controller = new AutoReleaseController(
                Clock.fixed(t0.plusSeconds(10), ZoneOffset.UTC),
                new AutoReleaseController.Config(0.2, 1, 1, 0.5, 0.5, 0.5, 0.6, 0.6, 0.6, Duration.ofDays(7), 0),
                validation);

        var first = controller.evaluateAll(tempDir, store, "test-1");
        assertThat(first.events()).extracting(AutoReleaseController.ReleaseEvent::toStage)
                .containsExactly(AutoReleaseController.Stage.CANARY);

        var second = controller.evaluateAll(tempDir, store, "test-2");
        assertThat(second.events()).extracting(AutoReleaseController.ReleaseEvent::toStage)
                .contains(AutoReleaseController.Stage.ACTIVE);
    }

    @Test
    void autoRollbackWhenGuardrailBreached() throws Exception {
        var t0 = Instant.parse("2026-02-24T00:00:00Z");
        var store = newStore(tempDir);
        var candidate = promoteCandidate(store, t0);
        writeDraft(tempDir, "rollback-skill", candidate.id());
        writeReplayReport(tempDir, 0.10);

        var validation = new ValidationGateEngine(
                Clock.fixed(t0, ZoneOffset.UTC), false, true,
                Path.of(".aceclaw/metrics/continuous-learning/replay-latest.json"), 0.65);
        var controller = new AutoReleaseController(
                Clock.fixed(t0.plusSeconds(10), ZoneOffset.UTC),
                new AutoReleaseController.Config(0.2, 1, 1, 0.8, 0.8, 0.8, 0.2, 0.8, 0.8, Duration.ofDays(7), 0),
                validation);

        controller.evaluateAll(tempDir, store, "bootstrap");
        controller.evaluateAll(tempDir, store, "to-active");

        for (int i = 0; i < 5; i++) {
            store.recordOutcome(candidate.id(), new CandidateStore.CandidateOutcome(
                    false, false, false, "runtime:test", "timeout", t0.plusSeconds(100 + i), null));
        }

        var rollback = controller.evaluateAll(tempDir, store, "guardrail");
        assertThat(rollback.events())
                .extracting(AutoReleaseController.ReleaseEvent::reasonCode)
                .contains("AUTO_ROLLBACK_GUARDRAIL_BREACH");
        assertThat(rollback.releases()).anySatisfy(r ->
                assertThat(r.stage()).isEqualTo(AutoReleaseController.Stage.SHADOW));
    }

    @Test
    void canaryDwellTimePreventsEarlyPromotion() throws Exception {
        var t0 = Instant.parse("2026-02-24T00:00:00Z");
        var store = newStore(tempDir);
        var candidate = promoteCandidate(store, t0);
        writeDraft(tempDir, "dwell-skill", candidate.id());
        writeReplayReport(tempDir, 0.10);

        // 24h dwell time, with enough attempts and good metrics
        var validation = new ValidationGateEngine(
                Clock.fixed(t0, ZoneOffset.UTC), false, true,
                Path.of(".aceclaw/metrics/continuous-learning/replay-latest.json"), 0.65);
        // Use new 12-arg Config with 24h dwell
        var config = new AutoReleaseController.Config(
                0.2, 1, 1, 0.5, 0.5, 0.5, 0.6, 0.6, 0.6, Duration.ofDays(7), 24);

        // Clock is only 10 seconds after t0 — dwell time not met
        var controller = new AutoReleaseController(
                Clock.fixed(t0.plusSeconds(10), ZoneOffset.UTC), config, validation);

        // First eval: SHADOW → CANARY
        var first = controller.evaluateAll(tempDir, store, "test-1");
        assertThat(first.events()).extracting(AutoReleaseController.ReleaseEvent::toStage)
                .containsExactly(AutoReleaseController.Stage.CANARY);

        // Second eval: should NOT promote to ACTIVE (only 10s in canary, need 24h)
        var second = controller.evaluateAll(tempDir, store, "test-2");
        assertThat(second.events()).isEmpty();

        // Third eval with clock 25h later: should promote to ACTIVE
        var controller25h = new AutoReleaseController(
                Clock.fixed(t0.plusSeconds(25 * 3600), ZoneOffset.UTC), config, validation);
        var third = controller25h.evaluateAll(tempDir, store, "test-3");
        assertThat(third.events()).extracting(AutoReleaseController.ReleaseEvent::toStage)
                .contains(AutoReleaseController.Stage.ACTIVE);
    }

    @Test
    void hardenedDefaultsAreStricter() {
        var defaults = AutoReleaseController.Config.defaults();
        assertThat(defaults.canaryMinAttempts()).isGreaterThanOrEqualTo(20);
        assertThat(defaults.canaryMaxFailureRate()).isLessThanOrEqualTo(0.10);
        assertThat(defaults.rollbackMaxFailureRate()).isLessThanOrEqualTo(0.20);
        assertThat(defaults.canaryDwellHours()).isGreaterThanOrEqualTo(24);
    }

    private static CandidateStore newStore(Path projectRoot) throws Exception {
        var cfg = new CandidateStateMachine.Config(1, 0.2, 0.9, 3, Duration.ofDays(30),
                Duration.ofDays(7), 1, 0.9, 10, Duration.ZERO, Set.of());
        var store = new CandidateStore(projectRoot, cfg);
        store.load();
        return store;
    }

    private static dev.aceclaw.memory.LearningCandidate promoteCandidate(CandidateStore store, Instant at) {
        store.upsert(new CandidateStore.CandidateObservation(
                MemoryEntry.Category.ERROR_RECOVERY,
                CandidateKind.ERROR_RECOVERY,
                "Use bounded retries for flaky commands",
                "bash",
                List.of("bash", "retry"),
                0.9,
                1,
                0,
                "src:1",
                at
        ));
        store.upsert(new CandidateStore.CandidateObservation(
                MemoryEntry.Category.ERROR_RECOVERY,
                CandidateKind.ERROR_RECOVERY,
                "Use bounded retries for flaky commands",
                "bash",
                List.of("bash", "retry"),
                0.9,
                1,
                0,
                "src:2",
                at.plusSeconds(30)
        ));
        store.evaluateAll();
        return store.byState(CandidateState.PROMOTED).getFirst();
    }

    private static void writeDraft(Path root, String skillName, String candidateId) throws Exception {
        Path draft = root.resolve(".aceclaw/skills-drafts").resolve(skillName).resolve("SKILL.md");
        Files.createDirectories(draft.getParent());
        Files.writeString(draft, """
                ---
                name: "%s"
                description: "Auto released skill"
                allowed-tools: [bash]
                disable-model-invocation: true
                source-candidate-id: "%s"
                ---

                # Draft Skill

                ## Strategy
                - Retry bounded commands.
                """.formatted(skillName, candidateId));
    }

    private static void writeReplayReport(Path root, double tokenError) throws Exception {
        Path replay = root.resolve(".aceclaw/metrics/continuous-learning/replay-latest.json");
        Files.createDirectories(replay.getParent());
        Files.writeString(replay, """
                {
                  "metrics": {
                    "replay_success_rate_delta": {"value": 0.03, "status": "measured"},
                    "replay_token_delta": {"value": 20, "status": "measured"},
                    "replay_failure_distribution_delta": {"value": 0.02, "status": "measured"},
                    "token_estimation_error_ratio_p95": {"value": %s, "status": "measured"}
                  }
                }
                """.formatted(tokenError));
    }
}

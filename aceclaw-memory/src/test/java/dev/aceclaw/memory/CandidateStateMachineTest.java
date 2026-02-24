package dev.aceclaw.memory;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CandidateStateMachineTest {

    private static final Instant NOW = Instant.parse("2026-02-23T00:00:00Z");

    @Test
    void validTransitionsShadowToPromoted() {
        assertThat(CandidateStateMachine.isValidTransition(
                CandidateState.SHADOW, CandidateState.PROMOTED)).isTrue();
    }

    @Test
    void validTransitionsShadowToRejected() {
        assertThat(CandidateStateMachine.isValidTransition(
                CandidateState.SHADOW, CandidateState.REJECTED)).isTrue();
    }

    @Test
    void validTransitionsPromotedToDemoted() {
        assertThat(CandidateStateMachine.isValidTransition(
                CandidateState.PROMOTED, CandidateState.DEMOTED)).isTrue();
    }

    @Test
    void validTransitionsPromotedToRejected() {
        assertThat(CandidateStateMachine.isValidTransition(
                CandidateState.PROMOTED, CandidateState.REJECTED)).isTrue();
    }

    @Test
    void validTransitionsDemotedToPromoted() {
        assertThat(CandidateStateMachine.isValidTransition(
                CandidateState.DEMOTED, CandidateState.PROMOTED)).isTrue();
    }

    @Test
    void validTransitionsDemotedToRejected() {
        assertThat(CandidateStateMachine.isValidTransition(
                CandidateState.DEMOTED, CandidateState.REJECTED)).isTrue();
    }

    @Test
    void invalidTransitionShadowToDemoted() {
        assertThat(CandidateStateMachine.isValidTransition(
                CandidateState.SHADOW, CandidateState.DEMOTED)).isFalse();
    }

    @Test
    void invalidTransitionPromotedToShadow() {
        assertThat(CandidateStateMachine.isValidTransition(
                CandidateState.PROMOTED, CandidateState.SHADOW)).isFalse();
    }

    @Test
    void invalidTransitionRejectedToAnything() {
        assertThat(CandidateStateMachine.isValidTransition(
                CandidateState.REJECTED, CandidateState.SHADOW)).isFalse();
        assertThat(CandidateStateMachine.isValidTransition(
                CandidateState.REJECTED, CandidateState.PROMOTED)).isFalse();
        assertThat(CandidateStateMachine.isValidTransition(
                CandidateState.REJECTED, CandidateState.DEMOTED)).isFalse();
    }

    @Test
    void promotionGrantedWhenGatesMet() {
        var sm = new CandidateStateMachine();
        var candidate = candidateWith(CandidateState.SHADOW, 0.85, 5, 4, 1);
        var result = sm.evaluate(candidate, CandidateState.PROMOTED, "test");
        assertThat(result).isPresent();
        assertThat(result.get().fromState()).isEqualTo(CandidateState.SHADOW);
        assertThat(result.get().toState()).isEqualTo(CandidateState.PROMOTED);
    }

    @Test
    void promotionDeniedWhenEvidenceBelowMinimum() {
        var sm = new CandidateStateMachine();
        var candidate = candidateWith(CandidateState.SHADOW, 0.85, 2, 2, 0);
        var result = sm.evaluate(candidate, CandidateState.PROMOTED, "test");
        assertThat(result).isEmpty();
    }

    @Test
    void promotionDeniedWhenScoreBelowMinimum() {
        var sm = new CandidateStateMachine();
        var candidate = candidateWith(CandidateState.SHADOW, 0.5, 5, 4, 1);
        var result = sm.evaluate(candidate, CandidateState.PROMOTED, "test");
        assertThat(result).isEmpty();
    }

    @Test
    void promotionDeniedWhenFailureRateTooHigh() {
        var sm = new CandidateStateMachine();
        // 3 success, 3 failure = 50% failure rate > 0.2 default
        var candidate = candidateWith(CandidateState.SHADOW, 0.85, 6, 3, 3);
        var result = sm.evaluate(candidate, CandidateState.PROMOTED, "test");
        assertThat(result).isEmpty();
    }

    @Test
    void demotionGrantedWhenNetFailuresExceedThreshold() {
        var sm = new CandidateStateMachine();
        // failureCount(5) - successCount(1) = 4 > maxConsecutiveFailures(3)
        var candidate = candidateWith(CandidateState.PROMOTED, 0.85, 6, 1, 5);
        var result = sm.evaluate(candidate, CandidateState.DEMOTED, "test");
        assertThat(result).isPresent();
        assertThat(result.get().toState()).isEqualTo(CandidateState.DEMOTED);
    }

    @Test
    void demotionDeniedWhenNetFailuresBelowThreshold() {
        var sm = new CandidateStateMachine();
        // failureCount(2) - successCount(1) = 1 <= maxConsecutiveFailures(3)
        var candidate = candidateWith(CandidateState.PROMOTED, 0.85, 5, 1, 2);
        var result = sm.evaluate(candidate, CandidateState.DEMOTED, "test");
        assertThat(result).isEmpty();
    }

    @Test
    void configOverridesDefaults() {
        var config = new CandidateStateMachine.Config(1, 0.5, 0.5, 5, Set.of());
        var sm = new CandidateStateMachine(config);
        // Would fail with defaults (evidence=2 < default 3) but passes with minEvidence=1
        var candidate = candidateWith(CandidateState.SHADOW, 0.6, 2, 2, 0);
        var result = sm.evaluate(candidate, CandidateState.PROMOTED, "test");
        assertThat(result).isPresent();
    }

    @Test
    void categoryAllowlistBlocksPromotion() {
        var config = new CandidateStateMachine.Config(
                1, 0.5, 0.5, 3,
                Set.of(MemoryEntry.Category.SUCCESSFUL_STRATEGY));
        var sm = new CandidateStateMachine(config);
        // Category is ERROR_RECOVERY, not in allowlist
        var candidate = candidateWith(CandidateState.SHADOW, 0.85, 5, 4, 1);
        var result = sm.evaluate(candidate, CandidateState.PROMOTED, "test");
        assertThat(result).isEmpty();
    }

    @Test
    void evaluateForPromotionReturnsEmptyForNonShadow() {
        var sm = new CandidateStateMachine();
        var candidate = candidateWith(CandidateState.PROMOTED, 0.85, 5, 4, 1);
        assertThat(sm.evaluateForPromotion(candidate)).isEmpty();
    }

    @Test
    void evaluateForPromotionAllowsDemotedCandidates() {
        var sm = new CandidateStateMachine();
        var candidate = candidateWith(CandidateState.DEMOTED, 0.85, 5, 4, 1);
        assertThat(sm.evaluateForPromotion(candidate)).isPresent();
    }

    @Test
    void evaluateForDemotionReturnsEmptyForNonPromoted() {
        var sm = new CandidateStateMachine();
        var candidate = candidateWith(CandidateState.SHADOW, 0.85, 5, 1, 5);
        assertThat(sm.evaluateForDemotion(candidate)).isEmpty();
    }

    @Test
    void demotedCanBeRePromoted() {
        var sm = new CandidateStateMachine();
        var candidate = candidateWith(CandidateState.DEMOTED, 0.85, 5, 4, 1);
        var result = sm.evaluate(candidate, CandidateState.PROMOTED, "re-promotion");
        assertThat(result).isPresent();
        assertThat(result.get().fromState()).isEqualTo(CandidateState.DEMOTED);
        assertThat(result.get().toState()).isEqualTo(CandidateState.PROMOTED);
    }

    @Test
    void promotionDeniedDuringCooldownWindow() {
        var sm = new CandidateStateMachine();
        var candidate = new LearningCandidate(
                "test-id", MemoryEntry.Category.ERROR_RECOVERY, CandidateKind.ERROR_RECOVERY,
                CandidateState.DEMOTED, "test content", "bash", List.of("test"),
                0.9, 5, 4, 1, NOW, NOW, NOW.plusSeconds(3600),
                LearningCandidate.CURRENT_VERSION,
                List.of(new LearningCandidate.EvidenceEvent("src", NOW, 1, 0, false, false, "ok")),
                List.of("src"), null);
        assertThat(sm.evaluateForPromotion(candidate)).isEmpty();
    }

    @Test
    void demotionTransitionCarriesCooldownAndReasonCode() {
        var sm = new CandidateStateMachine();
        var candidate = candidateWith(CandidateState.PROMOTED, 0.85, 6, 1, 5);
        var result = sm.evaluate(candidate, CandidateState.DEMOTED, "regression");
        assertThat(result).isPresent();
        assertThat(result.get().reasonCode()).isEqualTo("DEMOTION_REGRESSION");
        assertThat(result.get().cooldownUntil()).isNotNull();
    }

    @Test
    void promotionCooldownBoundaryIsDeterministicWithInjectedClock() {
        var fixedNow = Instant.parse("2026-02-23T00:00:00Z");
        var config = new CandidateStateMachine.Config(
                1, 0.5, 0.5, 3,
                Duration.ofDays(14), Duration.ofDays(7), 2, 0.6, 2, Duration.ofDays(3),
                Set.of());
        var smAtNow = new CandidateStateMachine(config, Clock.fixed(fixedNow, ZoneOffset.UTC));
        var smAfterCooldown = new CandidateStateMachine(
                config, Clock.fixed(fixedNow.plusSeconds(121), ZoneOffset.UTC));

        var candidate = new LearningCandidate(
                "test-id", MemoryEntry.Category.ERROR_RECOVERY, CandidateKind.ERROR_RECOVERY,
                CandidateState.DEMOTED, "test content", "bash", List.of("test"),
                0.9, 5, 4, 1, fixedNow.minusSeconds(10), fixedNow.minusSeconds(10),
                fixedNow.plusSeconds(120), LearningCandidate.CURRENT_VERSION,
                List.of(new LearningCandidate.EvidenceEvent("src", fixedNow.minusSeconds(10),
                        1, 0, false, false, "ok")),
                List.of("src"), null);

        assertThat(smAtNow.evaluateForPromotion(candidate)).isEmpty();
        assertThat(smAfterCooldown.evaluateForPromotion(candidate)).isPresent();
    }

    @Test
    void severeFailureLookbackUsesInjectedClockWindow() {
        var fixedNow = Instant.parse("2026-02-23T00:00:00Z");
        var config = new CandidateStateMachine.Config(
                1, 0.5, 0.5, 3,
                Duration.ofDays(14), Duration.ofDays(1), 2, 0.6, 2, Duration.ofDays(3),
                Set.of());
        var sm = new CandidateStateMachine(config, Clock.fixed(fixedNow, ZoneOffset.UTC));

        var candidate = new LearningCandidate(
                "test-id", MemoryEntry.Category.ERROR_RECOVERY, CandidateKind.ERROR_RECOVERY,
                CandidateState.SHADOW, "test content", "bash", List.of("test"),
                0.9, 5, 4, 1, fixedNow.minus(Duration.ofDays(3)), fixedNow.minus(Duration.ofDays(2)),
                null, LearningCandidate.CURRENT_VERSION,
                List.of(
                        new LearningCandidate.EvidenceEvent(
                                "src-old", fixedNow.minus(Duration.ofDays(2)), 0, 1, true, false, "old severe"),
                        new LearningCandidate.EvidenceEvent(
                                "src-recent", fixedNow.minus(Duration.ofHours(1)), 1, 0, false, false, "recent ok")
                ),
                List.of("src"), null);

        assertThat(sm.evaluateForPromotion(candidate)).isPresent();
    }

    private static LearningCandidate candidateWith(CandidateState state, double score,
                                                    int evidence, int success, int failure) {
        return new LearningCandidate(
                "test-id", MemoryEntry.Category.ERROR_RECOVERY, CandidateKind.ERROR_RECOVERY,
                state, "test content", "bash", List.of("test"),
                score, evidence, success, failure,
                NOW, NOW, List.of(), null);
    }
}

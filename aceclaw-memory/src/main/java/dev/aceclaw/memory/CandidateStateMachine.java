package dev.aceclaw.memory;

import java.time.Instant;
import java.time.Duration;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Validates and evaluates state transitions for learning candidates.
 *
 * <p>Valid transitions:
 * <ul>
 *   <li>SHADOW -&gt; PROMOTED (promotion gates must pass)</li>
 *   <li>SHADOW -&gt; REJECTED</li>
 *   <li>PROMOTED -&gt; DEMOTED (demotion gates must pass)</li>
 *   <li>PROMOTED -&gt; REJECTED</li>
 *   <li>DEMOTED -&gt; PROMOTED (promotion gates must pass)</li>
 *   <li>DEMOTED -&gt; REJECTED</li>
 * </ul>
 */
public final class CandidateStateMachine {

    private static final Set<TransitionPair> VALID_TRANSITIONS = Set.of(
            new TransitionPair(CandidateState.SHADOW, CandidateState.PROMOTED),
            new TransitionPair(CandidateState.SHADOW, CandidateState.REJECTED),
            new TransitionPair(CandidateState.PROMOTED, CandidateState.DEMOTED),
            new TransitionPair(CandidateState.PROMOTED, CandidateState.REJECTED),
            new TransitionPair(CandidateState.DEMOTED, CandidateState.PROMOTED),
            new TransitionPair(CandidateState.DEMOTED, CandidateState.REJECTED)
    );

    private final Config config;
    private final Clock clock;

    public CandidateStateMachine() {
        this(Config.defaults(), Clock.systemUTC());
    }

    public CandidateStateMachine(Config config) {
        this(config, Clock.systemUTC());
    }

    public CandidateStateMachine(Config config, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Evaluates whether a candidate can transition to the target state.
     *
     * @return a transition record if successful, empty if the gate check fails or the transition is invalid
     */
    public Optional<CandidateTransition> evaluate(LearningCandidate candidate,
                                                    CandidateState targetState,
                                                    String reason) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(targetState, "targetState");
        if (reason == null || reason.isBlank()) {
            reason = "auto";
        }

        var pair = new TransitionPair(candidate.state(), targetState);
        if (!VALID_TRANSITIONS.contains(pair)) {
            return Optional.empty();
        }

        // Gate checks for promotion (SHADOW->PROMOTED or DEMOTED->PROMOTED)
        if (targetState == CandidateState.PROMOTED) {
            var metrics = collectWindowMetrics(candidate, config.rollingWindow);
            if (!passesPromotionGates(candidate, metrics)) {
                return Optional.empty();
            }
            return Optional.of(new CandidateTransition(
                    candidate.id(), candidate.state(), targetState, reason,
                    "PROMOTION_GATE_PASS", "auto",
                    metrics.attempts, metrics.failureRate, metrics.correctionConflicts,
                    null, now()));
        }

        // Gate checks for demotion (PROMOTED->DEMOTED)
        if (targetState == CandidateState.DEMOTED) {
            var metrics = collectWindowMetrics(candidate, config.rollingWindow);
            if (!passesDemotionGates(candidate, metrics)) {
                return Optional.empty();
            }
            return Optional.of(new CandidateTransition(
                    candidate.id(), candidate.state(), targetState, reason,
                    "DEMOTION_REGRESSION", "auto",
                    metrics.attempts, metrics.failureRate, metrics.correctionConflicts,
                    now().plus(config.demotionCooldown), now()));
        }

        return Optional.of(new CandidateTransition(
                candidate.id(), candidate.state(), targetState, reason,
                "MANUAL_TRANSITION", "manual", 0, 0.0, 0, null, now()));
    }

    /**
     * Evaluates a SHADOW candidate for automatic promotion.
     *
     * @return a promotion transition if the candidate meets all gates, empty otherwise
     */
    public Optional<CandidateTransition> evaluateForPromotion(LearningCandidate candidate) {
        if (candidate.state() != CandidateState.SHADOW
                && candidate.state() != CandidateState.DEMOTED) {
            return Optional.empty();
        }
        return evaluate(candidate, CandidateState.PROMOTED, "auto-promotion");
    }

    /**
     * Evaluates a PROMOTED candidate for automatic demotion.
     *
     * @return a demotion transition if the candidate exceeds failure thresholds, empty otherwise
     */
    public Optional<CandidateTransition> evaluateForDemotion(LearningCandidate candidate) {
        if (candidate.state() != CandidateState.PROMOTED) {
            return Optional.empty();
        }
        return evaluate(candidate, CandidateState.DEMOTED, "auto-demotion");
    }

    private boolean passesPromotionGates(LearningCandidate candidate, WindowMetrics metrics) {
        if (candidate.cooldownUntil() != null && candidate.cooldownUntil().isAfter(now())) {
            return false;
        }
        if (candidate.evidenceCount() < config.minEvidenceCount) {
            return false;
        }
        if (candidate.score() < config.minScore) {
            return false;
        }
        if (metrics.attempts < config.minEvidenceCount) {
            return false;
        }
        if (metrics.failureRate > config.maxFailureRate) {
            return false;
        }
        if (hasRecentSevereFailure(candidate)) {
            return false;
        }
        if (config.allowedCategories != null && !config.allowedCategories.isEmpty()
                && !config.allowedCategories.contains(candidate.category())) {
            return false;
        }
        return true;
    }

    private boolean passesDemotionGates(LearningCandidate candidate, WindowMetrics metrics) {
        if (metrics.attempts < config.demotionMinAttempts) {
            return false;
        }
        if (metrics.failureRate >= config.demotionFailureRateThreshold) {
            return true;
        }
        return metrics.correctionConflicts >= config.correctionConflictThreshold;
    }

    private boolean hasRecentSevereFailure(LearningCandidate candidate) {
        if (candidate.evidence().isEmpty()) {
            return false;
        }
        Instant cutoff = now().minus(config.severeFailureLookback);
        return candidate.evidence().stream()
                .anyMatch(e -> e.severeFailure() && !e.observedAt().isBefore(cutoff));
    }

    private WindowMetrics collectWindowMetrics(LearningCandidate candidate, Duration window) {
        if (candidate.evidence().isEmpty()) {
            int total = candidate.successCount() + candidate.failureCount();
            double failureRate = total == 0 ? 0.0 : (double) candidate.failureCount() / total;
            return new WindowMetrics(total, failureRate, 0);
        }
        Instant cutoff = now().minus(window);
        int success = 0;
        int failure = 0;
        int conflicts = 0;
        for (var e : candidate.evidence()) {
            if (e.observedAt().isBefore(cutoff)) continue;
            success += e.successDelta();
            failure += e.failureDelta();
            if (e.correctionConflict()) {
                conflicts++;
            }
        }
        int total = success + failure;
        double failureRate = total == 0 ? 0.0 : (double) failure / total;
        return new WindowMetrics(total, failureRate, conflicts);
    }

    private Instant now() {
        return Instant.now(clock);
    }

    /**
     * Returns whether a transition from one state to another is structurally valid.
     */
    public static boolean isValidTransition(CandidateState from, CandidateState to) {
        return VALID_TRANSITIONS.contains(new TransitionPair(from, to));
    }

    private record TransitionPair(CandidateState from, CandidateState to) {}
    private record WindowMetrics(int attempts, double failureRate, int correctionConflicts) {}

    /**
     * Configuration for the candidate state machine gates.
     */
    public record Config(
            int minEvidenceCount,
            double minScore,
            double maxFailureRate,
            int maxConsecutiveFailures,
            Duration rollingWindow,
            Duration severeFailureLookback,
            int demotionMinAttempts,
            double demotionFailureRateThreshold,
            int correctionConflictThreshold,
            Duration demotionCooldown,
            Set<MemoryEntry.Category> allowedCategories
    ) {
        public Config(
                int minEvidenceCount,
                double minScore,
                double maxFailureRate,
                int maxConsecutiveFailures,
                Set<MemoryEntry.Category> allowedCategories
        ) {
            this(minEvidenceCount, minScore, maxFailureRate, maxConsecutiveFailures,
                    Duration.ofDays(14), Duration.ofDays(7), 4, 0.6, 2, Duration.ofDays(3),
                    allowedCategories);
        }

        public Config {
            if (minEvidenceCount < 0) {
                throw new IllegalArgumentException("minEvidenceCount must be non-negative");
            }
            if (minScore < 0.0 || minScore > 1.0) {
                throw new IllegalArgumentException("minScore must be in [0.0, 1.0]");
            }
            if (maxFailureRate < 0.0 || maxFailureRate > 1.0) {
                throw new IllegalArgumentException("maxFailureRate must be in [0.0, 1.0]");
            }
            if (maxConsecutiveFailures < 0) {
                throw new IllegalArgumentException("maxConsecutiveFailures must be non-negative");
            }
            if (rollingWindow == null || rollingWindow.isNegative() || rollingWindow.isZero()) {
                throw new IllegalArgumentException("rollingWindow must be positive");
            }
            if (severeFailureLookback == null || severeFailureLookback.isNegative() || severeFailureLookback.isZero()) {
                throw new IllegalArgumentException("severeFailureLookback must be positive");
            }
            if (demotionMinAttempts < 0) {
                throw new IllegalArgumentException("demotionMinAttempts must be non-negative");
            }
            if (demotionFailureRateThreshold < 0.0 || demotionFailureRateThreshold > 1.0) {
                throw new IllegalArgumentException("demotionFailureRateThreshold must be in [0.0, 1.0]");
            }
            if (correctionConflictThreshold < 0) {
                throw new IllegalArgumentException("correctionConflictThreshold must be non-negative");
            }
            if (demotionCooldown == null || demotionCooldown.isNegative()) {
                throw new IllegalArgumentException("demotionCooldown must be non-negative");
            }
            allowedCategories = allowedCategories != null ? Set.copyOf(allowedCategories) : Set.of();
        }

        public static Config defaults() {
            return new Config(3, 0.75, 0.2, 3, Set.of());
        }
    }
}

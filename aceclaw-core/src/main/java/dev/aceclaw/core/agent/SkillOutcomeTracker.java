package dev.aceclaw.core.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Thread-safe tracker of per-skill outcomes and derived metrics.
 */
public final class SkillOutcomeTracker {

    static final Duration HALF_LIFE = Duration.ofDays(7);

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SkillOutcome>> outcomes =
            new ConcurrentHashMap<>();

    /**
     * Records a single outcome for a skill.
     */
    public void record(String skillName, SkillOutcome outcome) {
        if (skillName == null || skillName.isBlank() || outcome == null) {
            return;
        }
        outcomes.computeIfAbsent(skillName, _ -> new CopyOnWriteArrayList<>()).add(outcome);
    }

    /**
     * Returns metrics for a skill, or empty if nothing was recorded.
     */
    public Optional<SkillMetrics> getMetrics(String skillName) {
        var entries = outcomes.get(skillName);
        if (entries == null || entries.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toMetrics(skillName, entries, Instant.now()));
    }

    /**
     * Returns a snapshot of all current skill metrics.
     */
    public Map<String, SkillMetrics> allMetrics() {
        Instant now = Instant.now();
        return outcomes.entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> toMetrics(entry.getKey(), entry.getValue(), now)));
    }

    /**
     * Returns a stable copy of recorded outcomes for persistence.
     */
    public List<SkillOutcome> outcomes(String skillName) {
        var entries = outcomes.get(skillName);
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        return List.copyOf(entries);
    }

    private static SkillMetrics toMetrics(String skillName, List<SkillOutcome> entries, Instant now) {
        int invocationCount = 0;
        int successCount = 0;
        int failureCount = 0;
        int correctionCount = 0;
        int successfulTurnSamples = 0;
        long totalTurnsUsed = 0L;
        Instant lastUsed = Instant.EPOCH;
        double weightedSuccess = 0.0;
        double weightedTotal = 0.0;

        for (var outcome : entries) {
            Instant timestamp = outcome.timestamp() != null ? outcome.timestamp() : now;
            if (timestamp.isAfter(lastUsed)) {
                lastUsed = timestamp;
            }
            double weight = decayWeight(timestamp, now);
            weightedTotal += weight;
            switch (outcome) {
                case SkillOutcome.Success success -> {
                    invocationCount++;
                    successCount++;
                    totalTurnsUsed += success.turnsUsed();
                    successfulTurnSamples++;
                    weightedSuccess += weight;
                }
                case SkillOutcome.Failure ignored -> {
                    invocationCount++;
                    failureCount++;
                }
                case SkillOutcome.UserCorrected ignored -> correctionCount++;
            }
        }

        double avgTurnsUsed = successfulTurnSamples == 0
                ? 0.0
                : (double) totalTurnsUsed / successfulTurnSamples;
        double timeDecayScore = weightedTotal == 0.0 ? 0.0 : weightedSuccess / weightedTotal;

        return new SkillMetrics(
                skillName,
                invocationCount,
                successCount,
                failureCount,
                correctionCount,
                avgTurnsUsed,
                lastUsed.equals(Instant.EPOCH) ? now : lastUsed,
                timeDecayScore);
    }

    private static double decayWeight(Instant timestamp, Instant now) {
        long ageMillis = Math.max(0L, Duration.between(timestamp, now).toMillis());
        if (ageMillis == 0L) {
            return 1.0;
        }
        double halfLifeMillis = HALF_LIFE.toMillis();
        return Math.exp(-Math.log(2.0) * ageMillis / halfLifeMillis);
    }
}

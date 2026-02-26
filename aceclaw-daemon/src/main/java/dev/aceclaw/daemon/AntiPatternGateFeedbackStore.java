package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Persists anti-pattern gate feedback for false-positive tracking and rollback decisions.
 */
final class AntiPatternGateFeedbackStore implements AntiPatternPreExecutionGate.RuleFeedbackProvider {

    private static final Logger log = LoggerFactory.getLogger(AntiPatternGateFeedbackStore.class);
    private static final String METRICS_FILE = ".aceclaw/metrics/continuous-learning/anti-pattern-gate-feedback.json";
    private static final int MIN_ROLLBACK_BLOCKED = 3;
    private static final double ROLLBACK_FALSE_POSITIVE_RATE = 0.5;

    private final Path file;
    private final ObjectMapper mapper;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, RuleFeedback> feedbackByRule = new HashMap<>();

    AntiPatternGateFeedbackStore(Path projectRoot) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        this.file = projectRoot.resolve(METRICS_FILE);
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        load();
    }

    @Override
    public AntiPatternPreExecutionGate.RuleFeedbackStats statsFor(String ruleId) {
        lock.lock();
        try {
            var feedback = feedbackByRule.get(ruleId);
            if (feedback == null) {
                return AntiPatternPreExecutionGate.RuleFeedbackStats.empty();
            }
            return new AntiPatternPreExecutionGate.RuleFeedbackStats(
                    feedback.blockedCount(), feedback.falsePositiveCount());
        } finally {
            lock.unlock();
        }
    }

    void recordBlocked(String ruleId) {
        mutate(ruleId, false);
    }

    boolean recordFalsePositive(String ruleId) {
        return mutate(ruleId, true);
    }

    private boolean mutate(String ruleId, boolean falsePositive) {
        if (ruleId == null || ruleId.isBlank()) {
            return false;
        }
        lock.lock();
        try {
            var existing = feedbackByRule.get(ruleId);
            int blocked = existing == null ? 0 : existing.blockedCount();
            int fp = existing == null ? 0 : existing.falsePositiveCount();
            if (falsePositive) {
                fp++;
            } else {
                blocked++;
            }
            var updated = new RuleFeedback(ruleId, blocked, fp, Instant.now());
            feedbackByRule.put(ruleId, updated);
            persistLocked();
            return shouldRollback(updated);
        } finally {
            lock.unlock();
        }
    }

    private boolean shouldRollback(RuleFeedback feedback) {
        if (feedback.blockedCount() < MIN_ROLLBACK_BLOCKED) {
            return false;
        }
        double rate = feedback.falsePositiveCount() / (double) feedback.blockedCount();
        return rate >= ROLLBACK_FALSE_POSITIVE_RATE;
    }

    private void load() {
        lock.lock();
        try {
            if (!Files.isRegularFile(file)) {
                return;
            }
            var root = mapper.readTree(file.toFile());
            if (root == null || !root.isArray()) {
                return;
            }
            for (var node : root) {
                try {
                    var item = mapper.treeToValue(node, RuleFeedback.class);
                    feedbackByRule.put(item.ruleId(), item);
                } catch (Exception e) {
                    log.warn("Skipping malformed anti-pattern feedback entry: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Failed loading anti-pattern feedback: {}", e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    private void persistLocked() {
        try {
            Files.createDirectories(file.getParent());
            var list = feedbackByRule.values().stream()
                    .sorted(java.util.Comparator.comparing(RuleFeedback::updatedAt).reversed())
                    .toList();
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            var json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(list);
            Files.writeString(tmp, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("Failed persisting anti-pattern feedback: {}", e.getMessage());
        }
    }

    private record RuleFeedback(
            String ruleId,
            int blockedCount,
            int falsePositiveCount,
            Instant updatedAt
    ) {}
}

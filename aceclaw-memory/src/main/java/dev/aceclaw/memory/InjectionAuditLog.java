package dev.aceclaw.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Append-only audit log for candidate injection events.
 *
 * <p>Records what was injected, why, at what cost, and links to the
 * turn outcome — enabling {@code learning_hit_rate} computation and
 * per-injection forensics.
 *
 * <p>Output: {@code ~/.aceclaw/memory/injection-audit.jsonl}
 */
public final class InjectionAuditLog {

    private static final Logger log = LoggerFactory.getLogger(InjectionAuditLog.class);
    private static final String AUDIT_FILE = "injection-audit.jsonl";

    private final Path auditFile;
    private final ObjectMapper mapper;
    private final ReentrantLock writeLock = new ReentrantLock();

    public InjectionAuditLog(Path memoryDir) {
        Objects.requireNonNull(memoryDir, "memoryDir");
        this.auditFile = memoryDir.resolve(AUDIT_FILE);
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /**
     * A single injected candidate with its metadata at injection time.
     */
    public record InjectedCandidate(
            String candidateId,
            String content,
            String toolTag,
            double score,
            int evidenceCount,
            double relevanceScore,
            int estimatedTokens
    ) {}

    /**
     * An injection event — one per agent turn where candidates are injected.
     */
    public record InjectionEvent(
            Instant timestamp,
            String sessionId,
            String queryHint,
            List<InjectedCandidate> candidates,
            int totalTokensUsed,
            int tokenBudget,
            int candidatesConsidered,
            int candidatesInjected
    ) {
        public InjectionEvent {
            candidates = candidates != null ? List.copyOf(candidates) : List.of();
            queryHint = queryHint != null ? queryHint : "";
        }
    }

    /**
     * An outcome linked to a prior injection event.
     */
    public record InjectionOutcome(
            Instant timestamp,
            String sessionId,
            List<String> candidateIds,
            boolean success,
            boolean severeFailure,
            String outcomeNote
    ) {
        public InjectionOutcome {
            candidateIds = candidateIds != null ? List.copyOf(candidateIds) : List.of();
        }
    }

    /**
     * Records an injection event (called at prompt assembly time).
     */
    public void recordInjection(InjectionEvent event) {
        Objects.requireNonNull(event, "event");
        appendRecord("injection", event);
    }

    /**
     * Records the outcome of a turn that had injected candidates.
     */
    public void recordOutcome(InjectionOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        appendRecord("outcome", outcome);
    }

    /**
     * Computes learning_hit_rate from the audit log.
     * Hit rate = turns with injected candidates that succeeded / total turns with injected candidates.
     *
     * @return hit rate [0.0, 1.0], or NaN if no data
     */
    public double computeHitRate() {
        if (!Files.isRegularFile(auditFile)) {
            return Double.NaN;
        }
        try {
            int totalOutcomes = 0;
            int successfulOutcomes = 0;
            for (String line : Files.readAllLines(auditFile)) {
                if (line.isBlank()) continue;
                var node = mapper.readTree(line);
                if (!"outcome".equals(node.path("type").asText())) continue;
                var data = node.path("data");
                if (data.isMissingNode()) continue;
                totalOutcomes++;
                if (data.path("success").asBoolean(false)) {
                    successfulOutcomes++;
                }
            }
            if (totalOutcomes == 0) return Double.NaN;
            return (double) successfulOutcomes / totalOutcomes;
        } catch (IOException e) {
            log.warn("Failed to compute hit rate from {}: {}", auditFile, e.getMessage());
            return Double.NaN;
        }
    }

    /**
     * Returns a summary of injection audit statistics.
     */
    public AuditSummary summarize() {
        if (!Files.isRegularFile(auditFile)) {
            return new AuditSummary(0, 0, 0, 0, Double.NaN);
        }
        try {
            int injections = 0;
            int outcomes = 0;
            int successfulOutcomes = 0;
            int totalCandidatesInjected = 0;
            for (String line : Files.readAllLines(auditFile)) {
                if (line.isBlank()) continue;
                var node = mapper.readTree(line);
                String type = node.path("type").asText("");
                if ("injection".equals(type)) {
                    injections++;
                    totalCandidatesInjected += node.path("data").path("candidatesInjected").asInt(0);
                } else if ("outcome".equals(type)) {
                    outcomes++;
                    if (node.path("data").path("success").asBoolean(false)) {
                        successfulOutcomes++;
                    }
                }
            }
            double hitRate = outcomes > 0 ? (double) successfulOutcomes / outcomes : Double.NaN;
            return new AuditSummary(injections, outcomes, successfulOutcomes, totalCandidatesInjected, hitRate);
        } catch (IOException e) {
            log.warn("Failed to summarize injection audit: {}", e.getMessage());
            return new AuditSummary(0, 0, 0, 0, Double.NaN);
        }
    }

    public record AuditSummary(
            int totalInjections,
            int totalOutcomes,
            int successfulOutcomes,
            int totalCandidatesInjected,
            double hitRate
    ) {}

    private void appendRecord(String type, Object data) {
        writeLock.lock();
        try {
            Files.createDirectories(auditFile.getParent());
            var node = mapper.createObjectNode();
            node.put("type", type);
            node.set("data", mapper.valueToTree(data));
            Files.writeString(auditFile, mapper.writeValueAsString(node) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Failed to write injection audit record: {}", e.getMessage());
        } finally {
            writeLock.unlock();
        }
    }
}

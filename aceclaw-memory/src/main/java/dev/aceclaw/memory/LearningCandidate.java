package dev.aceclaw.memory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Persisted continuous-learning candidate record.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LearningCandidate(
        String id,
        MemoryEntry.Category category,
        CandidateKind kind,
        CandidateState state,
        String content,
        String toolTag,
        List<String> tags,
        double score,
        int evidenceCount,
        int successCount,
        int failureCount,
        Instant firstSeenAt,
        Instant lastSeenAt,
        List<String> sourceRefs,
        String hmac
) {

    private static final int MAX_SOURCE_REFS = 50;

    public LearningCandidate {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(toolTag, "toolTag");
        Objects.requireNonNull(tags, "tags");
        Objects.requireNonNull(firstSeenAt, "firstSeenAt");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt");
        Objects.requireNonNull(sourceRefs, "sourceRefs");
        tags = List.copyOf(tags);
        sourceRefs = List.copyOf(sourceRefs);
        if (score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("score must be in [0.0, 1.0], got: " + score);
        }
        if (evidenceCount < 0 || successCount < 0 || failureCount < 0) {
            throw new IllegalArgumentException("counts must be non-negative");
        }
    }

    public String signablePayload() {
        return id + "|" + category + "|" + kind + "|" + state + "|" + content + "|" + toolTag + "|"
                + String.join(",", tags) + "|" + score + "|" + evidenceCount + "|" + successCount + "|"
                + failureCount + "|" + firstSeenAt + "|" + lastSeenAt + "|" + String.join(",", sourceRefs);
    }

    public LearningCandidate mergeWith(LearningCandidate incoming) {
        int mergedEvidence = evidenceCount + incoming.evidenceCount;
        double mergedScore = ((score * evidenceCount) + (incoming.score * incoming.evidenceCount))
                / Math.max(1, mergedEvidence);
        var mergedSources = new LinkedHashSet<>(sourceRefs);
        mergedSources.addAll(incoming.sourceRefs);
        while (mergedSources.size() > MAX_SOURCE_REFS) {
            mergedSources.remove(mergedSources.iterator().next());
        }
        return new LearningCandidate(
                id,
                category,
                kind,
                state,
                content,
                toolTag,
                tags,
                mergedScore,
                mergedEvidence,
                successCount + incoming.successCount,
                failureCount + incoming.failureCount,
                firstSeenAt.isBefore(incoming.firstSeenAt) ? firstSeenAt : incoming.firstSeenAt,
                lastSeenAt.isAfter(incoming.lastSeenAt) ? lastSeenAt : incoming.lastSeenAt,
                List.copyOf(mergedSources),
                hmac
        );
    }
}

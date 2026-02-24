package dev.aceclaw.memory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.ArrayList;
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
        Instant cooldownUntil,
        int version,
        List<EvidenceEvent> evidence,
        List<String> sourceRefs,
        String hmac
) {

    private static final int MAX_SOURCE_REFS = 50;
    private static final int MAX_EVIDENCE_EVENTS = 200;
    public static final int CURRENT_VERSION = 2;

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
        evidence = evidence != null ? List.copyOf(evidence) : List.of();
        tags = List.copyOf(tags);
        sourceRefs = List.copyOf(sourceRefs);
        if (version <= 0) {
            version = 1; // legacy records without explicit version field
        }
        if (score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("score must be in [0.0, 1.0], got: " + score);
        }
        if (evidenceCount < 0 || successCount < 0 || failureCount < 0) {
            throw new IllegalArgumentException("counts must be non-negative");
        }
    }

    public LearningCandidate(
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
        this(id, category, kind, state, content, toolTag, tags, score, evidenceCount, successCount, failureCount,
                firstSeenAt, lastSeenAt, null, CURRENT_VERSION, List.of(), sourceRefs, hmac);
    }

    public String signablePayload() {
        if (version <= 1) {
            return legacySignablePayload();
        }
        return id + "|" + category + "|" + kind + "|" + state + "|" + content + "|" + toolTag + "|"
                + String.join(",", tags) + "|" + score + "|" + evidenceCount + "|" + successCount + "|"
                + failureCount + "|" + firstSeenAt + "|" + lastSeenAt + "|"
                + (cooldownUntil != null ? cooldownUntil : "") + "|" + version + "|"
                + evidencePayload() + "|" + String.join(",", sourceRefs);
    }

    private String legacySignablePayload() {
        return id + "|" + category + "|" + kind + "|" + state + "|" + content + "|" + toolTag + "|"
                + String.join(",", tags) + "|" + score + "|" + evidenceCount + "|" + successCount + "|"
                + failureCount + "|" + firstSeenAt + "|" + lastSeenAt + "|" + String.join(",", sourceRefs);
    }

    private String evidencePayload() {
        var sb = new StringBuilder();
        for (var e : evidence) {
            if (sb.length() > 0) sb.append(";");
            sb.append(e.sourceRef()).append("@")
                    .append(e.observedAt()).append("@")
                    .append(e.successDelta()).append("@")
                    .append(e.failureDelta()).append("@")
                    .append(e.severeFailure()).append("@")
                    .append(e.correctionConflict()).append("@")
                    .append(e.note() == null ? "" : e.note());
        }
        return sb.toString();
    }

    public boolean isLegacyVersion() {
        return version <= 1;
    }

    public LearningCandidate migrateToV2() {
        if (!isLegacyVersion()) {
            return this;
        }
        var migratedEvidence = new ArrayList<EvidenceEvent>();
        for (var src : sourceRefs) {
            migratedEvidence.add(new EvidenceEvent(
                    src, lastSeenAt, 0, 0, false, false, "migrated-v1"));
        }
        return new LearningCandidate(
                id, category, kind, state, content, toolTag, tags,
                score, evidenceCount, successCount, failureCount,
                firstSeenAt, lastSeenAt, null, CURRENT_VERSION,
                List.copyOf(migratedEvidence), sourceRefs, null);
    }

    public LearningCandidate withState(CandidateState newState) {
        return new LearningCandidate(
                id, category, kind, newState, content, toolTag, tags,
                score, evidenceCount, successCount, failureCount,
                firstSeenAt, lastSeenAt, cooldownUntil, version, evidence, sourceRefs, null);
    }

    public LearningCandidate withCooldownUntil(Instant newCooldownUntil) {
        return new LearningCandidate(
                id, category, kind, state, content, toolTag, tags,
                score, evidenceCount, successCount, failureCount,
                firstSeenAt, lastSeenAt, newCooldownUntil, version, evidence, sourceRefs, null);
    }

    public LearningCandidate withScore(double newScore) {
        return new LearningCandidate(
                id, category, kind, state, content, toolTag, tags,
                newScore, evidenceCount, successCount, failureCount,
                firstSeenAt, lastSeenAt, cooldownUntil, version, evidence, sourceRefs, null);
    }

    public LearningCandidate mergeWith(LearningCandidate incoming) {
        int mergedEvidenceCount = evidenceCount + incoming.evidenceCount;
        double mergedScore = ((score * evidenceCount) + (incoming.score * incoming.evidenceCount))
                / Math.max(1, mergedEvidenceCount);
        var mergedSources = new LinkedHashSet<>(sourceRefs);
        mergedSources.addAll(incoming.sourceRefs);
        while (mergedSources.size() > MAX_SOURCE_REFS) {
            mergedSources.remove(mergedSources.iterator().next());
        }
        var mergedEvidenceEvents = new ArrayList<EvidenceEvent>(evidence);
        mergedEvidenceEvents.addAll(incoming.evidence);
        if (mergedEvidenceEvents.size() > MAX_EVIDENCE_EVENTS) {
            mergedEvidenceEvents = new ArrayList<>(mergedEvidenceEvents.subList(
                    mergedEvidenceEvents.size() - MAX_EVIDENCE_EVENTS, mergedEvidenceEvents.size()));
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
                mergedEvidenceCount,
                successCount + incoming.successCount,
                failureCount + incoming.failureCount,
                firstSeenAt.isBefore(incoming.firstSeenAt) ? firstSeenAt : incoming.firstSeenAt,
                lastSeenAt.isAfter(incoming.lastSeenAt) ? lastSeenAt : incoming.lastSeenAt,
                maxInstant(cooldownUntil, incoming.cooldownUntil),
                Math.max(version, incoming.version),
                List.copyOf(mergedEvidenceEvents),
                List.copyOf(mergedSources),
                hmac
        );
    }

    private static Instant maxInstant(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }

    public record EvidenceEvent(
            String sourceRef,
            Instant observedAt,
            int successDelta,
            int failureDelta,
            boolean severeFailure,
            boolean correctionConflict,
            String note
    ) {
        public EvidenceEvent {
            sourceRef = sourceRef != null ? sourceRef : "";
            observedAt = observedAt != null ? observedAt : Instant.now();
            if (successDelta < 0 || failureDelta < 0) {
                throw new IllegalArgumentException("deltas must be non-negative");
            }
        }
    }
}

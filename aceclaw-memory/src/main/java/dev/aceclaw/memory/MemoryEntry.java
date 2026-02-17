package dev.aceclaw.memory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

/**
 * A single entry in the auto-memory store.
 *
 * <p>Each entry captures a learned insight — a mistake to avoid, a pattern to follow,
 * a user preference, or a codebase-specific observation. Entries are persisted as
 * JSONL and protected by HMAC-SHA256 signatures to detect tampering.
 *
 * @param id        unique identifier (UUID)
 * @param category  the type of memory (e.g. MISTAKE, PATTERN, PREFERENCE)
 * @param content   the actual learned insight in natural language
 * @param tags      searchable tags for retrieval (e.g. "gradle", "java", "testing")
 * @param createdAt when the memory was created
 * @param source    what triggered the memory (e.g. "session:abc123", "user-feedback")
 * @param hmac      HMAC-SHA256 hex digest over id+category+content+tags+createdAt+source
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MemoryEntry(
        String id,
        Category category,
        String content,
        List<String> tags,
        Instant createdAt,
        String source,
        String hmac
) {

    /**
     * Categories of auto-memory entries.
     */
    public enum Category {
        /** A mistake the agent made and should avoid repeating. */
        MISTAKE,
        /** A recurring code pattern or convention in the project. */
        PATTERN,
        /** An explicit user preference or instruction. */
        PREFERENCE,
        /** A structural insight about the codebase (module layout, key classes, etc.). */
        CODEBASE_INSIGHT,
        /** A strategy or approach that worked well (or failed). */
        STRATEGY,
        /** A recurring workflow or multi-step process. */
        WORKFLOW,
        /** Environment-specific configuration (paths, versions, services). */
        ENVIRONMENT,
        /** Relationship between components, modules, or people. */
        RELATIONSHIP,
        /** Domain-specific terminology or abbreviation. */
        TERMINOLOGY,
        /** An explicit constraint or limitation to respect. */
        CONSTRAINT,
        /** A design or implementation decision and its rationale. */
        DECISION,
        /** Tool usage pattern, quirks, or best practices. */
        TOOL_USAGE,
        /** Communication style or protocol preferences. */
        COMMUNICATION,
        /** Session or task context carried forward. */
        CONTEXT,
        /** A correction the user made to the agent's output. */
        CORRECTION,
        /** A bookmarked file, line, or resource for quick reference. */
        BOOKMARK
    }

    /**
     * Returns the signable payload: the concatenation of all content fields
     * (excluding the hmac itself) used for HMAC computation.
     */
    public String signablePayload() {
        return id + "|" + category + "|" + content + "|" +
                String.join(",", tags) + "|" + createdAt + "|" + source;
    }
}

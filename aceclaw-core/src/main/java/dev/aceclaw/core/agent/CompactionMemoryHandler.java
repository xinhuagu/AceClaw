package dev.aceclaw.core.agent;

import java.util.List;

/**
 * Functional interface for persisting context items extracted during compaction.
 *
 * <p>This abstraction allows the core agent loop to persist auto-memory
 * without depending on aceclaw-memory directly (dependency inversion).
 *
 * <p>Implementations typically wrap an {@code AutoMemoryStore} from aceclaw-memory.
 */
@FunctionalInterface
public interface CompactionMemoryHandler {

    /**
     * Persists extracted context items from a compaction event.
     *
     * @param extractedContext the context items to persist
     * @param source           identifier for the source (e.g. "compaction:session-id")
     */
    void persist(List<String> extractedContext, String source);
}

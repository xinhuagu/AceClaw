package dev.aceclaw.memory;

/**
 * The 6-tier memory hierarchy, ordered by priority (highest first).
 *
 * <p>Each tier represents a different source and scope of memory:
 * <ol>
 *   <li><strong>Soul</strong> — Immutable core identity from SOUL.md</li>
 *   <li><strong>ManagedPolicy</strong> — Organization-managed policies</li>
 *   <li><strong>WorkspaceMemory</strong> — Project-specific ACECLAW.md instructions</li>
 *   <li><strong>UserMemory</strong> — Global user preferences from ~/.aceclaw/ACECLAW.md</li>
 *   <li><strong>AutoMemory</strong> — Agent-learned insights (JSONL + HMAC)</li>
 *   <li><strong>DailyJournal</strong> — Append-only daily activity log</li>
 * </ol>
 */
public sealed interface MemoryTier {

    /** Display name for prompt assembly. */
    String displayName();

    /** Priority (higher = loaded earlier). */
    int priority();

    /** Immutable core identity loaded from SOUL.md. */
    record Soul() implements MemoryTier {
        @Override public String displayName() { return "Soul"; }
        @Override public int priority() { return 100; }
    }

    /** Organization-managed policies (reserved for future enterprise use). */
    record ManagedPolicy() implements MemoryTier {
        @Override public String displayName() { return "Managed Policy"; }
        @Override public int priority() { return 90; }
    }

    /** Project-specific instructions from workspace ACECLAW.md files. */
    record WorkspaceMemory() implements MemoryTier {
        @Override public String displayName() { return "Workspace Memory"; }
        @Override public int priority() { return 80; }
    }

    /** Global user preferences from ~/.aceclaw/ACECLAW.md. */
    record UserMemory() implements MemoryTier {
        @Override public String displayName() { return "User Memory"; }
        @Override public int priority() { return 70; }
    }

    /** Agent-learned insights from auto-memory store. */
    record AutoMemory() implements MemoryTier {
        @Override public String displayName() { return "Auto-Memory"; }
        @Override public int priority() { return 60; }
    }

    /** Append-only daily activity journal. */
    record Journal() implements MemoryTier {
        @Override public String displayName() { return "Daily Journal"; }
        @Override public int priority() { return 50; }
    }
}

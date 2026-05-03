package dev.aceclaw.security.ids;

import java.util.Objects;

/**
 * Typed wrapper for a memory-tier key used in
 * {@link dev.aceclaw.security.Capability.MemoryRead} /
 * {@link dev.aceclaw.security.Capability.MemoryWrite} variants.
 *
 * <p>Lives in {@code aceclaw-security} (rather than {@code aceclaw-memory})
 * so the security module can describe memory capabilities without taking a
 * compile-time dependency on the memory module. The memory subsystem maps
 * this back to its own {@code MemoryEntry} keys.
 */
public record MemoryKey(String value) {
    public MemoryKey {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value;
    }
}

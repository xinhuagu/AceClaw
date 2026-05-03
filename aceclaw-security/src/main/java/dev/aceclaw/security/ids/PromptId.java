package dev.aceclaw.security.ids;

import java.util.Objects;

/**
 * Typed wrapper for a user-prompt identifier. The root of every provenance
 * chain (#480) — every capability the agent eventually requests can be traced
 * back to one of these via the {@code Provenance.rootPrompt()} field.
 */
public record PromptId(String value) {
    public PromptId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value;
    }
}

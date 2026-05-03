package dev.aceclaw.security.ids;

import java.util.Objects;

/**
 * Typed wrapper for a session identifier. Replacing bare {@code String} use at
 * API boundaries (#480 Layer 1) prevents the easy bug of passing a sessionId
 * where a {@link PromptId} or {@link PlanStepId} was expected — the compiler
 * catches it instead of a runtime mis-routing.
 *
 * <p>Construction null-guards the value so audit-log queries and dashboard
 * envelopes can rely on the field being present.
 */
public record SessionId(String value) {
    public SessionId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value;
    }
}

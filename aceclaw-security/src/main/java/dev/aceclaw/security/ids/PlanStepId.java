package dev.aceclaw.security.ids;

import java.util.Objects;

/**
 * Typed wrapper for a plan step identifier. Today the planner uses bare
 * {@code String} stepIds — wrapping them in a record at API boundaries (#480)
 * lets the compiler catch the easy bug of mixing them with sessionIds or
 * promptIds in capability/audit code.
 */
public record PlanStepId(String value) {
    public PlanStepId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value;
    }
}

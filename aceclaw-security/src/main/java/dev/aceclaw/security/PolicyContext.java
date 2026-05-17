package dev.aceclaw.security;

import java.util.Objects;

/**
 * Per-check context carried alongside a {@link Capability} and
 * {@link Provenance} into the {@link PolicyEngine} (#465 Scope #2).
 *
 * <p>What goes here vs. on the engine itself: anything the caller (today
 * {@link PermissionManager}) knows on a per-check basis but the engine
 * cannot derive from the capability — that's context. Anything the engine
 * configures globally (e.g. mode supplier, the rule list) lives on the
 * engine.
 *
 * <h3>Why {@code allowlistKey} and {@code description} live here</h3>
 *
 * The dispatcher's originating tool name and rich human-readable
 * description need to reach the legacy-policy fallback so its
 * {@link PermissionRequest} carries the same values it did pre-Layer 2.
 * Without context they'd have to be reconstructed from the capability,
 * losing per-tool information (two tools producing the same variant must
 * stay distinct in the allowlist — see {@link Capability#allowlistKey()}).
 *
 * <p>Rules are free to ignore both fields; they exist for the legacy
 * fallback adapter. A rule reasoning purely about the capability (e.g.
 * "deny FileRead(.env)") should not branch on {@code allowlistKey} — that
 * would re-introduce the per-adapter divergence that capability-style
 * governance is meant to eliminate.
 */
public record PolicyContext(String allowlistKey, String description) {
    public PolicyContext {
        Objects.requireNonNull(allowlistKey, "allowlistKey");
        Objects.requireNonNull(description, "description");
    }
}

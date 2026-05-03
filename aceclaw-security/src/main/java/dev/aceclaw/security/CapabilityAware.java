package dev.aceclaw.security;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Optional companion to {@code dev.aceclaw.core.agent.Tool}: a tool
 * implements this when it can describe its requested operation as a
 * structured {@link Capability} variant rather than the flat
 * {@link PermissionRequest} (#480 — runtime governance Scope #1).
 *
 * <p>The dispatcher checks {@code instanceof CapabilityAware} before
 * calling {@link PermissionManager}; if present, the structured capability
 * goes into the audit log and the policy decision; otherwise the legacy
 * path with {@link Capability.LegacyToolUse} bridges. This lets tools
 * migrate one-at-a-time without a flag-day sweep.
 *
 * <p>Lives in {@code aceclaw-security} (not {@code aceclaw-core}) because
 * it's specifically about the security pipeline; {@code aceclaw-core}'s
 * {@code Tool} stays general-purpose. Tools in {@code aceclaw-tools}
 * already depend on both modules so they can implement both interfaces.
 */
public interface CapabilityAware {

    /**
     * Builds the {@link Capability} this tool will exercise on the given
     * input. Called <em>before</em> the tool runs, so the policy can make
     * a decision against a structured request.
     *
     * <p>Implementations should return a {@code Capability} variant whose
     * fields match what the tool will actually do — e.g.
     * {@code WriteFileTool} returns {@link Capability.FileWrite} with the
     * path and write mode it intends to use.
     *
     * @param args the JSON args the LLM (or caller) passed to the tool
     * @return the structured capability the tool will request, never null
     * @throws IllegalArgumentException if args are missing required fields
     */
    Capability toCapability(JsonNode args);
}

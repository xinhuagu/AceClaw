package dev.aceclaw.security;

/**
 * Direction of data movement for a {@link Capability}. Derived from the
 * variant — never set independently, never lied about by callers.
 *
 * <ul>
 *   <li>{@code INGRESS} — data flows IN from a resource (e.g.
 *       {@link Capability.FileRead}).</li>
 *   <li>{@code EGRESS} — data flows OUT to a resource (e.g.
 *       {@link Capability.FileWrite}).</li>
 *   <li>{@code BOTH} — a single op moves data both ways (e.g. an HTTP
 *       request that uploads a body and reads a response).</li>
 * </ul>
 *
 * <p>Modeled as an enum because {@code DataFlow} carries no per-variant
 * fields. PolicyEngine (#465 Scope #2) will use this to express rules like
 * "no EGRESS in plan mode" uniformly across adapters.
 */
public enum DataFlow {
    INGRESS,
    EGRESS,
    BOTH
}

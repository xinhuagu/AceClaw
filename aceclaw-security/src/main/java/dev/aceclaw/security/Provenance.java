package dev.aceclaw.security;

import dev.aceclaw.security.ids.PlanStepId;
import dev.aceclaw.security.ids.PromptId;
import dev.aceclaw.security.ids.SessionId;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * "How did we get here" for a {@link Capability} check (#480). Every
 * capability use carries a {@link Provenance} so the audit log can answer
 * questions like "what user prompt eventually triggered this {@code rm
 * -rf}?", which sub-agent did it, which plan step, after how many retries.
 *
 * <h3>Field semantics</h3>
 *
 * <ul>
 *   <li>{@code rootPrompt} — the user prompt at the root of this chain.
 *       Optional because PR 2 does not yet thread {@link PromptId} through
 *       {@code StreamingAgentLoop}; PR 3 wires it. The legacy shim
 *       ({@link PermissionManager#check(PermissionRequest, String)}) leaves
 *       it empty.</li>
 *   <li>{@code sessionId} — owning session, or empty for daemon-internal
 *       checks (cron triggers, boot scripts). Not all capability uses
 *       belong to a user-facing session.</li>
 *   <li>{@code planStepId} — current plan step, or empty when not running
 *       inside a plan. PR 3 wires the planner to populate this.</li>
 *   <li>{@code subAgentDepth} — 0 for top-level agent; N for an agent
 *       spawned N levels deep. Policies can refuse spawns past a depth.</li>
 *   <li>{@code chain} — ordered list of {@link ProvenanceLink}s root-first.
 *       Empty in PR 2 (no chain wiring yet). PR 3 will populate it.</li>
 * </ul>
 *
 * <h3>Why all the {@code Optional} fields</h3>
 *
 * Because pretending we know what we don't is worse than admitting it.
 * PR 2 introduces the shape; PR 3 wires the data. An honest empty is
 * easier to spot in the audit log than a synthetic placeholder; future
 * code can pattern-match on {@code Optional.isEmpty()} to know "this
 * capability was checked through the legacy path before full provenance
 * tracking landed."
 */
public record Provenance(
        Optional<PromptId> rootPrompt,
        Optional<SessionId> sessionId,
        Optional<PlanStepId> planStepId,
        int subAgentDepth,
        List<ProvenanceLink> chain
) {
    public Provenance {
        Objects.requireNonNull(rootPrompt, "rootPrompt");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(planStepId, "planStepId");
        if (subAgentDepth < 0) {
            throw new IllegalArgumentException("subAgentDepth must be non-negative; got " + subAgentDepth);
        }
        chain = chain != null ? List.copyOf(chain) : List.of();
    }

    /**
     * Top-level provenance for a session, with no plan step / prompt-id
     * wiring (yet). Used by tools that have a session but haven't been
     * threaded through PR 3's full chain yet.
     */
    public static Provenance forSession(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        return new Provenance(
                Optional.empty(),
                Optional.of(sessionId),
                Optional.empty(),
                0,
                List.of());
    }

    /**
     * Provenance for a daemon-internal capability check that doesn't
     * belong to any user session — boot scripts, cron triggers,
     * background maintenance.
     */
    public static Provenance daemonInternal() {
        return new Provenance(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                List.of());
    }

    /**
     * Convenience for callers that have a {@code String} sessionId from a
     * pre-#480 surface (legacy {@code PermissionRequest} shim) or from the
     * dispatcher (which doesn't yet have a {@link PromptId} or
     * {@link PlanStepId} to thread — those land in PR 3). Maps {@code null}
     * to {@link #daemonInternal()} and a non-null value to {@link #forSession}.
     *
     * <p>Named {@code fromNullableSessionId} (rather than {@code legacy})
     * so the call sites describe what they actually have — a possibly-null
     * raw string id — without implying the path is legacy-only. A grep for
     * this name finds every site that lacks the full PR 3 chain wiring,
     * which is the migration signal we actually care about.
     */
    public static Provenance fromNullableSessionId(String sessionId) {
        if (sessionId == null) {
            return daemonInternal();
        }
        return forSession(new SessionId(sessionId));
    }
}

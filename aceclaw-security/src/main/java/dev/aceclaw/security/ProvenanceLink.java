package dev.aceclaw.security;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import dev.aceclaw.security.ids.PlanStepId;

import java.time.Instant;
import java.util.Objects;

/**
 * One step in a {@link Provenance} chain (#480) — what happened to the agent
 * between the root prompt and the current capability check. Modeled as a
 * sealed interface so audit replay and dashboard timelines can pattern-match
 * exhaustively on the kind of event.
 *
 * <p>Variants are intentionally narrow: each kind of step has its own
 * record with the fields that step needs. PolicyEngine (#465 Scope #2) and
 * the audit-log viewer (planned for PR 3) both consume this via
 * {@code switch} with no default — adding a new kind of link forces every
 * consumer to update.
 */
// {@code "@type"} (not {@code "kind"}) for the same collision-avoidance
// reason as {@link Capability} — the discriminator name must not match
// any variant's record component.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ProvenanceLink.PlanStepEntered.class, name = "PlanStepEntered"),
        @JsonSubTypes.Type(value = ProvenanceLink.SubAgentSpawned.class, name = "SubAgentSpawned"),
        @JsonSubTypes.Type(value = ProvenanceLink.RetryAttempt.class, name = "RetryAttempt"),
})
public sealed interface ProvenanceLink {

    /** Wall-clock instant the link was recorded. */
    Instant at();

    /** Single-line human-readable summary, used in the dashboard timeline. */
    String displayLabel();

    /** Agent entered a plan step. {@code stepId} identifies which step. */
    record PlanStepEntered(PlanStepId stepId, Instant at) implements ProvenanceLink {
        public PlanStepEntered {
            Objects.requireNonNull(stepId, "stepId");
            Objects.requireNonNull(at, "at");
        }
        @Override public String displayLabel() { return "plan-step " + stepId; }
    }

    /**
     * A sub-agent was spawned. {@code role} is a free-form descriptor (e.g.
     * {@code "planner"}, {@code "reviewer"}); the new sub-agent's depth is
     * {@code parent.subAgentDepth + 1}, recorded on the new {@link Provenance}
     * rather than on the link.
     */
    record SubAgentSpawned(String role, Instant at) implements ProvenanceLink {
        public SubAgentSpawned {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(at, "at");
        }
        @Override public String displayLabel() { return "spawn role=" + role; }
    }

    /**
     * A retry of a previously-failed action. {@code attempt} is 1-indexed
     * (a fresh attempt is 1, the first retry is 2, …) so log lines read
     * naturally as {@code "retry attempt 3"}.
     */
    record RetryAttempt(int attempt, Instant at) implements ProvenanceLink {
        public RetryAttempt {
            Objects.requireNonNull(at, "at");
            if (attempt < 1) {
                throw new IllegalArgumentException("attempt must be >= 1; got " + attempt);
            }
        }
        @Override public String displayLabel() { return "retry attempt " + attempt; }
    }
}

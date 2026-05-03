package dev.aceclaw.security;

import dev.aceclaw.security.ids.PlanStepId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins each {@link ProvenanceLink} variant's contract — null-guards, range
 * checks, and the {@code displayLabel} format the dashboard timeline will
 * render (#480 PR 2). The exhaustiveness sentinel below has no
 * {@code default} branch: adding a new variant without updating it fails
 * compilation.
 */
final class ProvenanceLinkTest {

    @Test
    void planStepEnteredCarriesStepId() {
        var step = new PlanStepId("s-1");
        var now = Instant.now();
        var link = new ProvenanceLink.PlanStepEntered(step, now);

        assertThat(link.stepId()).isEqualTo(step);
        assertThat(link.at()).isEqualTo(now);
        assertThat(link.displayLabel()).contains("s-1");
    }

    @Test
    void subAgentSpawnedCarriesRole() {
        var link = new ProvenanceLink.SubAgentSpawned("planner", Instant.now());

        assertThat(link.role()).isEqualTo("planner");
        assertThat(link.displayLabel()).contains("planner");
    }

    @Test
    void retryAttemptIsOneIndexed() {
        // 1-indexed so log lines read naturally — "retry attempt 1" is a
        // first retry, not a fresh attempt.
        var link = new ProvenanceLink.RetryAttempt(3, Instant.now());

        assertThat(link.attempt()).isEqualTo(3);
        assertThat(link.displayLabel()).contains("3");
    }

    @Test
    void retryAttemptRejectsZeroOrNegative() {
        assertThatThrownBy(() -> new ProvenanceLink.RetryAttempt(0, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProvenanceLink.RetryAttempt(-1, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allVariantsRejectNullTimestamp() {
        var step = new PlanStepId("s");
        assertThatThrownBy(() -> new ProvenanceLink.PlanStepEntered(step, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ProvenanceLink.SubAgentSpawned("r", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ProvenanceLink.RetryAttempt(1, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void exhaustivenessSentinelCoversEveryVariant() {
        // No default branch — adding a new ProvenanceLink variant without
        // updating this switch fails compilation.
        ProvenanceLink l = new ProvenanceLink.RetryAttempt(1, Instant.now());
        assertThat(exhaustivenessSentinel(l)).isNotBlank();
    }

    private static String exhaustivenessSentinel(ProvenanceLink l) {
        return switch (l) {
            case ProvenanceLink.PlanStepEntered p -> p.displayLabel();
            case ProvenanceLink.SubAgentSpawned s -> s.displayLabel();
            case ProvenanceLink.RetryAttempt r -> r.displayLabel();
        };
    }
}

package dev.aceclaw.security;

import dev.aceclaw.security.ids.PlanStepId;
import dev.aceclaw.security.ids.PromptId;
import dev.aceclaw.security.ids.SessionId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the construction contract for {@link Provenance} — the "how did we
 * get here" record threaded through every capability check (#480 PR 2).
 *
 * <p>Two factory methods carry semantic intent — {@link Provenance#forSession}
 * vs {@link Provenance#daemonInternal} — so callers don't accidentally pass
 * empty {@code Optional}s where they meant the daemon-internal sentinel.
 * Tests pin those factory shapes so a future refactor can't silently drop
 * the distinction.
 */
final class ProvenanceTest {

    @Test
    void forSessionWrapsSessionIdAndLeavesOtherFieldsEmpty() {
        // PR 2 doesn't yet thread rootPrompt or planStepId through the agent
        // loop — those land in PR 3. forSession admits that explicitly with
        // empty Optionals rather than synthesizing fake values.
        var sid = new SessionId("sess-1");
        var p = Provenance.forSession(sid);

        assertThat(p.sessionId()).contains(sid);
        assertThat(p.rootPrompt()).isEmpty();
        assertThat(p.planStepId()).isEmpty();
        assertThat(p.subAgentDepth()).isZero();
        assertThat(p.chain()).isEmpty();
    }

    @Test
    void daemonInternalLeavesSessionIdEmpty() {
        // Cron triggers, boot scripts, and other daemon-internal capability
        // checks have no session. The factory makes that distinct from
        // "forgot to pass a session" — empty Optional, not null.
        var p = Provenance.daemonInternal();

        assertThat(p.sessionId()).isEmpty();
        assertThat(p.rootPrompt()).isEmpty();
        assertThat(p.planStepId()).isEmpty();
        assertThat(p.subAgentDepth()).isZero();
    }

    @Test
    void fromNullableSessionIdDispatchesByNullability() {
        // The bridge from callers that have a possibly-null raw String id
        // (PermissionManager legacy shim, dispatcher pre-PR-3) lands here.
        // null sessionId means daemon-internal; non-null wraps as a
        // SessionId.
        assertThat(Provenance.fromNullableSessionId(null).sessionId()).isEmpty();
        assertThat(Provenance.fromNullableSessionId("sess-x").sessionId())
                .map(SessionId::value)
                .contains("sess-x");
    }

    @Test
    void canonicalConstructorRequiresEveryOptionalArgument() {
        // Optional fields rejecting null is intentional: callers must say
        // explicitly "I have nothing here" (Optional.empty()) rather than
        // accidentally passing null. That eliminates a class of NPE bugs
        // downstream.
        assertThatThrownBy(() -> new Provenance(
                null, Optional.empty(), Optional.empty(), 0, List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("rootPrompt");
        assertThatThrownBy(() -> new Provenance(
                Optional.empty(), null, Optional.empty(), 0, List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sessionId");
        assertThatThrownBy(() -> new Provenance(
                Optional.empty(), Optional.empty(), null, 0, List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("planStepId");
    }

    @Test
    void rejectsNegativeSubAgentDepth() {
        assertThatThrownBy(() -> new Provenance(
                Optional.empty(), Optional.empty(), Optional.empty(), -1, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subAgentDepth");
    }

    @Test
    void chainIsDefensivelyCopied() {
        // Caller mutates their list AFTER constructing Provenance — the
        // record's chain must not change. List.copyOf in the canonical
        // constructor enforces this.
        var mutable = new java.util.ArrayList<ProvenanceLink>();
        mutable.add(new ProvenanceLink.RetryAttempt(1, Instant.now()));
        var p = new Provenance(
                Optional.empty(), Optional.empty(), Optional.empty(), 0, mutable);

        mutable.add(new ProvenanceLink.RetryAttempt(2, Instant.now()));
        assertThat(p.chain()).hasSize(1);
    }

    @Test
    void chainAcceptsNullAsEmpty() {
        // Caller convenience — null in canonical constructor becomes List.of().
        // Same pattern as records elsewhere in the codebase.
        var p = new Provenance(
                Optional.empty(), Optional.empty(), Optional.empty(), 0, null);
        assertThat(p.chain()).isEmpty();
    }

    @Test
    void canCarryFullChain() {
        // Full population test — PR 3 will populate this through the agent
        // loop; PR 2 just guarantees the shape works.
        var sid = new SessionId("s");
        var promptId = new PromptId("p1");
        var stepId = new PlanStepId("step-1");
        var now = Instant.now();
        var p = new Provenance(
                Optional.of(promptId),
                Optional.of(sid),
                Optional.of(stepId),
                2,
                List.of(
                        new ProvenanceLink.PlanStepEntered(stepId, now),
                        new ProvenanceLink.SubAgentSpawned("planner", now),
                        new ProvenanceLink.RetryAttempt(2, now)));

        assertThat(p.rootPrompt()).contains(promptId);
        assertThat(p.subAgentDepth()).isEqualTo(2);
        assertThat(p.chain()).hasSize(3);
    }
}

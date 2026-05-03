package dev.aceclaw.security.ids;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the null-guard + {@code toString} contract for the typed-ID record
 * wrappers (#480). The whole point of these wrappers is that mixing a
 * {@code SessionId} and a {@code PromptId} fails at compile time —
 * runtime tests can only check the bits that aren't compile-checked, namely
 * "null becomes a clear error, not a downstream NPE."
 */
final class IdRecordsTest {

    @Test
    void sessionIdRejectsNull() {
        assertThatThrownBy(() -> new SessionId(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void sessionIdToStringIsTheValue() {
        // Audit log + dashboard envelope serialize the wrapper as the bare
        // value. Confirm toString matches so log lines stay readable.
        assertThat(new SessionId("sess-1").toString()).isEqualTo("sess-1");
    }

    @Test
    void promptIdRejectsNull() {
        assertThatThrownBy(() -> new PromptId(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void planStepIdRejectsNull() {
        assertThatThrownBy(() -> new PlanStepId(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void memoryKeyRejectsNull() {
        assertThatThrownBy(() -> new MemoryKey(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void recordsImplementValueEquality() {
        // Records get equals/hashCode for free; the test pins that no one
        // accidentally overrode them away.
        assertThat(new SessionId("a")).isEqualTo(new SessionId("a"));
        assertThat(new SessionId("a")).isNotEqualTo(new SessionId("b"));
        assertThat(new SessionId("a")).hasSameHashCodeAs(new SessionId("a"));
    }
}

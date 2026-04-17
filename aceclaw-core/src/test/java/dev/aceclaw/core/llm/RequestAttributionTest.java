package dev.aceclaw.core.llm;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestAttributionTest {

    @Test
    void emptyAttributionHasZeroTotal() {
        var a = RequestAttribution.empty();
        assertThat(a.total()).isZero();
        assertThat(a.bySource()).isEmpty();
        assertThat(a.count(RequestSource.MAIN_TURN)).isZero();
    }

    @Test
    void builderRecordsAndSums() {
        var a = RequestAttribution.builder()
                .record(RequestSource.MAIN_TURN)
                .record(RequestSource.MAIN_TURN)
                .record(RequestSource.PLANNER)
                .build();

        assertThat(a.total()).isEqualTo(3);
        assertThat(a.count(RequestSource.MAIN_TURN)).isEqualTo(2);
        assertThat(a.count(RequestSource.PLANNER)).isEqualTo(1);
        assertThat(a.count(RequestSource.REPLAN)).isZero();
    }

    @Test
    void builderBatchRecordAddsGivenCount() {
        var a = RequestAttribution.builder()
                .record(RequestSource.MAIN_TURN, 5)
                .record(RequestSource.COMPACTION_SUMMARY, 2)
                .build();

        assertThat(a.total()).isEqualTo(7);
        assertThat(a.count(RequestSource.MAIN_TURN)).isEqualTo(5);
    }

    @Test
    void builderIgnoresZeroCount() {
        var a = RequestAttribution.builder()
                .record(RequestSource.MAIN_TURN, 0)
                .record(RequestSource.PLANNER, 3)
                .build();

        assertThat(a.total()).isEqualTo(3);
        assertThat(a.bySource()).containsOnlyKeys(RequestSource.PLANNER);
    }

    @Test
    void builderRejectsNegativeCount() {
        var b = RequestAttribution.builder();
        assertThatThrownBy(() -> b.record(RequestSource.MAIN_TURN, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mergeSumsCountsPerSource() {
        var a = RequestAttribution.builder()
                .record(RequestSource.MAIN_TURN, 3)
                .record(RequestSource.PLANNER, 1)
                .build();
        var b = RequestAttribution.builder()
                .record(RequestSource.MAIN_TURN, 2)
                .record(RequestSource.REPLAN, 1)
                .build();

        var merged = a.merge(b);

        assertThat(merged.total()).isEqualTo(7);
        assertThat(merged.count(RequestSource.MAIN_TURN)).isEqualTo(5);
        assertThat(merged.count(RequestSource.PLANNER)).isEqualTo(1);
        assertThat(merged.count(RequestSource.REPLAN)).isEqualTo(1);
    }

    @Test
    void builderMergeFoldsAnotherAttributionIntoThisBuilder() {
        // Used by the streaming loop to absorb a CompactionResult's request attribution
        // into the parent turn's running builder, and by SequentialPlanExecutor to fold
        // per-step attributions into the plan total. Each per-source count must add.
        var compactionAttr = RequestAttribution.builder()
                .record(RequestSource.COMPACTION_SUMMARY)
                .build();
        var turnBuilder = RequestAttribution.builder()
                .record(RequestSource.MAIN_TURN, 2);

        turnBuilder.merge(compactionAttr);

        var snapshot = turnBuilder.build();
        assertThat(snapshot.total()).isEqualTo(3);
        assertThat(snapshot.count(RequestSource.MAIN_TURN)).isEqualTo(2);
        assertThat(snapshot.count(RequestSource.COMPACTION_SUMMARY)).isEqualTo(1);
    }

    @Test
    void builderMergeToleratesNullAndEmpty() {
        var b = RequestAttribution.builder().record(RequestSource.MAIN_TURN);
        b.merge(null);
        b.merge(RequestAttribution.empty());

        assertThat(b.total()).isEqualTo(1);
    }

    @Test
    void mergeWithEmptyIsIdentity() {
        var a = RequestAttribution.builder().record(RequestSource.MAIN_TURN, 4).build();

        assertThat(a.merge(RequestAttribution.empty())).isEqualTo(a);
        assertThat(RequestAttribution.empty().merge(a)).isEqualTo(a);
        assertThat(a.merge(null)).isEqualTo(a);
    }

    @Test
    void constructorEnforcesTotalMatchesSum() {
        // Invariant from the issue discussion: sum(bySource.values()) == total.
        // Directly constructing with a mismatched total must fail loud, not silently drift.
        Map<RequestSource, Integer> counts = new EnumMap<>(RequestSource.class);
        counts.put(RequestSource.MAIN_TURN, 3);

        assertThatThrownBy(() -> new RequestAttribution(counts, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void countRejectsNullSource() {
        // Per CLAUDE.md: method parameters passed to downstream calls must fail fast on
        // null rather than silently returning a plausible-looking zero.
        var a = RequestAttribution.builder().record(RequestSource.MAIN_TURN).build();
        assertThatThrownBy(() -> a.count(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("source");
    }

    @Test
    void bySourceMapIsUnmodifiable() {
        var a = RequestAttribution.builder().record(RequestSource.MAIN_TURN).build();
        assertThatThrownBy(() -> a.bySource().put(RequestSource.PLANNER, 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}

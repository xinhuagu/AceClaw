package dev.aceclaw.daemon.scheduler;

import dev.aceclaw.memory.TrendDetector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the pure helpers extracted from AceClawDaemon alongside
 * MaintenancePipelineRunner (batch B of the daemon decomp). The pipeline's
 * stage orchestration goes through the daemon's end-to-end integration tests
 * (heavy IO + scheduler wiring); these unit tests pin the pure parts.
 */
class MaintenancePipelineRunnerTest {

    // -- summarizeTrends: first 3 descriptions, " | " separator ---------------

    @Test
    void summarizeTrends_emptyListYieldsEmptyString() {
        assertThat(MaintenancePipelineRunner.summarizeTrends(List.of())).isEqualTo("");
    }

    @Test
    void summarizeTrends_joinsAllDescriptionsWhenUnderLimit() {
        var trends = List.of(
                trend("error chain spiking"),
                trend("workflow Y stable"));

        assertThat(MaintenancePipelineRunner.summarizeTrends(trends))
                .isEqualTo("error chain spiking | workflow Y stable");
    }

    @Test
    void summarizeTrends_truncatesToFirst3WhenOverLimit() {
        // Limit is hard-coded at 3 — a noisy trigger fire must not flood
        // the journal line with every detected trend.
        var trends = List.of(
                trend("first"),
                trend("second"),
                trend("third"),
                trend("fourth"),
                trend("fifth"));

        assertThat(MaintenancePipelineRunner.summarizeTrends(trends))
                .isEqualTo("first | second | third");
    }

    @Test
    void summarizeTrends_preservesOrder() {
        var trends = List.of(trend("alpha"), trend("beta"), trend("gamma"));

        assertThat(MaintenancePipelineRunner.summarizeTrends(trends))
                .isEqualTo("alpha | beta | gamma");
    }

    // -- helpers ---------------------------------------------------------------

    private static TrendDetector.Trend trend(String description) {
        return new TrendDetector.Trend("metric", TrendDetector.TrendDirection.RISING, 1.0, 5, description);
    }
}

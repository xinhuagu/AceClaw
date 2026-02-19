package dev.aceclaw.core.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ToolMetricsCollectorTest {

    private ToolMetricsCollector collector;

    @BeforeEach
    void setUp() {
        collector = new ToolMetricsCollector();
    }

    @Test
    void recordSingleTool() {
        collector.record("read_file", true, 50);

        var metrics = collector.getMetrics("read_file");
        assertThat(metrics).isPresent();
        assertThat(metrics.get().totalInvocations()).isEqualTo(1);
        assertThat(metrics.get().successCount()).isEqualTo(1);
        assertThat(metrics.get().errorCount()).isEqualTo(0);
        assertThat(metrics.get().totalExecutionMs()).isEqualTo(50);
        assertThat(metrics.get().lastUsed()).isNotNull();
    }

    @Test
    void recordMultipleInvocations() {
        collector.record("bash", true, 100);
        collector.record("bash", true, 200);
        collector.record("bash", false, 50);

        var metrics = collector.getMetrics("bash").orElseThrow();
        assertThat(metrics.totalInvocations()).isEqualTo(3);
        assertThat(metrics.successCount()).isEqualTo(2);
        assertThat(metrics.errorCount()).isEqualTo(1);
        assertThat(metrics.totalExecutionMs()).isEqualTo(350);
    }

    @Test
    void recordMultipleTools() {
        collector.record("read_file", true, 10);
        collector.record("write_file", true, 20);
        collector.record("bash", false, 100);

        var all = collector.allMetrics();
        assertThat(all).hasSize(3);
        assertThat(all).containsKeys("read_file", "write_file", "bash");
    }

    @Test
    void unknownToolReturnsEmpty() {
        assertThat(collector.getMetrics("nonexistent")).isEmpty();
    }

    @Test
    void allMetricsEmptyByDefault() {
        assertThat(collector.allMetrics()).isEmpty();
    }

    @Test
    void avgExecutionMs() {
        collector.record("grep", true, 100);
        collector.record("grep", true, 200);
        collector.record("grep", false, 300);

        var metrics = collector.getMetrics("grep").orElseThrow();
        assertThat(metrics.avgExecutionMs()).isEqualTo(200.0);
    }

    @Test
    void successRate() {
        collector.record("edit_file", true, 50);
        collector.record("edit_file", true, 60);
        collector.record("edit_file", false, 70);
        collector.record("edit_file", true, 80);

        var metrics = collector.getMetrics("edit_file").orElseThrow();
        assertThat(metrics.successRate()).isEqualTo(0.75);
    }

    @Test
    void derivedMethodsZeroInvocations() {
        // ToolMetrics with zero invocations (direct construction)
        var metrics = new ToolMetrics("empty", 0, 0, 0, 0, java.time.Instant.now());
        assertThat(metrics.avgExecutionMs()).isEqualTo(0.0);
        assertThat(metrics.successRate()).isEqualTo(0.0);
    }

    @Test
    void concurrentAccess() {
        // Record from 100 parallel threads
        IntStream.range(0, 100).parallel().forEach(i ->
                collector.record("concurrent_tool", i % 3 != 0, i));

        var metrics = collector.getMetrics("concurrent_tool").orElseThrow();
        assertThat(metrics.totalInvocations()).isEqualTo(100);
        // ~33 errors (i % 3 == 0 for i in 0..99), ~67 successes
        assertThat(metrics.successCount() + metrics.errorCount()).isEqualTo(100);
    }
}

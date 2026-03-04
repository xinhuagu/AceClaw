package dev.aceclaw.core.agent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class ProgressDetectorTest {

    @Test
    void writeFileIsProgress() {
        var detector = new ProgressDetector();

        detector.recordToolResult("write_file", "{\"path\":\"/tmp/test.txt\"}", false, "ok");

        assertThat(detector.isStalled()).isFalse();
        assertThat(detector.noProgressCount()).isEqualTo(0);
    }

    @Test
    void readSameFileNotProgress() {
        var detector = new ProgressDetector(3);

        // First read is progress (new file)
        detector.recordToolResult("read_file", "{\"path\":\"/tmp/test.txt\"}", false, "content");
        assertThat(detector.noProgressCount()).isEqualTo(0);

        // Second read of same file is NOT progress
        detector.recordToolResult("read_file", "{\"path\":\"/tmp/test.txt\"}", false, "content");
        assertThat(detector.noProgressCount()).isEqualTo(1);

        // Third read still not progress
        detector.recordToolResult("read_file", "{\"path\":\"/tmp/test.txt\"}", false, "content");
        assertThat(detector.noProgressCount()).isEqualTo(2);
    }

    @Test
    void stallAfterThreshold() {
        var detector = new ProgressDetector(3);

        // 3 consecutive errors -> stalled
        detector.recordToolResult("bash", "{\"command\":\"npm test\"}", true, "fail");
        assertThat(detector.isStalled()).isFalse();

        detector.recordToolResult("bash", "{\"command\":\"npm test\"}", true, "fail");
        assertThat(detector.isStalled()).isFalse();

        detector.recordToolResult("bash", "{\"command\":\"npm test\"}", true, "fail");
        assertThat(detector.isStalled()).isTrue();
        assertThat(detector.noProgressCount()).isEqualTo(3);
    }

    @Test
    void successResetsStall() {
        var detector = new ProgressDetector(3);

        // 2 errors
        detector.recordToolResult("bash", "{\"command\":\"npm test\"}", true, "fail");
        detector.recordToolResult("bash", "{\"command\":\"npm test\"}", true, "fail");
        assertThat(detector.noProgressCount()).isEqualTo(2);

        // Success resets
        detector.recordToolResult("write_file", "{\"path\":\"/tmp/fix.txt\"}", false, "ok");
        assertThat(detector.noProgressCount()).isEqualTo(0);
        assertThat(detector.isStalled()).isFalse();
    }

    @Test
    void pivotPromptContainsContext() {
        var detector = new ProgressDetector(2);

        detector.recordToolResult("bash", "{\"command\":\"npm test\"}", true, "fail");
        detector.recordToolResult("bash", "{\"command\":\"npm test\"}", true, "fail");

        assertThat(detector.isStalled()).isTrue();
        String pivot = detector.buildPivotPrompt();
        assertThat(pivot).contains("Progress stall detected");
        assertThat(pivot).contains("tool calls without making meaningful forward progress");
        assertThat(pivot).contains("fundamentally DIFFERENT strategy");
    }

    @Test
    void bashTestCommandIsProgress() {
        var detector = new ProgressDetector();

        detector.recordToolResult("bash", "{\"command\":\"gradle test\"}", false, "BUILD SUCCESSFUL");

        assertThat(detector.noProgressCount()).isEqualTo(0);
        assertThat(detector.isStalled()).isFalse();
    }

    @Test
    void configurableThreshold() {
        // Custom threshold of 2
        var detector = new ProgressDetector(2);

        detector.recordToolResult("bash", "{\"command\":\"npm test\"}", true, "fail");
        assertThat(detector.isStalled()).isFalse();

        detector.recordToolResult("bash", "{\"command\":\"npm test\"}", true, "fail");
        assertThat(detector.isStalled()).isTrue();

        // Default threshold of 5
        var defaultDetector = new ProgressDetector();
        for (int i = 0; i < 4; i++) {
            defaultDetector.recordToolResult("bash", "{}", true, "fail");
        }
        assertThat(defaultDetector.isStalled()).isFalse();
        defaultDetector.recordToolResult("bash", "{}", true, "fail");
        assertThat(defaultDetector.isStalled()).isTrue();
    }

    @Test
    void editFileIsProgress() {
        var detector = new ProgressDetector();

        detector.recordToolResult("edit_file", "{\"path\":\"/tmp/test.txt\"}", false, "ok");

        assertThat(detector.noProgressCount()).isEqualTo(0);
    }

    @Test
    void readNewFileIsProgress() {
        var detector = new ProgressDetector(3);

        // Different files are each progress
        detector.recordToolResult("read_file", "{\"path\":\"/tmp/a.txt\"}", false, "a");
        assertThat(detector.noProgressCount()).isEqualTo(0);

        detector.recordToolResult("read_file", "{\"path\":\"/tmp/b.txt\"}", false, "b");
        assertThat(detector.noProgressCount()).isEqualTo(0);
    }

    @Test
    void attemptSummaryShowsRecentTools() {
        var detector = new ProgressDetector(2);

        detector.recordToolResult("bash", "{\"command\":\"npm test\"}", true, "fail");
        detector.recordToolResult("read_file", "{\"path\":\"/tmp/log\"}", false, "content");
        detector.recordToolResult("bash", "{\"command\":\"npm run build\"}", true, "error");

        String summary = detector.buildAttemptSummary();
        assertThat(summary).contains("bash");
        assertThat(summary).contains("FAILED");
    }

    @Test
    void resetClearsStall() {
        var detector = new ProgressDetector(2);

        detector.recordToolResult("bash", "{}", true, "fail");
        detector.recordToolResult("bash", "{}", true, "fail");
        assertThat(detector.isStalled()).isTrue();

        detector.reset();
        assertThat(detector.isStalled()).isFalse();
        assertThat(detector.noProgressCount()).isEqualTo(0);
    }

    @Test
    void concurrentRecordToolResultDoesNotThrow() throws Exception {
        var detector = new ProgressDetector(100);
        int threadCount = 8;
        int callsPerThread = 50;
        var errors = new AtomicBoolean(false);
        var latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            int threadNum = t;
            Thread.ofVirtual().start(() -> {
                try {
                    for (int i = 0; i < callsPerThread; i++) {
                        boolean isError = i % 3 == 0;
                        String tool = switch (threadNum % 4) {
                            case 0 -> "bash";
                            case 1 -> "read_file";
                            case 2 -> "write_file";
                            default -> "edit_file";
                        };
                        detector.recordToolResult(tool,
                                "{\"path\":\"/tmp/t" + threadNum + "/f" + i + "\"}",
                                isError, isError ? "error" : "ok");
                    }
                } catch (Exception e) {
                    errors.set(true);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        assertThat(errors.get()).isFalse();
        // noProgressCount should be non-negative regardless of thread interleaving
        assertThat(detector.noProgressCount()).isGreaterThanOrEqualTo(0);
        // buildAttemptSummary and buildPivotPrompt should not throw
        assertThat(detector.buildAttemptSummary()).isNotNull();
        assertThat(detector.buildPivotPrompt()).isNotNull();
    }
}

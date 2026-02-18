package dev.aceclaw.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DailyJournalTest {

    @TempDir
    Path tempDir;

    private DailyJournal journal;

    @BeforeEach
    void setUp() throws IOException {
        journal = new DailyJournal(tempDir);
    }

    @Test
    void appendCreatesFile() {
        journal.append("First entry");

        var todayFile = journal.fileForDate(LocalDate.now());
        assertThat(Files.exists(todayFile)).isTrue();
    }

    @Test
    void appendAddsTimestampedEntry() throws IOException {
        journal.append("Test entry content");

        var lines = journal.loadDay(LocalDate.now());
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst()).contains("Test entry content");
        // Should have a timestamp in brackets
        assertThat(lines.getFirst()).matches("- \\[\\d{4}-.*\\] Test entry content");
    }

    @Test
    void multipleAppends() {
        journal.append("Entry one");
        journal.append("Entry two");
        journal.append("Entry three");

        var lines = journal.loadDay(LocalDate.now());
        assertThat(lines).hasSize(3);
    }

    @Test
    void loadDayForMissingDate() {
        var lines = journal.loadDay(LocalDate.of(2020, 1, 1));
        assertThat(lines).isEmpty();
    }

    @Test
    void loadRecentWindowIncludesToday() {
        journal.append("Today's entry");

        var recent = journal.loadRecentWindow();
        assertThat(recent).hasSize(1);
        assertThat(recent.getFirst()).contains("Today's entry");
    }

    @Test
    void loadRecentWindowWithYesterday() throws IOException {
        // Manually write yesterday's journal
        var yesterday = LocalDate.now().minusDays(1);
        var yesterdayFile = journal.fileForDate(yesterday);
        Files.writeString(yesterdayFile, "- [2025-01-01T00:00:00Z] Yesterday entry\n");

        journal.append("Today entry");

        var recent = journal.loadRecentWindow();
        assertThat(recent).hasSize(2);
        assertThat(recent.get(0)).contains("Today entry");
        assertThat(recent.get(1)).contains("Yesterday entry");
    }

    @Test
    void maxLinesCap() throws IOException {
        // Write MAX_LINES entries directly to the file
        var todayFile = journal.fileForDate(LocalDate.now());
        var sb = new StringBuilder();
        for (int i = 0; i < DailyJournal.MAX_LINES_PER_FILE; i++) {
            sb.append("- [2025-01-01T00:00:00Z] Line ").append(i).append("\n");
        }
        Files.writeString(todayFile, sb.toString());

        // Next append should be silently dropped
        journal.append("This should be dropped");

        var lines = journal.loadDay(LocalDate.now());
        assertThat(lines).hasSize(DailyJournal.MAX_LINES_PER_FILE);
        assertThat(lines).noneMatch(l -> l.contains("This should be dropped"));
    }

    @Test
    void blankEntriesIgnored() {
        journal.append("");
        journal.append("   ");
        journal.append(null);

        var todayFile = journal.fileForDate(LocalDate.now());
        assertThat(Files.exists(todayFile)).isFalse();
    }

    @Test
    void journalDirCreated() {
        assertThat(Files.isDirectory(journal.journalDir())).isTrue();
    }

    // =========================================================================
    // Concurrent write safety
    // =========================================================================

    @Test
    void concurrentAppendsProduceValidLines() throws Exception {
        int threadCount = 8;
        int entriesPerThread = 20;
        var latch = new CountDownLatch(1);
        var errors = new AtomicInteger(0);

        try (ExecutorService executor = Executors.newFixedThreadPool(threadCount)) {
            for (int t = 0; t < threadCount; t++) {
                int threadId = t;
                executor.submit(() -> {
                    try {
                        latch.await();
                        for (int i = 0; i < entriesPerThread; i++) {
                            journal.append("Thread " + threadId + " entry " + i);
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                });
            }

            latch.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(errors.get()).isEqualTo(0);

        var lines = journal.loadDay(LocalDate.now());
        assertThat(lines).hasSize(threadCount * entriesPerThread);

        // Every line must be a complete, well-formed entry (no interleaving)
        for (var line : lines) {
            assertThat(line).matches("- \\[\\d{4}-.*\\] Thread \\d+ entry \\d+");
        }
    }

    @Test
    void concurrentAppendsRespectMaxLinesCap() throws Exception {
        // Pre-fill journal to near capacity
        int preFilled = DailyJournal.MAX_LINES_PER_FILE - 5;
        var todayFile = journal.fileForDate(LocalDate.now());
        var sb = new StringBuilder();
        for (int i = 0; i < preFilled; i++) {
            sb.append("- [2025-01-01T00:00:00Z] Pre-filled line ").append(i).append("\n");
        }
        Files.writeString(todayFile, sb.toString());

        // Launch 10 threads each trying to append 5 entries (50 attempts, only 5 should fit)
        int threadCount = 10;
        int entriesPerThread = 5;
        var latch = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(threadCount)) {
            for (int t = 0; t < threadCount; t++) {
                int threadId = t;
                executor.submit(() -> {
                    try {
                        latch.await();
                        for (int i = 0; i < entriesPerThread; i++) {
                            journal.append("Overflow " + threadId + "-" + i);
                        }
                    } catch (Exception ignored) {}
                });
            }

            latch.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        var lines = journal.loadDay(LocalDate.now());
        // Must not exceed MAX_LINES_PER_FILE
        assertThat(lines.size()).isLessThanOrEqualTo(DailyJournal.MAX_LINES_PER_FILE);
    }
}

package dev.aceclaw.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CandidateStoreTest {

    @TempDir
    Path tempDir;

    private CandidateStore store;

    @BeforeEach
    void setUp() throws Exception {
        store = new CandidateStore(tempDir);
        store.load();
    }

    @Test
    void upsertMergesSimilarObservations() {
        var t0 = Instant.parse("2026-02-22T00:00:00Z");
        var t1 = t0.plusSeconds(60);

        store.upsert(observation("command timeout after 120 seconds", "session:a", t0));
        store.upsert(observation("bash command timed out after 120 sec", "session:b", t1));

        var all = store.all();
        assertThat(all).hasSize(1);
        var c = all.getFirst();
        assertThat(c.evidenceCount()).isEqualTo(2);
        assertThat(c.successCount()).isEqualTo(2);
        assertThat(c.sourceRefs()).containsExactly("session:a", "session:b");
        assertThat(c.lastSeenAt()).isEqualTo(t1);
    }

    @Test
    void upsertKeepsDistinctCandidatesWhenBelowThreshold() {
        store.upsert(observation("permission denied on chmod", "session:a",
                Instant.parse("2026-02-22T00:00:00Z")));
        store.upsert(observation("network timeout while downloading", "session:b",
                Instant.parse("2026-02-22T00:01:00Z")));
        assertThat(store.all()).hasSize(2);
    }

    @Test
    void loadSkipsTamperedAndMalformedEntries() throws Exception {
        store.upsert(observation("command timeout after 120 seconds", "session:a",
                Instant.parse("2026-02-22T00:00:00Z")));
        Path file = tempDir.resolve("memory").resolve("candidates.jsonl");
        String content = Files.readString(file);
        String tampered = content.replace("timeout", "tampered-timeout");
        Files.writeString(file, tampered + "{\"bad-json\":\n");

        var reloaded = new CandidateStore(tempDir);
        reloaded.load();
        assertThat(reloaded.all()).isEmpty();
    }

    @Test
    void recentWindowLimitsMergeLookupRange() throws Exception {
        var narrowWindowStore = new CandidateStore(tempDir, Duration.ofDays(1), 0.72);
        narrowWindowStore.load();
        narrowWindowStore.upsert(observation("command timeout after 120 seconds", "session:a",
                Instant.parse("2026-01-01T00:00:00Z")));
        narrowWindowStore.upsert(observation("bash command timed out after 120 sec", "session:b",
                Instant.parse("2026-02-22T00:00:00Z")));
        assertThat(narrowWindowStore.all()).hasSize(2);
    }

    @Test
    void mergeBaselineAtTenThousandCandidates() throws IOException {
        var largeStore = new CandidateStore(tempDir.resolve("perf"));
        largeStore.load();
        Instant base = Instant.parse("2026-02-22T00:00:00Z");
        for (int i = 0; i < 10_000; i++) {
            largeStore.upsert(new CandidateStore.CandidateObservation(
                    MemoryEntry.Category.ERROR_RECOVERY,
                    CandidateKind.ERROR_RECOVERY,
                    "timeout error signature " + i,
                    "bash",
                    List.of("bash", "timeout", "sig-" + i),
                    0.8,
                    1,
                    0,
                    "seed:" + i,
                    base.minusSeconds(i)));
        }

        long start = System.nanoTime();
        largeStore.upsert(new CandidateStore.CandidateObservation(
                MemoryEntry.Category.ERROR_RECOVERY,
                CandidateKind.ERROR_RECOVERY,
                "timeout error signature 9999",
                "bash",
                List.of("bash", "timeout", "sig-9999"),
                0.9,
                1,
                0,
                "measure",
                base.plusSeconds(5)));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isLessThan(10_000);
    }

    private static CandidateStore.CandidateObservation observation(String content, String source, Instant at) {
        return new CandidateStore.CandidateObservation(
                MemoryEntry.Category.ERROR_RECOVERY,
                CandidateKind.ERROR_RECOVERY,
                content,
                "bash",
                List.of("bash", "timeout"),
                0.8,
                1,
                0,
                source,
                at
        );
    }
}

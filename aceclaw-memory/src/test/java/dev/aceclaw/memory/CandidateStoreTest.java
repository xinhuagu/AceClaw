package dev.aceclaw.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    void transitionShadowToPromoted() {
        var t0 = Instant.parse("2026-02-22T00:00:00Z");
        // Upsert enough observations to meet default promotion gates (evidence >= 2, score >= 0.75)
        store.upsert(observation("command timeout after 120 seconds", "session:a", t0));
        store.upsert(observation("bash command timed out after 120 sec", "session:b", t0.plusSeconds(60)));
        store.upsert(observation("command timeout after 120 seconds retry", "session:c", t0.plusSeconds(120)));

        var all = store.all();
        assertThat(all).hasSize(1);
        var candidate = all.getFirst();
        assertThat(candidate.evidenceCount()).isGreaterThanOrEqualTo(3);

        var transition = store.transition(candidate.id(), CandidateState.PROMOTED, "manual promotion");
        assertThat(transition).isPresent();
        assertThat(transition.get().toState()).isEqualTo(CandidateState.PROMOTED);

        // Verify state was persisted
        var promoted = store.byId(candidate.id());
        assertThat(promoted).isPresent();
        assertThat(promoted.get().state()).isEqualTo(CandidateState.PROMOTED);
    }

    @Test
    void transitionGateRejectionInsufficientEvidence() {
        store.upsert(observation("command timeout after 120 seconds", "session:a",
                Instant.parse("2026-02-22T00:00:00Z")));
        var candidate = store.all().getFirst();
        // Only 1 evidence, default gate requires 2
        var transition = store.transition(candidate.id(), CandidateState.PROMOTED, "test");
        assertThat(transition).isEmpty();
        // State unchanged
        assertThat(store.byId(candidate.id()).get().state()).isEqualTo(CandidateState.SHADOW);
    }

    @Test
    void evaluateAllBatchPromotionAndDemotion() throws Exception {
        var t0 = Instant.parse("2026-02-22T00:00:00Z");
        // Use low gates for testing
        var smConfig = new CandidateStateMachine.Config(2, 0.5, 0.5, 1, java.util.Set.of());
        var testStore = new CandidateStore(tempDir.resolve("eval-test"),
                Duration.ofDays(30), 0.50, smConfig);
        testStore.load();

        // Create a candidate that should be promoted (evidence=2, score=0.8)
        testStore.upsert(new CandidateStore.CandidateObservation(
                MemoryEntry.Category.ERROR_RECOVERY, CandidateKind.ERROR_RECOVERY,
                "timeout recovery strategy", "bash", List.of("bash", "timeout"),
                0.8, 1, 0, "src:a", t0));
        testStore.upsert(new CandidateStore.CandidateObservation(
                MemoryEntry.Category.ERROR_RECOVERY, CandidateKind.ERROR_RECOVERY,
                "bash timeout recovery strategy", "bash", List.of("bash", "timeout"),
                0.8, 1, 0, "src:b", t0.plusSeconds(60)));

        var transitions = testStore.evaluateAll();
        assertThat(transitions).hasSize(1);
        assertThat(transitions.getFirst().toState()).isEqualTo(CandidateState.PROMOTED);

        // Verify promoted
        assertThat(testStore.byState(CandidateState.PROMOTED)).hasSize(1);
        assertThat(testStore.byState(CandidateState.SHADOW)).isEmpty();
    }

    @Test
    void transitionAuditLogPersisted() throws Exception {
        var t0 = Instant.parse("2026-02-22T00:00:00Z");
        store.upsert(observation("command timeout after 120 seconds", "session:a", t0));
        store.upsert(observation("bash command timed out after 120 sec", "session:b", t0.plusSeconds(60)));
        store.upsert(observation("command timeout after 120 seconds retry", "session:c", t0.plusSeconds(120)));

        var candidate = store.all().getFirst();
        store.transition(candidate.id(), CandidateState.PROMOTED, "test promotion");

        Path transitionsFile = tempDir.resolve("memory").resolve("candidate-transitions.jsonl");
        assertThat(transitionsFile).exists();
        var lines = Files.readAllLines(transitionsFile);
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst()).contains("PROMOTED");
    }

    @Test
    void evaluateAllAllowsDemotedCandidateToBeRePromoted() throws Exception {
        var t0 = Instant.parse("2026-02-22T00:00:00Z");
        var smConfig = new CandidateStateMachine.Config(
                2, 0.5, 1.0, 1,
                Duration.ofDays(30), Duration.ofDays(7), 2, 0.6, 2, Duration.ZERO,
                java.util.Set.of());
        var testStore = new CandidateStore(tempDir.resolve("repromote-test"),
                Duration.ofDays(30), 0.50, smConfig);
        testStore.load();

        testStore.upsert(observation("timeout recovery strategy", "src:a", t0));
        testStore.upsert(observation("timeout recovery strategy improved", "src:b", t0.plusSeconds(30)));
        testStore.evaluateAll();
        assertThat(testStore.byState(CandidateState.PROMOTED)).hasSize(1);

        for (int i = 0; i < 4; i++) {
            testStore.upsert(new CandidateStore.CandidateObservation(
                    MemoryEntry.Category.ERROR_RECOVERY, CandidateKind.ERROR_RECOVERY,
                    "timeout recovery strategy", "bash", List.of("bash", "timeout"),
                    0.8, 0, 1, "fail:" + i, t0.plusSeconds(120 + i)));
        }
        testStore.evaluateAll();
        assertThat(testStore.byState(CandidateState.DEMOTED)).hasSize(1);

        for (int i = 0; i < 4; i++) {
            testStore.upsert(new CandidateStore.CandidateObservation(
                    MemoryEntry.Category.ERROR_RECOVERY, CandidateKind.ERROR_RECOVERY,
                    "timeout recovery strategy", "bash", List.of("bash", "timeout"),
                    0.8, 1, 0, "recover:" + i, t0.plus(Duration.ofDays(5)).plusSeconds(i)));
        }
        var transitions = testStore.evaluateAll();
        assertThat(transitions).anyMatch(t -> t.toState() == CandidateState.PROMOTED);
        assertThat(testStore.byState(CandidateState.PROMOTED)).hasSize(1);
    }

    @Test
    void antiPatternCandidateBlocksPromotionForSameTool() throws Exception {
        var t0 = Instant.parse("2026-02-22T00:00:00Z");
        var smConfig = new CandidateStateMachine.Config(
                2, 0.5, 1.0, 1,
                Duration.ofDays(30), Duration.ofDays(7), 2, 0.6, 2, Duration.ZERO,
                java.util.Set.of());
        var testStore = new CandidateStore(tempDir.resolve("anti-pattern-gate"),
                Duration.ofDays(30), 0.50, smConfig);
        testStore.load();

        testStore.upsert(new CandidateStore.CandidateObservation(
                MemoryEntry.Category.ERROR_RECOVERY, CandidateKind.ERROR_RECOVERY,
                "bash timeout recovery strategy", "bash", List.of("bash", "timeout"),
                0.9, 1, 0, "src:a", t0));
        testStore.upsert(new CandidateStore.CandidateObservation(
                MemoryEntry.Category.ERROR_RECOVERY, CandidateKind.ERROR_RECOVERY,
                "bash timeout recovery strategy updated", "bash", List.of("bash", "timeout"),
                0.9, 1, 0, "src:b", t0.plusSeconds(60)));

        testStore.upsert(new CandidateStore.CandidateObservation(
                MemoryEntry.Category.ANTI_PATTERN, CandidateKind.ANTI_PATTERN,
                "Avoid bash timeout retries", "bash", List.of("bash", "anti-pattern"),
                0.95, 0, 1, "src:anti", t0.plusSeconds(120),
                true, true, "anti-pattern-generated", null));

        var transitions = testStore.evaluateAll();
        assertThat(transitions).isEmpty();
        assertThat(testStore.byState(CandidateState.PROMOTED)).isEmpty();
    }

    @Test
    void antiPatternCandidateBlocksRePromotionForDemotedCandidate() throws Exception {
        var t0 = Instant.parse("2026-02-22T00:00:00Z");
        var smConfig = new CandidateStateMachine.Config(
                2, 0.5, 1.0, 1,
                Duration.ofDays(30), Duration.ofDays(7), 2, 0.6, 2, Duration.ZERO,
                java.util.Set.of());
        var testStore = new CandidateStore(tempDir.resolve("anti-pattern-repromote"),
                Duration.ofDays(30), 0.50, smConfig);
        testStore.load();

        testStore.upsert(observation("timeout recovery strategy", "src:a", t0));
        testStore.upsert(observation("timeout recovery strategy improved", "src:b", t0.plusSeconds(30)));
        testStore.evaluateAll();
        assertThat(testStore.byState(CandidateState.PROMOTED)).hasSize(1);

        for (int i = 0; i < 4; i++) {
            testStore.upsert(new CandidateStore.CandidateObservation(
                    MemoryEntry.Category.ERROR_RECOVERY, CandidateKind.ERROR_RECOVERY,
                    "timeout recovery strategy", "bash", List.of("bash", "timeout"),
                    0.8, 0, 1, "fail:" + i, t0.plusSeconds(120 + i)));
        }
        testStore.evaluateAll();
        assertThat(testStore.byState(CandidateState.DEMOTED)).hasSize(1);

        testStore.upsert(new CandidateStore.CandidateObservation(
                MemoryEntry.Category.ANTI_PATTERN, CandidateKind.ANTI_PATTERN,
                "Avoid bash timeout retries", "bash", List.of("bash", "anti-pattern"),
                0.95, 0, 1, "src:anti", t0.plusSeconds(400),
                true, true, "anti-pattern-generated", null));
        testStore.upsert(new CandidateStore.CandidateObservation(
                MemoryEntry.Category.ERROR_RECOVERY, CandidateKind.ERROR_RECOVERY,
                "timeout recovery strategy", "bash", List.of("bash", "timeout"),
                0.8, 1, 0, "recover:1", t0.plus(Duration.ofDays(5))));

        var transitions = testStore.evaluateAll();
        assertThat(transitions).noneMatch(t -> t.toState() == CandidateState.PROMOTED);
        assertThat(testStore.byState(CandidateState.PROMOTED)).isEmpty();
        assertThat(testStore.byState(CandidateState.DEMOTED)).hasSize(1);
    }

    @Test
    void byStateFiltersCorrectly() {
        var t0 = Instant.parse("2026-02-22T00:00:00Z");
        store.upsert(observation("strategy alpha", "session:a", t0));
        store.upsert(observation("strategy beta completely different", "session:b", t0.plusSeconds(60)));
        assertThat(store.byState(CandidateState.SHADOW)).hasSize(2);
        assertThat(store.byState(CandidateState.PROMOTED)).isEmpty();
    }

    @Test
    void byIdFindsExistingCandidate() {
        store.upsert(observation("command timeout after 120 seconds", "session:a",
                Instant.parse("2026-02-22T00:00:00Z")));
        var candidate = store.all().getFirst();
        assertThat(store.byId(candidate.id())).isPresent();
        assertThat(store.byId("nonexistent")).isEmpty();
    }

    @Test
    void loadMigratesLegacyV1CandidateAndRewritesFile() throws Exception {
        Path memoryDir = tempDir.resolve("memory");
        Files.createDirectories(memoryDir);
        byte[] key = Files.readAllBytes(memoryDir.resolve("memory.key"));
        var signer = new MemorySigner(key);

        var id = "legacy-1";
        var category = MemoryEntry.Category.ERROR_RECOVERY;
        var kind = CandidateKind.ERROR_RECOVERY;
        var state = CandidateState.SHADOW;
        var content = "legacy timeout recovery";
        var toolTag = "bash";
        var tags = List.of("bash", "timeout");
        var score = 0.8;
        var evidenceCount = 2;
        var successCount = 1;
        var failureCount = 0;
        var firstSeenAt = Instant.parse("2026-02-20T00:00:00Z");
        var lastSeenAt = Instant.parse("2026-02-22T00:00:00Z");
        var sourceRefs = List.of("legacy:session");

        String legacyPayload = id + "|" + category + "|" + kind + "|" + state + "|" + content + "|" + toolTag + "|"
                + String.join(",", tags) + "|" + score + "|" + evidenceCount + "|" + successCount + "|"
                + failureCount + "|" + firstSeenAt + "|" + lastSeenAt + "|" + String.join(",", sourceRefs);
        String hmac = signer.sign(legacyPayload);

        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        var legacyNode = mapper.createObjectNode();
        legacyNode.put("id", id);
        legacyNode.put("category", category.name());
        legacyNode.put("kind", kind.name());
        legacyNode.put("state", state.name());
        legacyNode.put("content", content);
        legacyNode.put("toolTag", toolTag);
        legacyNode.set("tags", mapper.valueToTree(tags));
        legacyNode.put("score", score);
        legacyNode.put("evidenceCount", evidenceCount);
        legacyNode.put("successCount", successCount);
        legacyNode.put("failureCount", failureCount);
        legacyNode.put("firstSeenAt", firstSeenAt.toString());
        legacyNode.put("lastSeenAt", lastSeenAt.toString());
        legacyNode.set("sourceRefs", mapper.valueToTree(sourceRefs));
        legacyNode.put("hmac", hmac);

        Files.writeString(memoryDir.resolve("candidates.jsonl"), mapper.writeValueAsString(legacyNode) + "\n");

        var reloaded = new CandidateStore(tempDir);
        reloaded.load();

        var migrated = reloaded.all();
        assertThat(migrated).hasSize(1);
        var c = migrated.getFirst();
        assertThat(c.version()).isEqualTo(LearningCandidate.CURRENT_VERSION);
        assertThat(c.evidence()).isNotEmpty();
        assertThat(c.evidence().getFirst().sourceRef()).isEqualTo("legacy:session");

        String rewritten = Files.readString(memoryDir.resolve("candidates.jsonl"));
        assertThat(rewritten).contains("\"version\":" + LearningCandidate.CURRENT_VERSION);
    }

    @Test
    void manualRollbackForceTransitionBypassesDemotionGate() {
        var t0 = Instant.parse("2026-02-22T00:00:00Z");
        store.upsert(observation("rollback strategy", "session:a", t0));
        store.upsert(observation("rollback strategy v2", "session:b", t0.plusSeconds(60)));
        store.upsert(observation("rollback strategy v3", "session:c", t0.plusSeconds(120)));
        var candidate = store.all().getFirst();
        store.transition(candidate.id(), CandidateState.PROMOTED, "promote");

        // Should succeed even without regression metrics because this is a manual rollback path.
        var rollback = store.rollbackPromoted(candidate.id(), "manual rollback");
        assertThat(rollback).isPresent();
        assertThat(rollback.get().reasonCode()).isEqualTo("MANUAL_ROLLBACK");
        assertThat(store.byId(candidate.id())).isPresent();
        assertThat(store.byId(candidate.id()).get().state()).isEqualTo(CandidateState.DEMOTED);
    }

    @Test
    void recordOutcomeWritesBackAndCanTriggerDemotion() throws Exception {
        var t0 = Instant.parse("2026-02-22T00:00:00Z");
        var smConfig = new CandidateStateMachine.Config(
                1, 0.1, 1.0, 1,
                Duration.ofDays(14), Duration.ofDays(7), 1, 0.2, 1, Duration.ZERO,
                Set.of());
        var testStore = new CandidateStore(tempDir.resolve("outcome-writeback"),
                Duration.ofDays(30), 0.50, smConfig);
        testStore.load();
        testStore.upsert(observation("outcome strategy", "src:a", t0));
        testStore.evaluateAll();

        var promoted = testStore.byState(CandidateState.PROMOTED);
        assertThat(promoted).hasSize(1);
        var candidateId = promoted.getFirst().id();

        var updated = testStore.recordOutcome(candidateId, new CandidateStore.CandidateOutcome(
                false, true, false, "runtime:s1", "runtime-outcome:error",
                t0.plusSeconds(30), null));
        assertThat(updated).isPresent();
        assertThat(updated.get().failureCount()).isGreaterThanOrEqualTo(1);
        assertThat(updated.get().evidenceCount()).isGreaterThanOrEqualTo(2);

        testStore.evaluateAll();
        assertThat(testStore.byId(candidateId)).isPresent();
        assertThat(testStore.byId(candidateId).get().state()).isEqualTo(CandidateState.DEMOTED);
    }

    @Test
    void maintenanceRemovesStaleCandidatesAndDecaysOldScores() throws Exception {
        var clock = new MutableClock(Instant.parse("2026-02-24T00:00:00Z"));
        var t0 = clock.instant();
        var smConfig = new CandidateStateMachine.Config(1, 0.1, 1.0, 3, Set.of());
        var maintenanceStore = new CandidateStore(
                tempDir.resolve("maintenance"),
                Duration.ofDays(30),
                0.50,
                smConfig,
                Duration.ofDays(30),     // retention
                Duration.ofSeconds(1),   // decay half-life
                Duration.ofHours(1),     // decay grace
                Duration.ZERO,           // run maintenance every evaluateAll call
                clock);
        maintenanceStore.load();

        maintenanceStore.upsert(observation("stale strategy", "stale", t0.minus(Duration.ofDays(40))));
        maintenanceStore.upsert(observation("old strategy", "old", t0.minus(Duration.ofDays(2))));
        var before = maintenanceStore.all();
        assertThat(before).hasSize(2);
        var oldCandidate = before.stream().filter(c -> c.sourceRefs().contains("old")).findFirst().orElseThrow();
        var oldScoreBefore = oldCandidate.score();

        clock.advance(Duration.ofMillis(1100));
        maintenanceStore.evaluateAll();

        var after = maintenanceStore.all();
        assertThat(after).hasSize(1);
        assertThat(after.getFirst().sourceRefs()).contains("old");
        assertThat(after.getFirst().score()).isLessThan(oldScoreBefore);
    }

    @Test
    void concurrentUpsertOutcomeAndEvaluateRemainConsistent() throws Exception {
        var t0 = Instant.parse("2026-02-22T00:00:00Z");
        var testStore = new CandidateStore(tempDir.resolve("concurrent"));
        testStore.load();
        testStore.upsert(observation("concurrent strategy", "seed", t0));
        var candidateId = testStore.all().getFirst().id();

        int threads = 8;
        int iterations = 40;
        var errors = new AtomicInteger();
        var latch = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        latch.await();
                        for (int i = 0; i < iterations; i++) {
                            if ((i + threadId) % 3 == 0) {
                                testStore.upsert(observation(
                                        "concurrent strategy " + threadId + "-" + i,
                                        "src:" + threadId + ":" + i,
                                        t0.plusSeconds(i)));
                            } else {
                                testStore.recordOutcome(candidateId, new CandidateStore.CandidateOutcome(
                                        i % 2 == 0,
                                        i % 7 == 0,
                                        false,
                                        "runtime:t" + threadId,
                                        "concurrent",
                                        t0.plusSeconds(i),
                                        null));
                            }
                            if (i % 10 == 0) {
                                testStore.evaluateAll();
                            }
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                });
            }
            latch.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(errors.get()).isZero();
        assertThat(testStore.all()).isNotEmpty();
        assertThat(testStore.byId(candidateId)).isPresent();
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

    private static final class MutableClock extends Clock {
        private Instant now;
        private final ZoneId zone;

        private MutableClock(Instant now) {
            this(now, ZoneOffset.UTC);
        }

        private MutableClock(Instant now, ZoneId zone) {
            this.now = now;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }
    }
}

package dev.aceclaw.daemon;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class LearningMaintenanceSchedulerTest {

    @Test
    void sessionCountTriggerRunsAfterThreshold() throws Exception {
        var clock = new MutableClock(Instant.parse("2026-03-13T10:00:00Z"));
        var activeSessions = new AtomicInteger(0);
        var memoryBytes = new AtomicLong(0);
        var triggers = Collections.synchronizedList(new ArrayList<String>());
        var scheduler = scheduler(clock, activeSessions, memoryBytes, triggers);
        try {
            for (int i = 0; i < 9; i++) {
                scheduler.onSessionClosed();
            }
            assertThat(triggers).isEmpty();

            scheduler.onSessionClosed();
            waitForTriggers(triggers, 1);

            assertThat(triggers).containsExactly("session-count");
        } finally {
            scheduler.stop();
        }
    }

    @Test
    void timeTriggerRunsAfterConfiguredInterval() throws Exception {
        var clock = new MutableClock(Instant.parse("2026-03-13T10:00:00Z"));
        var activeSessions = new AtomicInteger(0);
        var memoryBytes = new AtomicLong(0);
        var triggers = Collections.synchronizedList(new ArrayList<String>());
        var scheduler = scheduler(clock, activeSessions, memoryBytes, triggers);
        try {
            clock.advance(Duration.ofHours(6).plusSeconds(1));
            scheduler.tick();
            waitForTriggers(triggers, 1);

            assertThat(triggers).containsExactly("scheduled");
        } finally {
            scheduler.stop();
        }
    }

    @Test
    void sizeTriggerRunsOncePerThresholdCrossing() throws Exception {
        var clock = new MutableClock(Instant.parse("2026-03-13T10:00:00Z"));
        var activeSessions = new AtomicInteger(0);
        var memoryBytes = new AtomicLong(0);
        var triggers = Collections.synchronizedList(new ArrayList<String>());
        var scheduler = scheduler(clock, activeSessions, memoryBytes, triggers);
        try {
            memoryBytes.set(60L * 1024L);
            scheduler.tick();
            waitForTriggers(triggers, 1);
            assertThat(triggers).containsExactly("size-threshold");

            scheduler.tick();
            Thread.sleep(50);
            assertThat(triggers).containsExactly("size-threshold");

            memoryBytes.set(10L * 1024L);
            scheduler.tick();
            memoryBytes.set(75L * 1024L);
            scheduler.tick();
            waitForTriggers(triggers, 2);
            assertThat(triggers).containsExactly("size-threshold", "size-threshold");
        } finally {
            scheduler.stop();
        }
    }

    @Test
    void idleTriggerRunsAfterIdleInterval() throws Exception {
        var clock = new MutableClock(Instant.parse("2026-03-13T10:00:00Z"));
        var activeSessions = new AtomicInteger(1);
        var memoryBytes = new AtomicLong(0);
        var triggers = Collections.synchronizedList(new ArrayList<String>());
        var scheduler = scheduler(clock, activeSessions, memoryBytes, triggers);
        try {
            scheduler.tick();
            activeSessions.set(0);
            clock.advance(Duration.ofMinutes(5).plusSeconds(1));
            scheduler.tick();
            waitForTriggers(triggers, 1);

            assertThat(triggers).containsExactly("idle");
        } finally {
            scheduler.stop();
        }
    }

    @Test
    void sessionCloseDoesNotTriggerWhileStopped() throws Exception {
        var clock = new MutableClock(Instant.parse("2026-03-13T10:00:00Z"));
        var activeSessions = new AtomicInteger(0);
        var memoryBytes = new AtomicLong(0);
        var triggers = Collections.synchronizedList(new ArrayList<String>());
        var scheduler = scheduler(clock, activeSessions, memoryBytes, triggers);
        scheduler.stop();

        for (int i = 0; i < 20; i++) {
            scheduler.onSessionClosed();
        }
        Thread.sleep(50);

        assertThat(triggers).isEmpty();
    }

    private static LearningMaintenanceScheduler scheduler(MutableClock clock,
                                                          AtomicInteger activeSessions,
                                                          AtomicLong memoryBytes,
                                                          List<String> triggers) {
        var scheduler = new LearningMaintenanceScheduler(
                new LearningMaintenanceScheduler.Config(
                        Duration.ofHours(6),
                        10,
                        50L * 1024L,
                        Duration.ofMinutes(5),
                        Duration.ofDays(1)),
                clock,
                activeSessions::get,
                memoryBytes::get,
                trigger -> triggers.add(trigger)
        );
        scheduler.start();
        return scheduler;
    }

    private static void waitForTriggers(List<String> triggers, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < deadline) {
            synchronized (triggers) {
                if (triggers.size() >= expected) {
                    return;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out waiting for trigger count " + expected + ", got " + triggers.size());
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}

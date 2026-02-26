package dev.aceclaw.core.util;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WaitSupportTest {

    @Test
    void awaitConditionReturnsFalseOnTimeout() throws Exception {
        boolean matched = WaitSupport.awaitCondition(
                () -> false,
                Duration.ofMillis(120),
                Duration.ofMillis(10));
        assertThat(matched).isFalse();
    }

    @Test
    void awaitConditionReturnsTrueWhenPredicateBecomesTrue() throws Exception {
        var ready = new AtomicBoolean(false);
        var setter = Thread.ofVirtual().start(() -> {
            try {
                WaitSupport.sleepInterruptibly(Duration.ofMillis(50));
                ready.set(true);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });

        boolean matched = WaitSupport.awaitCondition(
                ready::get,
                Duration.ofSeconds(1),
                Duration.ofMillis(10));
        assertThat(matched).isTrue();
        setter.join(Duration.ofMillis(200));
    }

    @Test
    void sleepInterruptiblyPropagatesInterrupt() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> WaitSupport.sleepInterruptibly(Duration.ofMillis(5)))
                    .isInstanceOf(InterruptedException.class);
        } finally {
            Thread.interrupted();
        }
    }
}

package dev.aceclaw.core.agent;

import dev.aceclaw.core.llm.StreamEventHandler;
import dev.aceclaw.core.llm.StreamSession;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CancellationToken}.
 */
class CancellationTokenTest {

    @Test
    void cancelSetsFlag() {
        var token = new CancellationToken();
        assertThat(token.isCancelled()).isFalse();

        token.cancel();

        assertThat(token.isCancelled()).isTrue();
    }

    @Test
    void cancelIsIdempotent() {
        var token = new CancellationToken();

        token.cancel();
        token.cancel();
        token.cancel();

        assertThat(token.isCancelled()).isTrue();
    }

    @Test
    void cancelCancelsActiveSession() {
        var token = new CancellationToken();
        var session = new SpyStreamSession();
        token.setActiveSession(session);

        token.cancel();

        assertThat(session.wasCancelled()).isTrue();
    }

    @Test
    void setActiveSessionOnAlreadyCancelledToken() {
        var token = new CancellationToken();
        token.cancel();

        var session = new SpyStreamSession();
        token.setActiveSession(session);

        // Session should be cancelled immediately because the token was already cancelled
        assertThat(session.wasCancelled()).isTrue();
    }

    @Test
    void cancelWithNoActiveSession() {
        var token = new CancellationToken();
        // Should not throw NPE
        token.cancel();
        assertThat(token.isCancelled()).isTrue();
    }

    /**
     * Minimal StreamSession spy that tracks whether cancel() was called.
     */
    private static final class SpyStreamSession implements StreamSession {

        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        @Override
        public void onEvent(StreamEventHandler handler) {
            // no-op
        }

        @Override
        public void cancel() {
            cancelled.set(true);
        }

        boolean wasCancelled() {
            return cancelled.get();
        }
    }
}

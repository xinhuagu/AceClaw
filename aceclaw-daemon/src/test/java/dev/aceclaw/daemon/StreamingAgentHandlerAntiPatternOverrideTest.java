package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StreamingAgentHandlerAntiPatternOverrideTest {

    @Test
    void setGetAndClearOverrideWorks() {
        var handler = new StreamingAgentHandler(null, null, null, null, new ObjectMapper());

        handler.setAntiPatternGateOverride("session-1", "bash", 120, "temporary bypass");
        var active = handler.getAntiPatternGateOverride("session-1", "bash");
        assertThat(active.active()).isTrue();
        assertThat(active.ttlSecondsRemaining()).isGreaterThan(0L);
        assertThat(active.reason()).isEqualTo("temporary bypass");

        boolean cleared = handler.clearAntiPatternGateOverride("session-1", "bash");
        assertThat(cleared).isTrue();
        var inactive = handler.getAntiPatternGateOverride("session-1", "bash");
        assertThat(inactive.active()).isFalse();
    }

    @Test
    void clearSessionOverrideRemovesSessionGateOverrides() {
        var handler = new StreamingAgentHandler(null, null, null, null, new ObjectMapper());

        handler.setAntiPatternGateOverride("session-a", "bash", 300, "manual");
        handler.setAntiPatternGateOverride("session-a", "applescript", 300, "manual");
        handler.setAntiPatternGateOverride("session-b", "bash", 300, "manual");

        handler.clearSessionOverride("session-a");

        assertThat(handler.getAntiPatternGateOverride("session-a", "bash").active()).isFalse();
        assertThat(handler.getAntiPatternGateOverride("session-a", "applescript").active()).isFalse();
        assertThat(handler.getAntiPatternGateOverride("session-b", "bash").active()).isTrue();
    }
}

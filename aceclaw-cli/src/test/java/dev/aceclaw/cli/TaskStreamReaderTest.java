package dev.aceclaw.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@code agent.prompt} JSON-RPC envelope built by
 * {@link TaskStreamReader#buildPromptRequest}. The CLI's /plan slash
 * command passes {@code forcePlan=true} all the way down to here; the
 * daemon-side branch in {@code StreamingAgentHandler.handlePrompt}
 * checks for the same field. These tests pin the wire shape so a
 * field rename on either side fails loudly at unit-test time instead
 * of silently disabling forced planning.
 */
final class TaskStreamReaderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void buildPromptRequest_omitsForcePlanByDefault() {
        // Older daemons don't know about forcePlan. Make sure we don't
        // gratuitously emit the field for normal prompts — keeps the
        // wire shape backward-compatible.
        var req = TaskStreamReader.buildPromptRequest(
                mapper, "sess-1", "hello", 7L, false);

        assertThat(req.get("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(req.get("method").asText()).isEqualTo("agent.prompt");
        assertThat(req.get("id").asLong()).isEqualTo(7L);

        var params = req.get("params");
        assertThat(params.get("sessionId").asText()).isEqualTo("sess-1");
        assertThat(params.get("prompt").asText()).isEqualTo("hello");
        assertThat(params.has("forcePlan"))
                .as("default request must not carry forcePlan")
                .isFalse();
    }

    @Test
    void buildPromptRequest_emitsForcePlanWhenTrue() {
        // /plan slash command path: the field MUST be present and true
        // so the daemon's handlePrompt skips the ComplexityEstimator
        // and runs the planner unconditionally.
        var req = TaskStreamReader.buildPromptRequest(
                mapper, "sess-1", "refactor X", 8L, true);

        var params = req.get("params");
        assertThat(params.get("forcePlan").asBoolean())
                .as("forcePlan must be true on the wire when CLI requested it")
                .isTrue();
    }

    @Test
    void buildPromptRequest_fieldNameIsExactlyForcePlan() {
        // Pin the exact field name. Daemon-side handlePrompt reads
        // params.get("forcePlan") — any rename here would silently
        // disable the feature without breaking anything else.
        var req = TaskStreamReader.buildPromptRequest(
                mapper, "s", "p", 1L, true);

        var fieldNames = new java.util.ArrayList<String>();
        req.get("params").fieldNames().forEachRemaining(fieldNames::add);
        assertThat(fieldNames).contains("forcePlan");
    }
}

package dev.aceclaw.llm.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.llm.StopReason;
import dev.aceclaw.core.llm.StreamEvent;
import dev.aceclaw.core.llm.StreamEventHandler;
import org.junit.jupiter.api.Test;

import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link OpenAIStreamSession}'s handling of terminal stop reasons, in particular the
 * OpenAI-compatible quirk where usage arrives in a trailing chunk with empty {@code choices}.
 */
class OpenAIStreamSessionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static List<StreamEvent.MessageDelta> run(List<String> sseData) {
        var mapper = new OpenAIMapper(JSON);
        var lines = sseData.stream().map(d -> "data: " + d).toList();
        var session = new OpenAIStreamSession(new FakeStreamResponse(lines), mapper);

        var deltas = new ArrayList<StreamEvent.MessageDelta>();
        session.onEvent(new StreamEventHandler() {
            @Override
            public void onMessageDelta(StreamEvent.MessageDelta event) {
                deltas.add(event);
            }
        });
        return deltas;
    }

    /**
     * P2 regression: a truncated response reports finish_reason="length" and then sends usage in a
     * separate trailing chunk with empty choices. The trailing chunk must NOT clobber MAX_TOKENS
     * with END_TURN — otherwise a partial tool_use in the truncated turn gets promoted+executed.
     */
    @Test
    void trailingUsageChunkPreservesMaxTokensStopReason() {
        var deltas = run(List.of(
                "{\"id\":\"c1\",\"model\":\"m\",\"choices\":[{\"delta\":{\"content\":\"partial\"},"
                        + "\"finish_reason\":\"length\"}]}",
                // Trailing usage-only chunk with empty choices (Ollama/OpenAI-compat quirk)
                "{\"id\":\"c1\",\"model\":\"m\",\"choices\":[],"
                        + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}",
                "[DONE]"));

        // The final terminal stop reason the accumulator sees must remain MAX_TOKENS.
        assertThat(deltas).isNotEmpty();
        assertThat(deltas.getLast().stopReason()).isEqualTo(StopReason.MAX_TOKENS);
        // Usage from the trailing chunk still reaches the client.
        assertThat(deltas.getLast().usage().outputTokens()).isEqualTo(5);
    }

    /**
     * When the trailing usage chunk is the sole terminator (no prior finish_reason), it falls back
     * to END_TURN so token counts still reach the client.
     */
    @Test
    void trailingUsageChunkWithNoPriorFinishReasonFallsBackToEndTurn() {
        var deltas = run(List.of(
                "{\"id\":\"c1\",\"model\":\"m\",\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}",
                "{\"id\":\"c1\",\"model\":\"m\",\"choices\":[],"
                        + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}",
                "[DONE]"));

        assertThat(deltas).isNotEmpty();
        assertThat(deltas.getLast().stopReason()).isEqualTo(StopReason.END_TURN);
    }

    /** A normal tool-call response maps finish_reason="tool_calls" to TOOL_USE. */
    @Test
    void toolCallsFinishReasonMapsToToolUse() {
        var deltas = run(List.of(
                "{\"id\":\"c1\",\"model\":\"m\",\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"id\":\"t1\",\"function\":{\"name\":\"web_search\",\"arguments\":\"{}\"}}]},"
                        + "\"finish_reason\":\"tool_calls\"}]}",
                "[DONE]"));

        assertThat(deltas).isNotEmpty();
        assertThat(deltas.getLast().stopReason()).isEqualTo(StopReason.TOOL_USE);
    }

    /** Minimal HttpResponse whose body() replays canned SSE lines as a Stream. */
    private record FakeStreamResponse(List<String> lines) implements HttpResponse<Stream<String>> {
        @Override public int statusCode() { return 200; }
        @Override public HttpRequest request() { return null; }
        @Override public Optional<HttpResponse<Stream<String>>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(java.util.Map.of(), (a, b) -> true); }
        @Override public Stream<String> body() { return lines.stream(); }
        @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
        @Override public java.net.URI uri() { return java.net.URI.create("http://test"); }
        @Override public java.net.http.HttpClient.Version version() { return java.net.http.HttpClient.Version.HTTP_1_1; }
    }
}

package dev.aceclaw.llm.openai;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.llm.ContentBlock;
import dev.aceclaw.core.llm.StopReason;
import dev.aceclaw.core.llm.StreamEvent;
import dev.aceclaw.core.llm.StreamEventHandler;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAIStreamSessionTest {

    @Test
    void standardOpenAIIncrementalToolCallStreaming() {
        var mapper = new OpenAIMapper(new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false));
        var session = new OpenAIStreamSession(response(Stream.of(
                // First chunk: tool call header with id, name, empty args
                """
                data: {"id":"chatcmpl-100","model":"gpt-4","choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call_abc","type":"function","function":{"name":"read_file","arguments":""}}]},"finish_reason":null}]}
                """.strip(),
                // Second chunk: argument fragment
                """
                data: {"id":"chatcmpl-100","model":"gpt-4","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\\"path\\":"}}]},"finish_reason":null}]}
                """.strip(),
                // Third chunk: argument fragment
                """
                data: {"id":"chatcmpl-100","model":"gpt-4","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\"/tmp/a\\"}"}}]},"finish_reason":null}]}
                """.strip(),
                // Finish
                """
                data: {"id":"chatcmpl-100","model":"gpt-4","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":20,"completion_tokens":8}}
                """.strip(),
                "data: [DONE]"
        )), mapper);

        var handler = new CapturingHandler();
        session.onEvent(handler);

        // Verify incremental deltas were emitted (not batched at flush)
        assertThat(handler.toolUseDeltaCount).as("should stream deltas incrementally").isGreaterThanOrEqualTo(2);
        assertThat(handler.toolUses)
                .extracting(ContentBlock.ToolUse::name)
                .containsExactly("read_file");
        assertThat(handler.toolUses)
                .extracting(ContentBlock.ToolUse::inputJson)
                .containsExactly("{\"path\":\"/tmp/a\"}");
        assertThat(handler.stopReasons).containsOnly(StopReason.TOOL_USE);
        assertThat(handler.completed).isTrue();
    }

    @Test
    void ollamaToolCallsWithoutIndexAndStopFinishReasonStillBecomeToolUse() {
        var mapper = new OpenAIMapper(new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false), "ollama");
        var session = new OpenAIStreamSession(response(Stream.of(
                """
                data: {"id":"chatcmpl-914","model":"llama3.1","choices":[{"delta":{"role":"assistant","content":"","tool_calls":[{"id":"call_a","type":"function","function":{"name":"read_file","arguments":"{\\"path\\":\\"/tmp/a\\"}"}},{"id":"call_b","type":"function","function":{"name":"grep","arguments":"{\\"pattern\\":\\"TODO\\"}"}}]},"index":0}]}
                """.strip(),
                """
                data: {"id":"chatcmpl-914","model":"llama3.1","choices":[{"delta":{"role":"assistant","content":""},"finish_reason":"stop","index":0}]}
                """.strip(),
                """
                data: {"id":"chatcmpl-914","model":"llama3.1","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":3}}
                """.strip(),
                "data: [DONE]"
        )), mapper);

        var handler = new CapturingHandler();
        session.onEvent(handler);

        assertThat(handler.toolUses)
                .extracting(ContentBlock.ToolUse::name)
                .containsExactly("read_file", "grep");
        assertThat(handler.toolUses)
                .extracting(ContentBlock.ToolUse::inputJson)
                .containsExactly("{\"path\":\"/tmp/a\"}", "{\"pattern\":\"TODO\"}");
        assertThat(handler.stopReasons).containsOnly(StopReason.TOOL_USE);
        assertThat(handler.completed).isTrue();
    }

    @Test
    void maxTokensToolCallStreamIsNotCoercedToToolUse() {
        var mapper = new OpenAIMapper(new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false), "ollama");
        var session = new OpenAIStreamSession(response(Stream.of(
                """
                data: {"id":"chatcmpl-915","model":"llama3.1","choices":[{"delta":{"role":"assistant","content":"","tool_calls":[{"id":"call_a","type":"function","function":{"name":"read_file","arguments":"{\\"path\\":"}}]},"index":0}]}
                """.strip(),
                """
                data: {"id":"chatcmpl-915","model":"llama3.1","choices":[{"delta":{},"finish_reason":"length","index":0}]}
                """.strip(),
                "data: [DONE]"
        )), mapper);

        var handler = new CapturingHandler();
        session.onEvent(handler);

        assertThat(handler.stopReasons).containsOnly(StopReason.MAX_TOKENS);
        assertThat(handler.completed).isTrue();
    }

    private static HttpResponse<Stream<String>> response(Stream<String> body) {
        return new HttpResponse<>() {
            @Override public int statusCode() { return 200; }
            @Override public HttpRequest request() { return null; }
            @Override public Optional<HttpResponse<Stream<String>>> previousResponse() { return Optional.empty(); }
            @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (name, values) -> true); }
            @Override public Stream<String> body() { return body; }
            @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
            @Override public URI uri() { return URI.create("http://localhost:11434/v1/chat/completions"); }
            @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }

    /** Tracks multiple concurrent tool calls by block index (handles interleaved Start events). */
    private static final class CapturingHandler implements StreamEventHandler {
        private final List<ContentBlock.ToolUse> toolUses = new ArrayList<>();
        private final List<StopReason> stopReasons = new ArrayList<>();
        private final Map<Integer, ToolAccumulator> activeTools = new LinkedHashMap<>();
        private int toolUseDeltaCount;
        private boolean completed;

        @Override
        public void onContentBlockStart(StreamEvent.ContentBlockStart event) {
            if (event.block() instanceof ContentBlock.ToolUse toolUse) {
                activeTools.put(event.index(), new ToolAccumulator(toolUse.id(), toolUse.name()));
            }
        }

        @Override
        public void onToolUseDelta(StreamEvent.ToolUseDelta event) {
            if (event.partialJson() != null) {
                var acc = activeTools.get(event.index());
                if (acc != null) {
                    acc.arguments.append(event.partialJson());
                }
                toolUseDeltaCount++;
            }
        }

        @Override
        public void onContentBlockStop(StreamEvent.ContentBlockStop event) {
            var acc = activeTools.remove(event.index());
            if (acc != null) {
                toolUses.add(new ContentBlock.ToolUse(acc.id, acc.name, acc.arguments.toString()));
            }
        }

        @Override
        public void onMessageDelta(StreamEvent.MessageDelta event) {
            stopReasons.add(event.stopReason());
        }

        @Override
        public void onComplete(StreamEvent.StreamComplete event) {
            completed = true;
        }

        private static final class ToolAccumulator {
            final String id;
            final String name;
            final StringBuilder arguments = new StringBuilder();
            ToolAccumulator(String id, String name) {
                this.id = id;
                this.name = name;
            }
        }
    }
}

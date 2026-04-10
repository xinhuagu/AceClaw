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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAIStreamSessionTest {

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

    private static final class CapturingHandler implements StreamEventHandler {
        private final List<ContentBlock.ToolUse> toolUses = new ArrayList<>();
        private final List<StopReason> stopReasons = new ArrayList<>();
        private String currentToolUseId;
        private String currentToolUseName;
        private final StringBuilder currentArguments = new StringBuilder();
        private boolean inToolUse;
        private boolean completed;

        @Override
        public void onContentBlockStart(StreamEvent.ContentBlockStart event) {
            if (event.block() instanceof ContentBlock.ToolUse toolUse) {
                currentToolUseId = toolUse.id();
                currentToolUseName = toolUse.name();
                currentArguments.setLength(0);
                inToolUse = true;
            }
        }

        @Override
        public void onToolUseDelta(StreamEvent.ToolUseDelta event) {
            if (event.partialJson() != null) {
                currentArguments.append(event.partialJson());
            }
        }

        @Override
        public void onContentBlockStop(StreamEvent.ContentBlockStop event) {
            if (inToolUse) {
                toolUses.add(new ContentBlock.ToolUse(
                        currentToolUseId, currentToolUseName, currentArguments.toString()));
                inToolUse = false;
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
    }
}

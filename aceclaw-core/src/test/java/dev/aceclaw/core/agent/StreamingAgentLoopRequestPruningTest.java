package dev.aceclaw.core.agent;

import dev.aceclaw.core.llm.ContentBlock;
import dev.aceclaw.core.llm.LlmClient;
import dev.aceclaw.core.llm.LlmException;
import dev.aceclaw.core.llm.LlmRequest;
import dev.aceclaw.core.llm.LlmResponse;
import dev.aceclaw.core.llm.Message;
import dev.aceclaw.core.llm.RequestSource;
import dev.aceclaw.core.llm.StopReason;
import dev.aceclaw.core.llm.StreamEvent;
import dev.aceclaw.core.llm.StreamEventHandler;
import dev.aceclaw.core.llm.StreamSession;
import dev.aceclaw.core.llm.Usage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class StreamingAgentLoopRequestPruningTest {

    @Test
    void requestTimePruningRelievesPressureBeforeFormalCompaction() throws Exception {
        var llm = new CapturingStreamingLlmClient();
        var config = new CompactionConfig(1_000, 100, 0.85, 0.60, 1);
        var compactor = new MessageCompactor(llm, "model", config);
        var loop = new StreamingAgentLoop(
                llm,
                new ToolRegistry(),
                "model",
                null,
                100,
                0,
                1_000,
                compactor,
                AgentLoopConfig.EMPTY);

        String largeContent = "X".repeat(5_000);
        var history = List.<Message>of(
                Message.user("Old question"),
                Message.toolResult("tu1", largeContent, false)
        );
        var compactionEvents = new AtomicInteger();

        var turn = loop.runTurn("Latest protected turn", history, new StreamEventHandler() {
            @Override
            public void onCompaction(int originalTokens, int compactedTokens, String phase) {
                compactionEvents.incrementAndGet();
            }
        });

        assertThat(turn.compactionResult()).isNull();
        assertThat(compactionEvents.get()).isZero();
        assertThat(llm.lastRequest).isNotNull();
        var requestToolResult = (Message.UserMessage) llm.lastRequest.messages().get(1);
        var contentBlock = (ContentBlock.ToolResult) requestToolResult.content().getFirst();
        assertThat(contentBlock.content()).contains("[content pruned during context compaction");

        var originalToolResult = (Message.UserMessage) history.get(1);
        var originalBlock = (ContentBlock.ToolResult) originalToolResult.content().getFirst();
        assertThat(originalBlock.content()).isEqualTo(largeContent);
    }

    @Test
    void streamingTurnAttributesAllRequestsToMainTurn() throws Exception {
        // Parallel to the AgentLoop-side invariant test. StreamingAgentLoop has its own
        // llmRequestCount++ site and several Turn-construction paths (END_TURN, max-iterations,
        // multiple buildCancelledTurn overloads). All of them must populate requestAttribution
        // with the same count, attributed to MAIN_TURN.
        var llm = new CapturingStreamingLlmClient();
        var config = new CompactionConfig(1_000, 100, 0.85, 0.60, 1);
        var compactor = new MessageCompactor(llm, "model", config);
        var loop = new StreamingAgentLoop(
                llm, new ToolRegistry(), "model", null, 100, 0, 1_000, compactor,
                AgentLoopConfig.EMPTY);

        var turn = loop.runTurn("hi", List.of(), new StreamEventHandler() {});

        assertThat(turn.llmRequestCount()).isEqualTo(1);
        assertThat(turn.requestAttribution().total()).isEqualTo(turn.llmRequestCount());
        assertThat(turn.requestAttribution().count(RequestSource.MAIN_TURN))
                .isEqualTo(turn.llmRequestCount());
    }

    private static final class CapturingStreamingLlmClient implements LlmClient {
        private LlmRequest lastRequest;

        @Override
        public LlmResponse sendMessage(LlmRequest request) throws LlmException {
            throw new UnsupportedOperationException();
        }

        @Override
        public StreamSession streamMessage(LlmRequest request) {
            this.lastRequest = request;
            return new StreamSession() {
                @Override
                public void onEvent(StreamEventHandler handler) {
                    handler.onMessageStart(new StreamEvent.MessageStart("msg-1", "model"));
                    handler.onContentBlockStart(
                            new StreamEvent.ContentBlockStart(0, new ContentBlock.Text("Done")));
                    handler.onTextDelta(new StreamEvent.TextDelta("Done"));
                    handler.onContentBlockStop(new StreamEvent.ContentBlockStop(0));
                    handler.onMessageDelta(new StreamEvent.MessageDelta(
                            StopReason.END_TURN, new Usage(100, 20)));
                    handler.onComplete(new StreamEvent.StreamComplete());
                }

                @Override
                public void cancel() {
                }
            };
        }

        @Override
        public String provider() {
            return "test";
        }

        @Override
        public String defaultModel() {
            return "model";
        }
    }
}

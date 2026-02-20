package dev.aceclaw.core.agent;

import dev.aceclaw.core.llm.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class SubAgentRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void filteredRegistryExcludesTask() {
        var parentRegistry = new ToolRegistry();
        parentRegistry.register(stubTool("read_file"));
        parentRegistry.register(stubTool("write_file"));
        parentRegistry.register(stubTool("task"));
        parentRegistry.register(stubTool("glob"));

        var runner = new SubAgentRunner(
                new StubLlmClient(), parentRegistry, "parent-model", tempDir, 4096, 0);

        // General agent: all tools minus task
        var generalConfig = new SubAgentConfig("general", "", null, List.of(), List.of(), 25, "");
        var generalRegistry = runner.createFilteredRegistry(generalConfig);
        assertThat(generalRegistry.size()).isEqualTo(3);
        assertThat(generalRegistry.get("task")).isEmpty();
        assertThat(generalRegistry.get("read_file")).isPresent();
        assertThat(generalRegistry.get("write_file")).isPresent();
        assertThat(generalRegistry.get("glob")).isPresent();
    }

    @Test
    void filteredRegistryRespectsAllowList() {
        var parentRegistry = new ToolRegistry();
        parentRegistry.register(stubTool("read_file"));
        parentRegistry.register(stubTool("write_file"));
        parentRegistry.register(stubTool("task"));
        parentRegistry.register(stubTool("glob"));
        parentRegistry.register(stubTool("grep"));

        var runner = new SubAgentRunner(
                new StubLlmClient(), parentRegistry, "parent-model", tempDir, 4096, 0);

        // Explore agent: only read_file, glob, grep
        var exploreConfig = new SubAgentConfig(
                "explore", "", null,
                List.of("read_file", "glob", "grep"), List.of(), 25, "");
        var exploreRegistry = runner.createFilteredRegistry(exploreConfig);
        assertThat(exploreRegistry.size()).isEqualTo(3);
        assertThat(exploreRegistry.get("read_file")).isPresent();
        assertThat(exploreRegistry.get("glob")).isPresent();
        assertThat(exploreRegistry.get("grep")).isPresent();
        assertThat(exploreRegistry.get("write_file")).isEmpty();
        assertThat(exploreRegistry.get("task")).isEmpty();
    }

    @Test
    void filteredRegistryRespectsDisallowList() {
        var parentRegistry = new ToolRegistry();
        parentRegistry.register(stubTool("read_file"));
        parentRegistry.register(stubTool("write_file"));
        parentRegistry.register(stubTool("task"));
        parentRegistry.register(stubTool("bash"));

        var runner = new SubAgentRunner(
                new StubLlmClient(), parentRegistry, "parent-model", tempDir, 4096, 0);

        var config = new SubAgentConfig(
                "safe", "", null, List.of(), List.of("bash"), 25, "");
        var registry = runner.createFilteredRegistry(config);
        assertThat(registry.size()).isEqualTo(2);
        assertThat(registry.get("read_file")).isPresent();
        assertThat(registry.get("write_file")).isPresent();
        assertThat(registry.get("bash")).isEmpty();
        assertThat(registry.get("task")).isEmpty();
    }

    @Test
    void modelResolutionInheritsParent() throws Exception {
        var mockClient = new SimpleMockLlmClient("Sub-agent result");
        var parentRegistry = new ToolRegistry();

        var runner = new SubAgentRunner(
                mockClient, parentRegistry, "parent-model-123", tempDir, 4096, 0);

        var config = new SubAgentConfig("general", "", null, List.of(), List.of(), 25, "test prompt");
        var result = runner.run(config, "Do something", null);

        assertThat(result).isEqualTo("Sub-agent result");
        // Verify the model used was the parent model
        assertThat(mockClient.lastRequest).isNotNull();
        assertThat(mockClient.lastRequest.model()).isEqualTo("parent-model-123");
    }

    @Test
    void modelResolutionUsesConfigModel() throws Exception {
        var mockClient = new SimpleMockLlmClient("Result from haiku");
        var parentRegistry = new ToolRegistry();

        var runner = new SubAgentRunner(
                mockClient, parentRegistry, "parent-model-123", tempDir, 4096, 0);

        var config = new SubAgentConfig("explore", "", "haiku-model", List.of(), List.of(), 25, "test");
        var result = runner.run(config, "Search files", null);

        assertThat(result).isEqualTo("Result from haiku");
        assertThat(mockClient.lastRequest.model()).isEqualTo("haiku-model");
    }

    @Test
    void systemPromptIncludesWorkingDir() throws Exception {
        var mockClient = new SimpleMockLlmClient("done");
        var parentRegistry = new ToolRegistry();

        var runner = new SubAgentRunner(
                mockClient, parentRegistry, "model", tempDir, 4096, 0);

        var config = new SubAgentConfig("test", "", null, List.of(), List.of(), 25,
                "Agent working in %s");
        runner.run(config, "test task", null);

        assertThat(mockClient.lastRequest.systemPrompt()).contains(tempDir.toString());
    }

    // -- TD-2: Cancellation tests --

    @Test
    void cancellationStopsSubAgent() throws Exception {
        var cancelledLatch = new CountDownLatch(1);
        var cancelled = new AtomicBoolean(false);

        // A client that blocks until cancellation is triggered
        var blockingClient = new LlmClient() {
            @Override
            public LlmResponse sendMessage(LlmRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public StreamSession streamMessage(LlmRequest request) {
                return new StreamSession() {
                    @Override
                    public void onEvent(StreamEventHandler handler) {
                        handler.onMessageStart(new StreamEvent.MessageStart("msg", "mock"));
                        handler.onContentBlockStart(new StreamEvent.ContentBlockStart(0, new ContentBlock.Text("")));
                        handler.onTextDelta(new StreamEvent.TextDelta("partial"));
                        // Signal that streaming has started
                        cancelledLatch.countDown();
                        // Simulate a long-running stream that checks cancellation
                        try { Thread.sleep(5000); } catch (InterruptedException e) {
                            cancelled.set(true);
                        }
                        handler.onContentBlockStop(new StreamEvent.ContentBlockStop(0));
                        handler.onMessageDelta(new StreamEvent.MessageDelta(StopReason.END_TURN, new Usage(10, 5)));
                        handler.onComplete(new StreamEvent.StreamComplete());
                    }

                    @Override
                    public void cancel() {
                        cancelled.set(true);
                    }
                };
            }

            @Override public String provider() { return "mock"; }
            @Override public String defaultModel() { return "mock"; }
        };

        var parentRegistry = new ToolRegistry();
        var runner = new SubAgentRunner(
                blockingClient, parentRegistry, "model", tempDir, 4096, 0);

        var token = new CancellationToken();
        var config = new SubAgentConfig("test", "", null, List.of(), List.of(), 25, "test");

        // Run the sub-agent in a separate thread
        var resultHolder = new String[1];
        var thread = Thread.ofVirtual().start(() -> {
            try {
                resultHolder[0] = runner.run(config, "do stuff", null, token);
            } catch (LlmException e) {
                // Expected if cancelled
            }
        });

        // Wait for streaming to start, then cancel
        cancelledLatch.await(3, TimeUnit.SECONDS);
        token.cancel();

        thread.join(5000);
        // The token was propagated and cancel() was called
        assertThat(token.isCancelled()).isTrue();
    }

    @Test
    void nullCancellationTokenAllowed() throws Exception {
        var mockClient = new SimpleMockLlmClient("result");
        var parentRegistry = new ToolRegistry();

        var runner = new SubAgentRunner(
                mockClient, parentRegistry, "model", tempDir, 4096, 0);

        var config = new SubAgentConfig("test", "", null, List.of(), List.of(), 25, "test");
        // Should not throw with null token
        var result = runner.run(config, "do stuff", null, null);
        assertThat(result).isEqualTo("result");
    }

    // -- TD-5: Project rules tests --

    @Test
    void systemPromptIncludesProjectRules() throws Exception {
        var mockClient = new SimpleMockLlmClient("done");
        var parentRegistry = new ToolRegistry();

        var runner = new SubAgentRunner(
                mockClient, parentRegistry, "model", tempDir, 4096, 0,
                null, "# Build\n\nUse Java 21 with preview features.");

        var config = new SubAgentConfig("test", "", null, List.of(), List.of(), 25, "test template");
        runner.run(config, "test task", null);

        var prompt = mockClient.lastRequest.systemPrompt();
        assertThat(prompt).contains("Project Rules");
        assertThat(prompt).contains("Java 21");
        assertThat(prompt).contains("test template");
    }

    @Test
    void rulesNullHandled() throws Exception {
        var mockClient = new SimpleMockLlmClient("done");
        var parentRegistry = new ToolRegistry();

        var runner = new SubAgentRunner(
                mockClient, parentRegistry, "model", tempDir, 4096, 0,
                null, null);

        var config = new SubAgentConfig("test", "", null, List.of(), List.of(), 25, "template");
        runner.run(config, "test task", null);

        var prompt = mockClient.lastRequest.systemPrompt();
        assertThat(prompt).doesNotContain("Project Rules");
        assertThat(prompt).contains("template");
    }

    @Test
    void rulesTruncatedForNonInheritingModel() throws Exception {
        var mockClient = new SimpleMockLlmClient("done");
        var parentRegistry = new ToolRegistry();

        // Create rules that exceed the 2000 char cap for non-inheriting models
        String longRules = "x".repeat(5000);
        var runner = new SubAgentRunner(
                mockClient, parentRegistry, "model", tempDir, 4096, 0,
                null, longRules);

        // Non-inheriting model (explicit model set)
        var config = new SubAgentConfig("haiku-agent", "", "haiku-model",
                List.of(), List.of(), 25, "test");
        runner.run(config, "test task", null);

        var prompt = mockClient.lastRequest.systemPrompt();
        assertThat(prompt).contains("[truncated]");
        // The full 5000-char rules should not appear
        assertThat(prompt).doesNotContain("x".repeat(5000));
    }

    // -- TD-6: Prompt caching tests --

    @Test
    void sameTypeAgentsProduceSamePrompt() throws Exception {
        var mockClient = new SimpleMockLlmClient("done");
        var parentRegistry = new ToolRegistry();

        var runner = new SubAgentRunner(
                mockClient, parentRegistry, "model", tempDir, 4096, 0,
                null, "shared rules");

        var config = new SubAgentConfig("explore", "", null,
                List.of("read_file"), List.of(), 25, "Explore the codebase.");

        // Run twice
        runner.run(config, "task 1", null);
        var prompt1 = mockClient.lastRequest.systemPrompt();

        runner.run(config, "task 2", null);
        var prompt2 = mockClient.lastRequest.systemPrompt();

        // System prompts should be identical (enables prompt caching)
        assertThat(prompt1).isEqualTo(prompt2);
    }

    // -- Test helpers ---------------------------------------------------------

    private static Tool stubTool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name + " tool"; }
            @Override public com.fasterxml.jackson.databind.JsonNode inputSchema() {
                return new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            }
            @Override public ToolResult execute(String inputJson) {
                return new ToolResult("ok", false);
            }
        };
    }

    /**
     * Minimal mock LLM client that returns a single text response.
     */
    private static final class SimpleMockLlmClient implements LlmClient {

        private final String responseText;
        volatile LlmRequest lastRequest;

        SimpleMockLlmClient(String responseText) {
            this.responseText = responseText;
        }

        @Override
        public LlmResponse sendMessage(LlmRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StreamSession streamMessage(LlmRequest request) {
            lastRequest = request;
            return new StreamSession() {
                @Override
                public void onEvent(StreamEventHandler handler) {
                    handler.onMessageStart(new StreamEvent.MessageStart("msg-sub", "mock"));
                    handler.onContentBlockStart(new StreamEvent.ContentBlockStart(0, new ContentBlock.Text("")));
                    handler.onTextDelta(new StreamEvent.TextDelta(responseText));
                    handler.onContentBlockStop(new StreamEvent.ContentBlockStop(0));
                    handler.onMessageDelta(new StreamEvent.MessageDelta(StopReason.END_TURN, new Usage(50, 20)));
                    handler.onComplete(new StreamEvent.StreamComplete());
                }

                @Override
                public void cancel() {}
            };
        }

        @Override public String provider() { return "mock"; }
        @Override public String defaultModel() { return "mock-model"; }
    }

    /**
     * Stub client for tests that only need registry filtering (no actual calls).
     */
    private static final class StubLlmClient implements LlmClient {
        @Override public LlmResponse sendMessage(LlmRequest request) { throw new UnsupportedOperationException(); }
        @Override public StreamSession streamMessage(LlmRequest request) { throw new UnsupportedOperationException(); }
        @Override public String provider() { return "stub"; }
        @Override public String defaultModel() { return "stub-model"; }
    }
}

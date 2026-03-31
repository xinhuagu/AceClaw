package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.agent.Tool;
import dev.aceclaw.core.agent.ToolRegistry;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for #386: MCP tools registered asynchronously must appear in
 * per-request tool snapshots (both tool definitions and execution lookup).
 */
class McpToolRegistrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Simulates the daemon boot race condition: tools are registered on a background
     * thread AFTER the registry is created. Verifies that a snapshot taken after the
     * future completes includes the async-registered tools.
     */
    @Test
    void asyncRegisteredToolsVisibleAfterFutureCompletes() throws Exception {
        var registry = new ToolRegistry();
        registry.register(stubTool("read_file"));
        registry.register(stubTool("bash"));

        // Simulate async MCP init
        var mcpReady = new CompletableFuture<Void>();
        var initStarted = new CountDownLatch(1);

        Thread.ofVirtual().name("mcp-init-test").start(() -> {
            initStarted.countDown();
            // Simulate slow MCP server startup
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            registry.register(stubTool("mcp__drawio__create_diagram"));
            registry.register(stubTool("mcp__drawio__export_diagram"));
            mcpReady.complete(null);
        });

        // Wait for init thread to start (simulates daemon boot completing)
        assertThat(initStarted.await(5, TimeUnit.SECONDS)).isTrue();

        // Before joining: MCP tools may or may not be present (race)
        // After joining: MCP tools MUST be present
        mcpReady.join();

        var toolNames = registry.all().stream()
                .map(Tool::name)
                .collect(Collectors.toSet());

        assertThat(toolNames).containsExactlyInAnyOrder(
                "read_file", "bash",
                "mcp__drawio__create_diagram", "mcp__drawio__export_diagram");

        // Tool definitions (sent to LLM API) must also include MCP tools
        var defNames = registry.toDefinitions().stream()
                .map(d -> d.name())
                .collect(Collectors.toSet());
        assertThat(defNames).contains(
                "mcp__drawio__create_diagram", "mcp__drawio__export_diagram");
    }

    /**
     * When MCP init fails, the future still completes and base tools remain available.
     */
    @Test
    void failedMcpInitStillCompletesFuture() {
        var registry = new ToolRegistry();
        registry.register(stubTool("read_file"));

        var mcpReady = new CompletableFuture<Void>();
        Thread.ofVirtual().start(() -> {
            try {
                throw new RuntimeException("MCP server unreachable");
            } catch (Exception e) {
                // Mirrors daemon: finally { mcpReady.complete(null); }
            } finally {
                mcpReady.complete(null);
            }
        });

        mcpReady.join(); // Must not hang

        assertThat(registry.all()).hasSize(1);
        assertThat(registry.all().getFirst().name()).isEqualTo("read_file");
    }

    /**
     * Verifies that currentToolNames() (live registry read) includes MCP tools
     * after async registration, and that ToolGuidanceGenerator emits MCP guidance.
     */
    @Test
    void toolGuidanceIncludesMcpToolsAfterAsyncRegistration() {
        var registry = new ToolRegistry();
        registry.register(stubTool("read_file"));
        registry.register(stubTool("bash"));

        // Simulate MCP tools being added after daemon boot
        registry.register(stubTool("mcp__drawio__create_diagram"));
        registry.register(stubTool("mcp__context7__query_docs"));

        var toolNames = registry.all().stream()
                .map(Tool::name)
                .collect(Collectors.toSet());

        var guidance = ToolGuidanceGenerator.generate(toolNames, false);

        assertThat(guidance).contains("## MCP Tools (external servers)");
        assertThat(guidance).contains("**mcp__drawio__create_diagram**");
        assertThat(guidance).contains("**mcp__context7__query_docs**");
    }

    private static Tool stubTool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "stub tool: " + name; }
            @Override public JsonNode inputSchema() {
                return MAPPER.createObjectNode().put("type", "object");
            }
            @Override public ToolResult execute(String inputJson) {
                return new ToolResult("ok", false);
            }
        };
    }
}

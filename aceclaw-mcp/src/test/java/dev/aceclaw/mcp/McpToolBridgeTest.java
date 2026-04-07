package dev.aceclaw.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpToolBridgeTest {

    @Mock
    McpSyncClient client;

    private final Lock serverLock = new ReentrantLock();

    private McpSchema.Tool mcpTool(String name, String description) {
        var schema = new McpSchema.JsonSchema("object", Map.of(), List.of(), null, null, null);
        return new McpSchema.Tool(name, null, description, schema, null, null, null);
    }

    @Test
    void toolNameFollowsConvention() {
        var tool = McpToolBridge.create("my-server", mcpTool("do_thing", "Does a thing"), client, serverLock);

        assertThat(tool.name()).isEqualTo("mcp__my-server__do_thing");
    }

    @Test
    void descriptionPassedThrough() {
        var tool = McpToolBridge.create("s", mcpTool("t", "My description"), client, serverLock);

        assertThat(tool.description()).isEqualTo("My description");
    }

    @Test
    void executeDelegatesToCallTool() throws Exception {
        var tool = McpToolBridge.create("s", mcpTool("action", "desc"), client, serverLock);

        when(client.callTool(any(McpSchema.CallToolRequest.class)))
                .thenReturn(new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("hello")), false));

        var result = tool.execute("{\"key\": \"value\"}");

        assertThat(result.output()).isEqualTo("hello");
        assertThat(result.isError()).isFalse();
        verify(client).callTool(any(McpSchema.CallToolRequest.class));
    }

    @Test
    void errorResultSetsIsError() throws Exception {
        var tool = McpToolBridge.create("s", mcpTool("fail", "desc"), client, serverLock);

        when(client.callTool(any(McpSchema.CallToolRequest.class)))
                .thenReturn(new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("error msg")), true));

        var result = tool.execute("{}");

        assertThat(result.isError()).isTrue();
        assertThat(result.output()).isEqualTo("error msg");
    }

    @Test
    void emptyInputHandledGracefully() throws Exception {
        var tool = McpToolBridge.create("s", mcpTool("t", "desc"), client, serverLock);

        when(client.callTool(any(McpSchema.CallToolRequest.class)))
                .thenReturn(new McpSchema.CallToolResult(List.of(), false));

        var result = tool.execute("");
        assertThat(result.output()).isEmpty();
        assertThat(result.isError()).isFalse();
    }

    @Test
    void nullInputHandledGracefully() throws Exception {
        var tool = McpToolBridge.create("s", mcpTool("t", "desc"), client, serverLock);

        when(client.callTool(any(McpSchema.CallToolRequest.class)))
                .thenReturn(new McpSchema.CallToolResult(List.of(), false));

        var result = tool.execute(null);
        assertThat(result.output()).isEmpty();
        assertThat(result.isError()).isFalse();
    }

    @Test
    void inputSchemaExposed() {
        var tool = McpToolBridge.create("s", mcpTool("t", "desc"), client, serverLock);

        assertThat(tool.inputSchema()).isNotNull();
        assertThat(tool.inputSchema().get("type").asText()).isEqualTo("object");
    }

    /**
     * Simulates the real race: concurrent callTool() on a transport that rejects
     * parallel emits (like StdioClientTransport's unicast Reactor sink).
     * The mock client tracks concurrent invocations and records if >1 thread
     * is inside callTool() at the same time. With the per-server lock this
     * must never happen.
     */
    @Test
    void concurrentCallToolSerializedByServerLock() throws Exception {
        var concurrency = new AtomicInteger();
        var maxConcurrency = new AtomicInteger();
        var errors = new CopyOnWriteArrayList<String>();

        var guardClient = mock(McpSyncClient.class);
        when(guardClient.callTool(any(McpSchema.CallToolRequest.class))).thenAnswer(inv -> {
            int current = concurrency.incrementAndGet();
            maxConcurrency.updateAndGet(max -> Math.max(max, current));
            if (current > 1) {
                errors.add("Concurrent callTool detected: " + current + " threads");
            }
            try {
                Thread.sleep(50); // simulate server processing time
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                concurrency.decrementAndGet();
            }
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ok")), false);
        });

        var lock = new ReentrantLock();
        var tools = new ArrayList<McpToolBridge>();
        for (int i = 0; i < 4; i++) {
            tools.add(McpToolBridge.create("srv", mcpTool("tool_" + i, "desc"),
                    guardClient, lock));
        }

        var startLatch = new CountDownLatch(1);
        var doneLatch = new CountDownLatch(4);

        for (var tool : tools) {
            Thread.ofVirtual().start(() -> {
                try {
                    startLatch.await(5, TimeUnit.SECONDS);
                    tool.execute("{}");
                } catch (Exception e) {
                    errors.add(e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // release all threads simultaneously
        assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(errors).as("no concurrent access errors").isEmpty();
        assertThat(maxConcurrency.get()).as("server lock must serialize to max 1 concurrent call").isEqualTo(1);
    }
}

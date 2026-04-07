package dev.aceclaw.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
}

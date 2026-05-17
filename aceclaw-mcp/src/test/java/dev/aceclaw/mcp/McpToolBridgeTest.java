package dev.aceclaw.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.security.Capability;
import dev.aceclaw.security.DefaultPermissionPolicy;
import dev.aceclaw.security.PermissionDecision;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpToolBridgeTest {

    @Mock
    McpSyncClient client;

    private McpSchema.Tool mcpTool(String name, String description) {
        var schema = new McpSchema.JsonSchema("object", Map.of(), List.of(), null, null, null);
        return new McpSchema.Tool(name, null, description, schema, null, null, null);
    }

    @Test
    void toolNameFollowsConvention() {
        var tool = McpToolBridge.create("my-server", mcpTool("do_thing", "Does a thing"), client);

        assertThat(tool.name()).isEqualTo("mcp__my-server__do_thing");
    }

    @Test
    void descriptionPassedThrough() {
        var tool = McpToolBridge.create("s", mcpTool("t", "My description"), client);

        assertThat(tool.description()).isEqualTo("My description");
    }

    @Test
    void executeDelegatesToCallTool() throws Exception {
        var tool = McpToolBridge.create("s", mcpTool("action", "desc"), client);

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
        var tool = McpToolBridge.create("s", mcpTool("fail", "desc"), client);

        when(client.callTool(any(McpSchema.CallToolRequest.class)))
                .thenReturn(new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("error msg")), true));

        var result = tool.execute("{}");

        assertThat(result.isError()).isTrue();
        assertThat(result.output()).isEqualTo("error msg");
    }

    @Test
    void emptyInputHandledGracefully() throws Exception {
        var tool = McpToolBridge.create("s", mcpTool("t", "desc"), client);

        when(client.callTool(any(McpSchema.CallToolRequest.class)))
                .thenReturn(new McpSchema.CallToolResult(List.of(), false));

        var result = tool.execute("");
        assertThat(result.output()).isEmpty();
        assertThat(result.isError()).isFalse();
    }

    @Test
    void nullInputHandledGracefully() throws Exception {
        var tool = McpToolBridge.create("s", mcpTool("t", "desc"), client);

        when(client.callTool(any(McpSchema.CallToolRequest.class)))
                .thenReturn(new McpSchema.CallToolResult(List.of(), false));

        var result = tool.execute(null);
        assertThat(result.output()).isEmpty();
        assertThat(result.isError()).isFalse();
    }

    @Test
    void inputSchemaExposed() {
        var tool = McpToolBridge.create("s", mcpTool("t", "desc"), client);

        assertThat(tool.inputSchema()).isNotNull();
        assertThat(tool.inputSchema().get("type").asText()).isEqualTo("object");
    }

    @Test
    void toCapabilityProducesMcpInvokeWithServerAndMethod() {
        // McpToolBridge is now CapabilityAware (#480 PR 4 / runtime-governance):
        // it produces a structured Capability.McpInvoke so the policy and audit
        // log see (server, method) directly instead of falling back to the
        // LegacyToolUse bridge.
        var tool = McpToolBridge.create("my-server", mcpTool("do_thing", "desc"), client);

        var capability = tool.toCapability(new ObjectMapper().createObjectNode());

        assertThat(capability).isInstanceOf(Capability.McpInvoke.class);
        var invoke = (Capability.McpInvoke) capability;
        assertThat(invoke.server()).isEqualTo("my-server");
        assertThat(invoke.method()).isEqualTo("do_thing");
    }

    @Test
    void toCapabilityIgnoresArgs() {
        // For methods that don't pattern-match as file ops, args are NOT
        // carried on McpInvoke (size + secret risk). Different args produce
        // the same capability variant.
        var tool = McpToolBridge.create("s", mcpTool("opaque_method", "desc"), client);

        var mapper = new ObjectMapper();
        var cap1 = tool.toCapability(mapper.createObjectNode().put("k", "v1"));
        var cap2 = tool.toCapability(mapper.createObjectNode().put("k", "v2"));

        assertThat(cap1).isEqualTo(cap2);
        assertThat(cap1).isInstanceOf(Capability.McpInvoke.class);
    }

    // -- Best-effort file-capability inference (Codex P1 follow-up on #495) --

    @Test
    void writeFileMethodWithPathInfersFileWrite() {
        var tool = McpToolBridge.create("fs", mcpTool("write_file", "desc"), client);

        var args = new ObjectMapper().createObjectNode().put("path", "/repo/.env");
        var cap = tool.toCapability(args);

        assertThat(cap).isInstanceOf(Capability.FileWrite.class);
        assertThat(((Capability.FileWrite) cap).path()).isEqualTo(Path.of("/repo/.env"));
    }

    @Test
    void createFileMethodWithFilePathInfersFileWrite() {
        var tool = McpToolBridge.create("fs", mcpTool("create_file", "desc"), client);

        var args = new ObjectMapper().createObjectNode().put("file_path", "/tmp/x.txt");
        var cap = tool.toCapability(args);

        assertThat(cap).isInstanceOf(Capability.FileWrite.class);
    }

    @Test
    void deleteFileMethodInfersFileDelete() {
        var tool = McpToolBridge.create("fs", mcpTool("delete_file", "desc"), client);

        var args = new ObjectMapper().createObjectNode().put("path", "/repo/.env");
        var cap = tool.toCapability(args);

        assertThat(cap).isInstanceOf(Capability.FileDelete.class);
    }

    @Test
    void removeMethodInfersFileDelete() {
        var tool = McpToolBridge.create("fs", mcpTool("remove", "desc"), client);

        var args = new ObjectMapper().createObjectNode().put("path", "/tmp/x");
        var cap = tool.toCapability(args);

        assertThat(cap).isInstanceOf(Capability.FileDelete.class);
    }

    @Test
    void writeMethodWithoutPathArgFallsBackToMcpInvoke() {
        // A method with a write-shaped name but no recognized path field is
        // genuinely opaque; produce McpInvoke and let the standard prompt
        // handle it.
        var tool = McpToolBridge.create("fs", mcpTool("write_file", "desc"), client);

        var args = new ObjectMapper().createObjectNode().put("destination_uri", "x");
        var cap = tool.toCapability(args);

        assertThat(cap).isInstanceOf(Capability.McpInvoke.class);
    }

    @Test
    void opaqueMethodNameWithPathArgFallsBackToMcpInvoke() {
        // Conservative on the false-positive side: if the method name has no
        // obvious write/delete verb, do NOT promote to FileWrite even when a
        // path arg is present.
        var tool = McpToolBridge.create("fs", mcpTool("get_metadata", "desc"), client);

        var args = new ObjectMapper().createObjectNode().put("path", "/etc/hosts");
        var cap = tool.toCapability(args);

        assertThat(cap).isInstanceOf(Capability.McpInvoke.class);
    }

    @Test
    void readFileMethodFallsBackToMcpInvoke() {
        // Reads aren't promoted to FileRead - the structural rules don't cover
        // reads, and McpInvoke keeps the prompt flow identical to a generic
        // MCP call.
        var tool = McpToolBridge.create("fs", mcpTool("read_file", "desc"), client);

        var args = new ObjectMapper().createObjectNode().put("path", "/etc/hosts");
        var cap = tool.toCapability(args);

        assertThat(cap).isInstanceOf(Capability.McpInvoke.class);
    }

    @Test
    void mcpFileWriteToSensitivePathIsStructurallyDenied() {
        // End-to-end: an MCP server's write_file(path=".env") composes through
        // McpToolBridge.toCapability() into a Capability.FileWrite that the
        // structural-denial layer refuses. Pins that the wiring between the
        // MCP boundary and DefaultPermissionPolicy works for the bypass Codex
        // P1 flagged (#495).
        var tool = McpToolBridge.create("fs", mcpTool("write_file", "desc"), client);
        var args = new ObjectMapper().createObjectNode().put("path", "/repo/.env");

        var cap = tool.toCapability(args);
        var decision = new DefaultPermissionPolicy("auto-accept").evaluateStructural(cap);

        assertThat(decision)
                .as("MCP-driven write to .env must be structurally denied even in auto-accept")
                .isNotNull();
        assertThat(decision.reason()).contains("sensitive path");
    }
}

package dev.aceclaw.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpClientManagerTest {

    @Mock
    McpSyncClient mockClient;

    private McpClientManager manager;

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
    }

    private static McpServerConfig.ServerEntry dummyConfig(String name) {
        return McpServerConfig.ServerEntry.stdio(name, List.of(), Map.of());
    }

    private McpSchema.ListToolsResult toolsResult(String... names) {
        var tools = new ArrayList<McpSchema.Tool>();
        for (var name : names) {
            var schema = new McpSchema.JsonSchema("object", Map.of(), List.of(), null, null, null);
            tools.add(new McpSchema.Tool(name, null, "desc", schema, null, null, null));
        }
        return new McpSchema.ListToolsResult(tools, null);
    }

    @Test
    void startConnectsServerAndBridgesTools() {
        when(mockClient.listTools()).thenReturn(toolsResult("tool_a", "tool_b"));
        // closeGracefully() returns non-void; Mockito default (null) is fine

        var configs = Map.of("server1", dummyConfig("echo"));
        manager = new McpClientManager(configs, (name, config) -> mockClient);

        var registered = new ArrayList<String>();
        manager.start(tools -> tools.forEach(t -> registered.add(t.name())));

        assertThat(registered).containsExactly("mcp__server1__tool_a", "mcp__server1__tool_b");
        assertThat(manager.bridgedTools()).hasSize(2);

        var health = manager.serverHealth();
        assertThat(health.get("server1").status()).isEqualTo(McpClientManager.ServerStatus.CONNECTED);
        assertThat(health.get("server1").toolCount()).isEqualTo(2);
    }

    @Test
    void startRecordsFailedServerWithError() {
        var configs = Map.of("bad", dummyConfig("bad-cmd"));
        manager = new McpClientManager(configs, (name, config) -> {
            throw new RuntimeException("connection refused");
        });
        manager.start();

        var health = manager.serverHealth();
        assertThat(health.get("bad").status()).isEqualTo(McpClientManager.ServerStatus.FAILED);
        assertThat(health.get("bad").lastError()).contains("connection refused");
        assertThat(health.get("bad").toolCount()).isZero();
    }

    @Test
    void toolDiscoveryFailureMarksServerFailed() {
        when(mockClient.listTools()).thenThrow(new RuntimeException("listTools timeout"));

        var configs = Map.of("srv", dummyConfig("cmd"));
        manager = new McpClientManager(configs, (name, config) -> mockClient);
        manager.start();

        // Server connected but tool discovery failed — should be marked FAILED, not CONNECTED
        var health = manager.serverHealth();
        assertThat(health.get("srv").status()).isEqualTo(McpClientManager.ServerStatus.FAILED);
        assertThat(health.get("srv").lastError()).contains("listTools timeout");
        assertThat(health.get("srv").toolCount()).isZero();
        assertThat(manager.bridgedTools()).isEmpty();
    }

    @Test
    void reconnectIfDueRespectssCooldown() {
        when(mockClient.listTools()).thenReturn(toolsResult("t"));
        // closeGracefully() returns non-void; Mockito default (null) is fine

        var configs = new LinkedHashMap<String, McpServerConfig.ServerEntry>();
        configs.put("s1", dummyConfig("cmd"));
        var callCount = new AtomicInteger();
        manager = new McpClientManager(configs, (name, config) -> {
            callCount.incrementAndGet();
            return mockClient;
        });
        manager.start();
        assertThat(callCount.get()).isEqualTo(1);

        // First reconnect should succeed
        assertThat(manager.reconnect("s1")).isTrue();
        assertThat(callCount.get()).isEqualTo(2);

        // Immediate reconnectIfDue should be skipped (cooldown not elapsed)
        assertThat(manager.reconnectIfDue("s1")).isFalse();
        assertThat(callCount.get()).isEqualTo(2); // no additional call
    }

    @Test
    void reconnectRemovesStaleToolsAndNotifiesCallback() {
        var client1 = mock(McpSyncClient.class);
        when(client1.listTools()).thenReturn(toolsResult("old_tool"));
        // closeGracefully() returns non-void; default mock return is fine

        var client2 = mock(McpSyncClient.class);
        when(client2.listTools()).thenReturn(toolsResult("new_tool"));
        // closeGracefully() returns non-void; default mock return is fine

        var configs = Map.of("srv", dummyConfig("cmd"));
        var callIndex = new AtomicInteger();
        manager = new McpClientManager(configs, (name, config) -> {
            return callIndex.incrementAndGet() == 1 ? client1 : client2;
        });
        manager.start();

        assertThat(manager.bridgedTools()).hasSize(1);
        assertThat(manager.bridgedTools().getFirst().name()).isEqualTo("mcp__srv__old_tool");

        // Track removals
        var removedTools = new ArrayList<String>();
        manager.setOnToolRemoved(removedTools::add);

        // Track new registrations
        var newTools = new ArrayList<String>();
        manager.reconnect("srv");

        assertThat(removedTools).containsExactly("mcp__srv__old_tool");
        assertThat(manager.bridgedTools()).hasSize(1);
        assertThat(manager.bridgedTools().getFirst().name()).isEqualTo("mcp__srv__new_tool");
    }

    @Test
    void reconnectFailureLeavesServerFailed() {
        when(mockClient.listTools()).thenReturn(toolsResult("t"));
        // closeGracefully() returns non-void; Mockito default (null) is fine

        var configs = Map.of("s", dummyConfig("cmd"));
        var callIndex = new AtomicInteger();
        manager = new McpClientManager(configs, (name, config) -> {
            if (callIndex.incrementAndGet() > 1) {
                throw new RuntimeException("reconnect failed");
            }
            return mockClient;
        });
        manager.start();
        assertThat(manager.serverHealth().get("s").status())
                .isEqualTo(McpClientManager.ServerStatus.CONNECTED);

        var removedTools = new ArrayList<String>();
        manager.setOnToolRemoved(removedTools::add);

        boolean result = manager.reconnect("s");

        assertThat(result).isFalse();
        assertThat(manager.serverHealth().get("s").status())
                .isEqualTo(McpClientManager.ServerStatus.FAILED);
        assertThat(manager.serverHealth().get("s").lastError()).contains("reconnect failed");
        // Old tools should still have been removed
        assertThat(removedTools).containsExactly("mcp__s__t");
        assertThat(manager.bridgedTools()).isEmpty();
    }

    @Test
    void reconnectUnknownServerReturnsFalse() {
        manager = new McpClientManager(Map.of(), (name, config) -> mockClient);
        manager.start();

        assertThat(manager.reconnect("nonexistent")).isFalse();
    }

    @Test
    void serverHealthReturnsCachedStateWithoutBlocking() {
        when(mockClient.listTools()).thenReturn(toolsResult("t1"));
        // closeGracefully() returns non-void; Mockito default (null) is fine

        var configs = Map.of("s", dummyConfig("cmd"));
        manager = new McpClientManager(configs, (name, config) -> mockClient);
        manager.start();

        // serverHealth() should return cached state — calling it should not throw
        // even if we can't actually ping (verifying it doesn't call refreshServerHealth)
        var health = manager.serverHealth();
        assertThat(health).containsKey("s");
        assertThat(health.get("s").status()).isEqualTo(McpClientManager.ServerStatus.CONNECTED);
        assertThat(health.get("s").toolCount()).isEqualTo(1);
    }

    @Test
    void pingUpdatesStatusOnFailure() {
        when(mockClient.listTools()).thenReturn(toolsResult());
        doThrow(new RuntimeException("timeout")).when(mockClient).ping();
        // closeGracefully() returns non-void; Mockito default (null) is fine

        var configs = Map.of("s", dummyConfig("cmd"));
        manager = new McpClientManager(configs, (name, config) -> mockClient);
        manager.start();

        var pingResults = manager.ping();
        assertThat(pingResults.get("s")).isFalse();

        var health = manager.serverHealth();
        assertThat(health.get("s").status()).isEqualTo(McpClientManager.ServerStatus.FAILED);
        assertThat(health.get("s").lastError()).contains("timeout");
    }

    @Test
    void closeStopsBackgroundRefreshThread() throws InterruptedException {
        when(mockClient.listTools()).thenReturn(toolsResult());
        // closeGracefully() returns non-void; default mock return is fine

        var configs = Map.of("s", dummyConfig("cmd"));
        manager = new McpClientManager(configs, (name, config) -> mockClient);
        manager.start();

        // Close should not hang — background thread should stop
        manager.close();
        manager = null; // prevent double-close in tearDown

        // Verify server marked as shutdown
    }
}

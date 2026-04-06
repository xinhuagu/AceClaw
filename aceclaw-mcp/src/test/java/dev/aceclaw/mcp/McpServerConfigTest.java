package dev.aceclaw.mcp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class McpServerConfigTest {

    @TempDir
    Path tempDir;

    private String originalUserHome;

    @BeforeEach
    void isolateUserHome() {
        originalUserHome = System.getProperty("user.home");
        // Point user.home to tempDir so tests don't pick up real ~/.aceclaw/mcp-servers.json
        System.setProperty("user.home", tempDir.toString());
    }

    @AfterEach
    void restoreUserHome() {
        System.setProperty("user.home", originalUserHome);
    }

    @Test
    void parseValidStdioConfig() throws IOException {
        var config = """
                {
                  "mcpServers": {
                    "my-server": {
                      "command": "npx",
                      "args": ["-y", "@pkg/server"],
                      "env": { "API_KEY": "secret" }
                    }
                  }
                }
                """;
        Files.writeString(tempDir.resolve(".mcp.json"), config);

        var result = McpServerConfig.load(tempDir);

        assertThat(result).containsKey("my-server");
        var entry = result.get("my-server");
        assertThat(entry.transport()).isEqualTo(McpServerConfig.TransportType.STDIO);
        assertThat(entry.command()).isEqualTo("npx");
        assertThat(entry.args()).containsExactly("-y", "@pkg/server");
        assertThat(entry.env()).containsEntry("API_KEY", "secret");
        assertThat(entry.url()).isNull();
    }

    @Test
    void loadGlobalAceClawConfigJson() throws IOException {
        var aceclawDir = tempDir.resolve(".aceclaw");
        Files.createDirectories(aceclawDir);
        Files.writeString(aceclawDir.resolve("config.json"), """
                {
                  "mcpServers": {
                    "drawio": {
                      "command": "npx",
                      "args": ["@next-ai-drawio/mcp-server@latest"]
                    }
                  }
                }
                """);

        var result = McpServerConfig.load(tempDir);

        assertThat(result).containsKey("drawio");
        assertThat(result.get("drawio").command()).isEqualTo("npx");
        assertThat(result.get("drawio").args()).containsExactly("@next-ai-drawio/mcp-server@latest");
    }

    @Test
    void parseValidSseConfig() throws IOException {
        var config = """
                {
                  "mcpServers": {
                    "remote": {
                      "url": "https://example.com/mcp",
                      "headers": { "Authorization": "Bearer tok" }
                    }
                  }
                }
                """;
        Files.writeString(tempDir.resolve(".mcp.json"), config);

        var result = McpServerConfig.load(tempDir);

        assertThat(result).containsKey("remote");
        var entry = result.get("remote");
        assertThat(entry.transport()).isEqualTo(McpServerConfig.TransportType.SSE);
        assertThat(entry.url()).isEqualTo("https://example.com/mcp");
        assertThat(entry.headers()).containsEntry("Authorization", "Bearer tok");
        assertThat(entry.command()).isNull();
    }

    @Test
    void parseStreamableHttpConfig() throws IOException {
        var config = """
                {
                  "mcpServers": {
                    "stream": {
                      "url": "https://example.com/mcp",
                      "transport": "streamable-http"
                    }
                  }
                }
                """;
        Files.writeString(tempDir.resolve(".mcp.json"), config);

        var result = McpServerConfig.load(tempDir);

        var entry = result.get("stream");
        assertThat(entry.transport()).isEqualTo(McpServerConfig.TransportType.STREAMABLE_HTTP);
    }

    @Test
    void skipEntriesWithoutCommandOrUrl() throws IOException {
        var config = """
                {
                  "mcpServers": {
                    "empty": {},
                    "valid": { "command": "node", "args": ["server.js"] }
                  }
                }
                """;
        Files.writeString(tempDir.resolve(".mcp.json"), config);

        var result = McpServerConfig.load(tempDir);

        assertThat(result).containsKey("valid").doesNotContainKey("empty");
    }

    @Test
    void mergeMultipleConfigFiles() throws IOException {
        // First config in .mcp.json
        var first = """
                {
                  "mcpServers": {
                    "server-a": { "command": "old" },
                    "server-b": { "command": "keep" }
                  }
                }
                """;
        Files.writeString(tempDir.resolve(".mcp.json"), first);

        // Second config in .aceclaw/mcp-servers.json overrides server-a
        var aceclawDir = tempDir.resolve(".aceclaw");
        Files.createDirectories(aceclawDir);
        var second = """
                {
                  "mcpServers": {
                    "server-a": { "command": "new" }
                  }
                }
                """;
        Files.writeString(aceclawDir.resolve("mcp-servers.json"), second);

        var result = McpServerConfig.load(tempDir);

        assertThat(result.get("server-a").command()).isEqualTo("new");
        assertThat(result.get("server-b").command()).isEqualTo("keep");
    }

    @Test
    void projectAceClawConfigOverridesClaudeCompatibleConfig() throws IOException {
        Files.writeString(tempDir.resolve(".mcp.json"), """
                {
                  "mcpServers": {
                    "drawio": { "command": "old" }
                  }
                }
                """);

        var aceclawDir = tempDir.resolve(".aceclaw");
        Files.createDirectories(aceclawDir);
        Files.writeString(aceclawDir.resolve("config.json"), """
                {
                  "mcpServers": {
                    "drawio": { "command": "new" },
                    "context7": { "command": "ctx" }
                  }
                }
                """);

        var result = McpServerConfig.load(tempDir);

        assertThat(result.get("drawio").command()).isEqualTo("new");
        assertThat(result.get("context7").command()).isEqualTo("ctx");
    }

    @Test
    void handleMissingConfigFileGracefully() {
        var result = McpServerConfig.load(tempDir);
        assertThat(result).isEmpty();
    }

    @Test
    void handleMalformedJsonGracefully() throws IOException {
        Files.writeString(tempDir.resolve(".mcp.json"), "not json at all {{{");

        var result = McpServerConfig.load(tempDir);
        assertThat(result).isEmpty();
    }

    @Test
    void handleMissingMcpServersKey() throws IOException {
        Files.writeString(tempDir.resolve(".mcp.json"), """
                { "otherKey": true }
                """);

        var result = McpServerConfig.load(tempDir);
        assertThat(result).isEmpty();
    }

    @Test
    void parseTimeoutFromConfig() throws IOException {
        Files.writeString(tempDir.resolve(".mcp.json"), """
                {
                  "mcpServers": {
                    "slow-server": {
                      "command": "npx",
                      "args": ["server"],
                      "timeout": 120
                    }
                  }
                }
                """);

        var result = McpServerConfig.load(tempDir);

        var entry = result.get("slow-server");
        assertThat(entry.timeout()).isEqualTo(120);
    }

    @Test
    void missingTimeoutDefaultsToNull() throws IOException {
        Files.writeString(tempDir.resolve(".mcp.json"), """
                {
                  "mcpServers": {
                    "default-server": {
                      "command": "node",
                      "args": ["server.js"]
                    }
                  }
                }
                """);

        var result = McpServerConfig.load(tempDir);

        assertThat(result.get("default-server").timeout()).isNull();
    }

    @Test
    void zeroTimeoutFallsBackToNull() throws IOException {
        Files.writeString(tempDir.resolve(".mcp.json"), """
                {
                  "mcpServers": {
                    "bad-server": {
                      "command": "node",
                      "timeout": 0
                    }
                  }
                }
                """);

        var result = McpServerConfig.load(tempDir);

        assertThat(result.get("bad-server").timeout()).isNull();
    }

    @Test
    void negativeTimeoutFallsBackToNull() throws IOException {
        Files.writeString(tempDir.resolve(".mcp.json"), """
                {
                  "mcpServers": {
                    "neg-server": {
                      "command": "node",
                      "timeout": -1
                    }
                  }
                }
                """);

        var result = McpServerConfig.load(tempDir);

        assertThat(result.get("neg-server").timeout()).isNull();
    }

    @Test
    void timeoutParsedForRemoteTransport() throws IOException {
        Files.writeString(tempDir.resolve(".mcp.json"), """
                {
                  "mcpServers": {
                    "remote-slow": {
                      "url": "https://example.com/mcp",
                      "timeout": 300
                    }
                  }
                }
                """);

        var result = McpServerConfig.load(tempDir);

        var entry = result.get("remote-slow");
        assertThat(entry.transport()).isEqualTo(McpServerConfig.TransportType.SSE);
        assertThat(entry.timeout()).isEqualTo(300);
    }

    @Test
    void mergeFromDirectlyWithValidConfig() throws IOException {
        var configFile = tempDir.resolve("test.json");
        Files.writeString(configFile, """
                {
                  "mcpServers": {
                    "s1": { "url": "https://a.com/mcp" },
                    "s2": { "command": "node" }
                  }
                }
                """);

        var target = new LinkedHashMap<String, McpServerConfig.ServerEntry>();
        McpServerConfig.mergeFrom(target, configFile);

        assertThat(target).hasSize(2);
        assertThat(target.get("s1").transport()).isEqualTo(McpServerConfig.TransportType.SSE);
        assertThat(target.get("s2").transport()).isEqualTo(McpServerConfig.TransportType.STDIO);
    }
}

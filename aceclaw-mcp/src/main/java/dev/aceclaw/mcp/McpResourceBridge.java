package dev.aceclaw.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bridges MCP resource access across all connected servers.
 *
 * <p>Unlike tools (which are adapted to the {@link dev.aceclaw.core.agent.Tool} interface),
 * resources are exposed as a simple helper API for the agent to fetch contextual data
 * (files, database rows, API responses, etc.) from MCP servers.
 */
public final class McpResourceBridge {

    private static final Logger log = LoggerFactory.getLogger(McpResourceBridge.class);

    private final McpClientManager clientManager;

    /**
     * A resource descriptor combining server name with the MCP resource metadata.
     *
     * @param serverName the MCP server that provides this resource
     * @param uri        the resource URI
     * @param name       human-readable resource name
     * @param description optional description
     * @param mimeType   optional MIME type
     */
    public record ResourceInfo(String serverName, String uri, String name,
                               String description, String mimeType) {}

    /**
     * Content returned from reading a resource.
     *
     * @param uri      the resource URI
     * @param mimeType the MIME type (may be null)
     * @param text     the text content (may be null for binary resources)
     * @param blob     base64-encoded binary content (may be null for text resources)
     */
    public record ResourceContent(String uri, String mimeType, String text, String blob) {}

    public McpResourceBridge(McpClientManager clientManager) {
        this.clientManager = clientManager;
    }

    /**
     * Lists all available resources across all connected MCP servers.
     *
     * @return map of server name to list of resources from that server
     */
    public Map<String, List<ResourceInfo>> listResources() {
        var result = new LinkedHashMap<String, List<ResourceInfo>>();

        for (var entry : clientManager.serverStatus().entrySet()) {
            var serverName = entry.getKey();
            if (entry.getValue() != McpClientManager.ServerStatus.CONNECTED) {
                continue;
            }

            var client = clientManager.client(serverName);
            if (client == null) continue;

            try {
                var resources = new ArrayList<ResourceInfo>();
                String cursor = null;
                do {
                    var listResult = cursor == null ? client.listResources() : client.listResources(cursor);
                    for (var r : listResult.resources()) {
                        resources.add(new ResourceInfo(
                                serverName, r.uri(), r.name(), r.description(), r.mimeType()));
                    }
                    cursor = listResult.nextCursor();
                } while (cursor != null && !cursor.isBlank());

                if (!resources.isEmpty()) {
                    result.put(serverName, Collections.unmodifiableList(resources));
                }
            } catch (Exception e) {
                log.warn("Failed to list resources from MCP server '{}': {}", serverName, e.getMessage());
            }
        }

        return Collections.unmodifiableMap(result);
    }

    /**
     * Lists resources from all servers as a flat list.
     *
     * @return all available resources across servers
     */
    public List<ResourceInfo> listAllResources() {
        var all = new ArrayList<ResourceInfo>();
        listResources().values().forEach(all::addAll);
        return Collections.unmodifiableList(all);
    }

    /**
     * Reads a resource by URI from the specified server.
     *
     * @param serverName the MCP server to read from
     * @param uri        the resource URI
     * @return list of content blocks from the resource
     * @throws IllegalArgumentException if the server is unknown or not connected
     */
    public List<ResourceContent> readResource(String serverName, String uri) {
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("Resource URI must not be null or blank");
        }
        var client = clientManager.client(serverName);
        if (client == null) {
            throw new IllegalArgumentException("MCP server '%s' is not connected".formatted(serverName));
        }

        var request = new McpSchema.ReadResourceRequest(uri);
        var result = client.readResource(request);

        var contents = new ArrayList<ResourceContent>();
        for (var c : result.contents()) {
            if (c instanceof McpSchema.TextResourceContents text) {
                contents.add(new ResourceContent(text.uri(), text.mimeType(), text.text(), null));
            } else if (c instanceof McpSchema.BlobResourceContents blob) {
                contents.add(new ResourceContent(blob.uri(), blob.mimeType(), null, blob.blob()));
            } else {
                log.debug("Unknown MCP resource content type: {}", c.getClass().getSimpleName());
            }
        }

        return Collections.unmodifiableList(contents);
    }
}

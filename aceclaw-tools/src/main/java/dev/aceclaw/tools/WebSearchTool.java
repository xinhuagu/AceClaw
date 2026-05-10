package dev.aceclaw.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.agent.Tool;
import dev.aceclaw.security.Capability;
import dev.aceclaw.security.CapabilityAware;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Performs web searches using Brave Search API or DuckDuckGo Lite as fallback.
 *
 * <p>When a Brave Search API key is configured, uses the Brave API for
 * higher-quality results. Otherwise, falls back to DuckDuckGo Lite (free,
 * no API key required).
 */
public final class WebSearchTool implements Tool, CapabilityAware {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String BRAVE_SEARCH_URL = "https://api.search.brave.com/res/v1/web/search";
    private static final String DDG_LITE_URL = "https://lite.duckduckgo.com/lite/";
    private static final int TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_MAX_RESULTS = 5;
    private static final int MAX_RESULTS_LIMIT = 20;

    private final Path workingDir;
    private final String apiKey; // nullable — null means DDG fallback
    private final HttpClient httpClient;

    /**
     * Creates a WebSearchTool with Brave Search API key.
     *
     * @param workingDir the working directory
     * @param apiKey     the Brave Search API key (may be null for DDG fallback)
     */
    public WebSearchTool(Path workingDir, String apiKey) {
        this.workingDir = workingDir;
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Creates a WebSearchTool without a Brave API key (uses DuckDuckGo Lite).
     *
     * @param workingDir the working directory
     */
    public WebSearchTool(Path workingDir) {
        this(workingDir, null);
    }

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public String description() {
        return ToolDescriptionLoader.load(name());
    }

    @Override
    public JsonNode inputSchema() {
        return SchemaBuilder.object()
                .requiredProperty("query", SchemaBuilder.string(
                        "The search query string"))
                .optionalProperty("max_results", SchemaBuilder.integer(
                        "Maximum number of results to return (default: 5, max: 20)"))
                .build();
    }

    @Override
    public ToolResult execute(String inputJson) throws Exception {
        var input = MAPPER.readTree(inputJson);

        if (!input.has("query") || input.get("query").asText().isBlank()) {
            return new ToolResult("Missing required parameter: query", true);
        }

        var query = input.get("query").asText();
        int maxResults = DEFAULT_MAX_RESULTS;
        if (input.has("max_results") && !input.get("max_results").isNull()) {
            maxResults = Math.max(1, Math.min(input.get("max_results").asInt(DEFAULT_MAX_RESULTS), MAX_RESULTS_LIMIT));
        }

        log.debug("Web search: query='{}', maxResults={}, backend={}",
                query, maxResults, apiKey != null ? "brave" : "ddg");

        try {
            if (apiKey != null) {
                return performBraveSearch(query, maxResults);
            } else {
                return performDdgSearch(query, maxResults);
            }
        } catch (java.io.IOException e) {
            return new ToolResult("Search failed: " + e.getMessage(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ToolResult("Search interrupted", true);
        }
    }

    /**
     * Returns whether this tool is using Brave Search (true) or DDG fallback (false).
     */
    public boolean isBraveEnabled() {
        return apiKey != null;
    }

    private ToolResult performBraveSearch(String query, int maxResults) throws Exception {
        var encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        var uri = URI.create(BRAVE_SEARCH_URL + "?q=" + encodedQuery + "&count=" + maxResults);

        var request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Accept", "application/json")
                .header("Accept-Encoding", "gzip")
                .header("X-Subscription-Token", apiKey)
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .GET()
                .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 401 || response.statusCode() == 403) {
            return new ToolResult("Brave Search API key is invalid or expired", true);
        }
        if (response.statusCode() == 429) {
            return new ToolResult("Brave Search rate limit exceeded. Please wait and try again.", true);
        }
        if (response.statusCode() >= 400) {
            return new ToolResult("Brave Search API error (HTTP " + response.statusCode() + ")", true);
        }

        var body = response.body();
        if (body == null || body.isEmpty()) {
            return new ToolResult("Empty response from Brave Search", true);
        }

        return formatBraveResults(body, maxResults);
    }

    private ToolResult performDdgSearch(String query, int maxResults) throws Exception {
        var formBody = "q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);

        var request = HttpRequest.newBuilder()
                .uri(URI.create(DDG_LITE_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", "AceClaw/1.0")
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 400) {
            return new ToolResult("DuckDuckGo search failed (HTTP " + response.statusCode() + ")", true);
        }

        var body = response.body();
        if (body == null || body.isEmpty()) {
            return new ToolResult("Empty response from DuckDuckGo", true);
        }

        var results = parseDdgResults(body, maxResults);
        if (results.isEmpty()) {
            return new ToolResult("No results found", false);
        }

        return formatDdgResults(results);
    }

    /**
     * Parses DuckDuckGo Lite HTML response into structured results.
     * Package-private for testability.
     */
    List<DdgResult> parseDdgResults(String html, int maxResults) {
        var results = new ArrayList<DdgResult>();
        Document doc = Jsoup.parse(html);

        // DDG Lite renders results as a table with specific structure:
        // Each result has a link in a <a class="result-link"> and snippet in <td class="result-snippet">
        var links = doc.select("a.result-link");
        var snippets = doc.select("td.result-snippet");

        for (int i = 0; i < links.size() && results.size() < maxResults; i++) {
            Element link = links.get(i);
            String title = link.text().strip();
            String url = link.attr("href").strip();

            if (title.isEmpty() || url.isEmpty()) continue;

            String snippet = "";
            if (i < snippets.size()) {
                snippet = snippets.get(i).text().strip();
            }

            results.add(new DdgResult(title, url, snippet));
        }

        // Fallback: if result-link class is not found, try generic link extraction
        // DDG Lite HTML can vary; some versions use different class names
        if (results.isEmpty()) {
            var allLinks = doc.select("a[href^=http]");
            for (Element link : allLinks) {
                if (results.size() >= maxResults) break;
                String url = link.attr("href");
                String title = link.text().strip();

                // Skip DDG navigation/internal links
                if (url.contains("duckduckgo.com") || title.isEmpty()) continue;

                results.add(new DdgResult(title, url, ""));
            }
        }

        return results;
    }

    private ToolResult formatDdgResults(List<DdgResult> results) {
        var sb = new StringBuilder();
        int count = 0;
        for (var result : results) {
            count++;
            sb.append(count).append(". ").append(result.title()).append('\n');
            sb.append("   URL: ").append(result.url()).append('\n');
            if (!result.snippet().isEmpty()) {
                sb.append("   ").append(result.snippet()).append('\n');
            }
            sb.append('\n');
        }
        return new ToolResult(sb.toString().strip(), false);
    }

    private ToolResult formatBraveResults(String jsonBody, int maxResults) {
        try {
            var root = MAPPER.readTree(jsonBody);
            var webResults = root.path("web").path("results");

            if (!webResults.isArray() || webResults.isEmpty()) {
                return new ToolResult("No results found", false);
            }

            var sb = new StringBuilder();
            int count = 0;
            for (var result : webResults) {
                if (count >= maxResults) break;
                count++;

                var title = result.path("title").asText("(no title)");
                var url = result.path("url").asText("(no url)");
                var snippet = result.path("description").asText("");

                sb.append(count).append(". ").append(title).append('\n');
                sb.append("   URL: ").append(url).append('\n');
                if (!snippet.isEmpty()) {
                    sb.append("   ").append(snippet).append('\n');
                }
                sb.append('\n');
            }

            return new ToolResult(sb.toString().strip(), false);

        } catch (Exception e) {
            log.error("Failed to parse Brave Search response: {}", e.getMessage());
            return new ToolResult("Failed to parse search results: " + e.getMessage(), true);
        }
    }

    /**
     * A single DuckDuckGo search result.
     */
    record DdgResult(String title, String url, String snippet) {}

    /**
     * #480 PR 3: structured {@link Capability.HttpFetch}. The capability
     * surfaces both the actual upstream endpoint AND the actual HTTP
     * method that {@link #execute(String)} will use:
     *
     * <ul>
     *   <li>Brave Search ({@code apiKey} present): {@code GET BRAVE_SEARCH_URL}.</li>
     *   <li>DuckDuckGo Lite fallback (no key): {@code POST DDG_LITE_URL} —
     *       see {@code performDdgSearch}, which sends a form-encoded body.</li>
     * </ul>
     *
     * <p>Hard-coding {@code "GET"} for both branches (the original v3 PR
     * shape) silently misclassified every keyless invocation: an audit
     * reader saw "GET" for a request that actually went out as "POST",
     * and any future PolicyEngine rule that distinguishes verbs (e.g.
     * "deny outbound POST to non-allowlisted hosts") would mis-classify
     * the DDG path. The capability MUST mirror what the runtime does.
     *
     * <p>The {@code query} args field is policy-material but lives in the
     * audit entry's prompt/description, not in the capability — keeping
     * the variant payload minimal mirrors how {@link Capability.McpInvoke}
     * omits args.
     */
    @Override
    public Capability toCapability(JsonNode args) {
        if (args == null || !args.has("query") || args.get("query").asText().isBlank()) {
            throw new IllegalArgumentException("web_search requires a non-blank query");
        }
        boolean useBrave = apiKey != null && !apiKey.isBlank();
        return new Capability.HttpFetch(
                URI.create(useBrave ? BRAVE_SEARCH_URL : DDG_LITE_URL),
                useBrave ? "GET" : "POST");
    }
}

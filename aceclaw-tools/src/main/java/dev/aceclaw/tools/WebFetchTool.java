package dev.aceclaw.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.agent.Tool;
import dev.aceclaw.security.Capability;
import dev.aceclaw.security.CapabilityAware;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Fetches content from a URL and converts HTML to readable text using Jsoup.
 *
 * <p>Uses {@link java.net.http.HttpClient} for HTTP requests and Jsoup for
 * HTML-to-text conversion. Follows redirects, 30s timeout, 30K char output cap.
 */
public final class WebFetchTool implements Tool, CapabilityAware {

    private static final Logger log = LoggerFactory.getLogger(WebFetchTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int TIMEOUT_SECONDS = 30;
    private static final int MAX_OUTPUT_CHARS = 30_000;
    private static final int MAX_RESPONSE_BYTES = 5 * 1024 * 1024; // 5MB

    private static final String USER_AGENT =
            "Mozilla/5.0 (compatible; AceClaw/0.1; +https://github.com/aceclaw)";

    private final Path workingDir;
    private final HttpClient httpClient;

    public WebFetchTool(java.nio.file.Path workingDir) {
        this.workingDir = workingDir;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .build();
    }

    @Override
    public String name() {
        return "web_fetch";
    }

    @Override
    public String description() {
        return ToolDescriptionLoader.load(name());
    }

    @Override
    public JsonNode inputSchema() {
        return SchemaBuilder.object()
                .requiredProperty("url", SchemaBuilder.string(
                        "The URL to fetch content from (must include http:// or https://)"))
                .optionalProperty("raw", SchemaBuilder.bool(
                        "If true, return raw HTML without text conversion. Defaults to false."))
                .build();
    }

    @Override
    public ToolResult execute(String inputJson) throws Exception {
        var input = MAPPER.readTree(inputJson);

        if (!input.has("url") || input.get("url").asText().isBlank()) {
            return new ToolResult("Missing required parameter: url", true);
        }

        var url = input.get("url").asText().strip();
        boolean raw = input.has("raw") && input.get("raw").asBoolean(false);

        // Validate URL
        URI uri;
        try {
            uri = URI.create(url);
            if (uri.getScheme() == null || (!uri.getScheme().equals("http") && !uri.getScheme().equals("https"))) {
                return new ToolResult("URL must start with http:// or https://", true);
            }
        } catch (IllegalArgumentException e) {
            return new ToolResult("Invalid URL: " + e.getMessage(), true);
        }

        log.debug("Fetching URL: {} (raw={})", url, raw);

        try {
            var request = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .GET()
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();

            if (statusCode >= 400) {
                return new ToolResult("HTTP " + statusCode + " error fetching " + url, true);
            }

            var body = response.body();
            if (body == null || body.isEmpty()) {
                return new ToolResult("(empty response from " + url + ")", false);
            }

            // Check content type to decide on HTML conversion
            var contentType = response.headers().firstValue("content-type").orElse("");
            boolean isHtml = contentType.contains("text/html") || contentType.contains("application/xhtml");

            String output;
            if (isHtml && !raw) {
                output = htmlToText(body, url);
            } else {
                output = body;
            }

            return new ToolResult(truncate(output), false);

        } catch (java.net.http.HttpTimeoutException e) {
            return new ToolResult("Request timed out after " + TIMEOUT_SECONDS + " seconds", true);
        } catch (java.io.IOException e) {
            return new ToolResult("Failed to fetch URL: " + e.getMessage(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ToolResult("Request interrupted", true);
        }
    }

    /**
     * Converts HTML to readable text using Jsoup.
     * Preserves document structure (headings, paragraphs, lists) while removing tags.
     */
    private static String htmlToText(String html, String baseUrl) {
        var doc = Jsoup.parse(html, baseUrl);

        // Remove script, style, nav, footer, header elements
        doc.select("script, style, nav, footer, header, aside, noscript").remove();

        // Extract title
        var title = doc.title();

        // Get the main content (prefer main/article, fall back to body)
        Element content = doc.selectFirst("main, article, [role=main]");
        if (content == null) {
            content = doc.body();
        }
        if (content == null) {
            return title.isEmpty() ? "(empty page)" : "Title: " + title;
        }

        var sb = new StringBuilder();
        if (!title.isEmpty()) {
            sb.append("Title: ").append(title).append("\n\n");
        }

        // Use Jsoup's text extraction which handles whitespace well
        sb.append(content.wholeText().replaceAll("(?m)^\\s+$", "").replaceAll("\n{3,}", "\n\n").strip());

        return sb.toString();
    }

    private static String truncate(String output) {
        if (output.length() <= MAX_OUTPUT_CHARS) return output;
        return output.substring(0, MAX_OUTPUT_CHARS) +
               "\n... (output truncated, " + output.length() + " total characters)";
    }

    /**
     * #480 PR 3: structured {@link Capability.HttpFetch}. Method is always
     * {@code GET} — {@code web_fetch} is read-only by construction (it only
     * exposes a {@code .GET()} HttpRequest builder). The capability still
     * reports {@link dev.aceclaw.security.DataFlow#BOTH} (per
     * {@link Capability.HttpFetch}'s own contract) because GET requests
     * carry URL/header/cookie payloads outbound and a "block egress" policy
     * must not be bypassable by hiding data in a query string.
     */
    @Override
    public Capability toCapability(JsonNode args) {
        if (args == null || !args.has("url") || args.get("url").asText().isBlank()) {
            throw new IllegalArgumentException("web_fetch requires a non-blank url");
        }
        URI uri;
        try {
            uri = URI.create(args.get("url").asText().strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("web_fetch url is not a valid URI: " + e.getMessage(), e);
        }
        return new Capability.HttpFetch(uri, "GET");
    }
}

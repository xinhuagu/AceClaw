package dev.aceclaw.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.ScreenshotType;
import dev.aceclaw.core.agent.Tool;
import dev.aceclaw.security.Capability;
import dev.aceclaw.security.CapabilityAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;

/**
 * Browser automation tool using Playwright for Java.
 *
 * <p>Manages a lazy singleton headless Chromium browser instance. Supports
 * actions: launch, navigate, click, type, screenshot, get_text, evaluate, close.
 *
 * <p>Implements {@link AutoCloseable} for cleanup on daemon shutdown.
 */
public final class BrowserTool implements Tool, CapabilityAware, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BrowserTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int MAX_OUTPUT_CHARS = 30_000;
    private static final int DEFAULT_TIMEOUT_MS = 30_000;
    private static final int LAUNCH_TIMEOUT_MS = 15_000;

    private final Path workingDir;

    // Lazy-initialized browser instance
    private Playwright playwright;
    private Browser browser;
    private Page page;

    public BrowserTool(Path workingDir) {
        this.workingDir = workingDir;
    }

    @Override
    public String name() {
        return "browser";
    }

    @Override
    public String description() {
        return ToolDescriptionLoader.load(name());
    }

    @Override
    public JsonNode inputSchema() {
        return SchemaBuilder.object()
                .requiredProperty("action", SchemaBuilder.stringEnum(
                        "The browser action to perform",
                        "launch", "navigate", "click", "type",
                        "screenshot", "get_text", "evaluate", "close"))
                .optionalProperty("url", SchemaBuilder.string(
                        "URL to navigate to (for 'navigate' action)"))
                .optionalProperty("selector", SchemaBuilder.string(
                        "CSS selector for element interaction (for 'click', 'type', 'get_text' actions)"))
                .optionalProperty("text", SchemaBuilder.string(
                        "Text to type (for 'type' action)"))
                .optionalProperty("script", SchemaBuilder.string(
                        "JavaScript code to execute (for 'evaluate' action)"))
                .optionalProperty("output_path", SchemaBuilder.string(
                        "File path to save screenshot (for 'screenshot' action)"))
                .build();
    }

    @Override
    public ToolResult execute(String inputJson) throws Exception {
        var input = MAPPER.readTree(inputJson);

        if (!input.has("action") || input.get("action").asText().isBlank()) {
            return new ToolResult("Missing required parameter: action", true);
        }

        var action = input.get("action").asText();

        return switch (action) {
            case "launch" -> doLaunch();
            case "navigate" -> doNavigate(input);
            case "click" -> doClick(input);
            case "type" -> doType(input);
            case "screenshot" -> doScreenshot(input);
            case "get_text" -> doGetText(input);
            case "evaluate" -> doEvaluate(input);
            case "close" -> doClose();
            default -> new ToolResult("Unknown action: " + action +
                    ". Valid actions: launch, navigate, click, type, screenshot, get_text, evaluate, close", true);
        };
    }

    private ToolResult doLaunch() {
        try {
            if (browser != null && browser.isConnected()) {
                return new ToolResult("Browser is already running. Use 'close' first to restart.", false);
            }

            // Clean up any existing state
            closeQuietly();

            playwright = Playwright.create();

            // Try system Chrome first, fall back to bundled Chromium if it fails or hangs.
            // System Chrome can conflict with existing instances (e.g. port 9222 contention).
            String channel = "chrome";
            try {
                browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                        .setChannel(channel)
                        .setHeadless(true)
                        .setTimeout(LAUNCH_TIMEOUT_MS));
            } catch (Exception systemChromeErr) {
                log.warn("System Chrome launch failed ({}), falling back to bundled Chromium",
                        systemChromeErr.getMessage());
                channel = "bundled";
                browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                        .setHeadless(true)
                        .setTimeout(LAUNCH_TIMEOUT_MS));
            }
            page = browser.newPage();
            page.setDefaultTimeout(DEFAULT_TIMEOUT_MS);

            log.info("Browser launched (headless {})", channel);
            return new ToolResult("Browser launched successfully (headless " + channel + ")", false);

        } catch (Exception e) {
            log.error("Failed to launch browser: {}", e.getMessage());
            closeQuietly();
            return new ToolResult("Failed to launch browser: " + e.getMessage() +
                    "\nMake sure Chrome is installed, or install Chromium via: npx playwright install chromium", true);
        }
    }

    private ToolResult doNavigate(JsonNode input) {
        if (!ensureBrowserRunning()) {
            return new ToolResult("Browser not running. Call 'launch' first.", true);
        }
        if (!input.has("url") || input.get("url").asText().isBlank()) {
            return new ToolResult("Missing required parameter: url", true);
        }

        var url = input.get("url").asText();
        try {
            var response = page.navigate(url);
            var status = response != null ? response.status() : -1;
            var title = page.title();
            return new ToolResult("Navigated to: " + url +
                    "\nStatus: " + status +
                    "\nTitle: " + title, false);
        } catch (Exception e) {
            return new ToolResult("Navigation failed: " + e.getMessage(), true);
        }
    }

    private ToolResult doClick(JsonNode input) {
        if (!ensureBrowserRunning()) {
            return new ToolResult("Browser not running. Call 'launch' first.", true);
        }
        if (!input.has("selector") || input.get("selector").asText().isBlank()) {
            return new ToolResult("Missing required parameter: selector", true);
        }

        var selector = input.get("selector").asText();
        try {
            page.click(selector);
            return new ToolResult("Clicked: " + selector, false);
        } catch (Exception e) {
            return new ToolResult("Click failed on '" + selector + "': " + e.getMessage(), true);
        }
    }

    private ToolResult doType(JsonNode input) {
        if (!ensureBrowserRunning()) {
            return new ToolResult("Browser not running. Call 'launch' first.", true);
        }
        if (!input.has("selector") || input.get("selector").asText().isBlank()) {
            return new ToolResult("Missing required parameter: selector", true);
        }
        if (!input.has("text")) {
            return new ToolResult("Missing required parameter: text", true);
        }

        var selector = input.get("selector").asText();
        var text = input.get("text").asText();
        try {
            page.fill(selector, text);
            return new ToolResult("Typed into '" + selector + "': " + text, false);
        } catch (Exception e) {
            return new ToolResult("Type failed on '" + selector + "': " + e.getMessage(), true);
        }
    }

    private ToolResult doScreenshot(JsonNode input) {
        if (!ensureBrowserRunning()) {
            return new ToolResult("Browser not running. Call 'launch' first.", true);
        }

        try {
            Path outputPath;
            if (input.has("output_path") && !input.get("output_path").isNull()
                    && !input.get("output_path").asText().isBlank()) {
                outputPath = PathResolver.resolve(input.get("output_path").asText(), workingDir);
            } else {
                outputPath = Files.createTempFile("aceclaw-browser-", ".png");
            }

            var bytes = page.screenshot(new Page.ScreenshotOptions()
                    .setType(ScreenshotType.PNG)
                    .setFullPage(true));

            Files.write(outputPath, bytes);
            return new ToolResult("Screenshot saved to: " + outputPath, false);

        } catch (Exception e) {
            return new ToolResult("Screenshot failed: " + e.getMessage(), true);
        }
    }

    private ToolResult doGetText(JsonNode input) {
        if (!ensureBrowserRunning()) {
            return new ToolResult("Browser not running. Call 'launch' first.", true);
        }

        try {
            String text;
            if (input.has("selector") && !input.get("selector").isNull()
                    && !input.get("selector").asText().isBlank()) {
                var selector = input.get("selector").asText();
                text = page.locator(selector).textContent();
            } else {
                text = page.locator("body").textContent();
            }

            if (text == null || text.isBlank()) {
                return new ToolResult("(no text content)", false);
            }

            return new ToolResult(truncate(text), false);

        } catch (Exception e) {
            return new ToolResult("Get text failed: " + e.getMessage(), true);
        }
    }

    private ToolResult doEvaluate(JsonNode input) {
        if (!ensureBrowserRunning()) {
            return new ToolResult("Browser not running. Call 'launch' first.", true);
        }
        if (!input.has("script") || input.get("script").asText().isBlank()) {
            return new ToolResult("Missing required parameter: script", true);
        }

        var script = input.get("script").asText();
        try {
            var result = page.evaluate(script);
            if (result == null) {
                return new ToolResult("(undefined)", false);
            }
            var output = result.toString();
            return new ToolResult(truncate(output), false);

        } catch (Exception e) {
            return new ToolResult("JavaScript evaluation failed: " + e.getMessage(), true);
        }
    }

    private ToolResult doClose() {
        closeQuietly();
        return new ToolResult("Browser closed", false);
    }

    private boolean ensureBrowserRunning() {
        return browser != null && browser.isConnected() && page != null;
    }

    private void closeQuietly() {
        try {
            if (page != null) {
                page.close();
            }
        } catch (Exception e) {
            log.debug("Error closing page: {}", e.getMessage());
        }
        try {
            if (browser != null) {
                browser.close();
            }
        } catch (Exception e) {
            log.debug("Error closing browser: {}", e.getMessage());
        }
        try {
            if (playwright != null) {
                playwright.close();
            }
        } catch (Exception e) {
            log.debug("Error closing playwright: {}", e.getMessage());
        }
        page = null;
        browser = null;
        playwright = null;
    }

    @Override
    public void close() {
        closeQuietly();
        log.info("Browser tool closed");
    }

    private static String truncate(String output) {
        if (output.length() <= MAX_OUTPUT_CHARS) return output;
        return output.substring(0, MAX_OUTPUT_CHARS) +
               "\n... (output truncated, " + output.length() + " total characters)";
    }

    /**
     * #480 PR 3: structured {@link Capability.BrowserAction}. The {@code url}
     * field is populated only for actions that target one ({@code navigate}),
     * leaving it empty for {@code click} / {@code type} / {@code screenshot}
     * etc. — that lets domain-allowlist policies match exactly the requests
     * they care about. An invalid URL is dropped to {@link Optional#empty()}
     * rather than thrown so the policy can still see {@code action} and
     * decide; the underlying tool will surface the URL error during execution.
     */
    @Override
    public Capability toCapability(JsonNode args) {
        if (args == null || !args.has("action") || args.get("action").asText().isBlank()) {
            throw new IllegalArgumentException("browser requires a non-blank action");
        }
        Optional<URI> uri = Optional.empty();
        if (args.has("url") && !args.get("url").isNull() && !args.get("url").asText().isBlank()) {
            try {
                uri = Optional.of(URI.create(args.get("url").asText()));
            } catch (IllegalArgumentException ignored) {
                // Leave empty — invalid URL surfaces in execute(), not at the
                // policy boundary.
            }
        }
        return new Capability.BrowserAction(args.get("action").asText(), uri);
    }
}

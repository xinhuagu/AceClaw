package dev.aceclaw.daemon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Generates dynamic tool guidance sections for the system prompt based on
 * which tools are actually registered.
 *
 * <p>This prevents the system prompt from referencing tools that don't exist
 * (e.g., web_search without API key, screen_capture on Linux), which can
 * confuse the agent and cause it to attempt unavailable tools.
 */
public final class ToolGuidanceGenerator {

    private static final Logger log = LoggerFactory.getLogger(ToolGuidanceGenerator.class);

    private static final String COMPOSITION_RESOURCE = "/tool-guidance-composition.md";

    private ToolGuidanceGenerator() {}

    /**
     * Generates dynamic tool guidance based on which tools are registered.
     *
     * @param registeredToolNames the set of tool names currently registered
     * @param hasBraveApiKey      whether a Brave Search API key is configured
     * @return the generated guidance text to inject into the system prompt
     */
    public static String generate(Set<String> registeredToolNames, boolean hasBraveApiKey) {
        var sb = new StringBuilder();

        sb.append("\n\n## Tool Priority: Always Prefer Text Over Images\n\n");
        sb.append("CRITICAL: When fetching information from the web, **always return TEXT results ");
        sb.append("directly to the user**. Do NOT save results as images/screenshots and do NOT ");
        sb.append("ask the user to look at files. The user sees your text output — give them ");
        sb.append("the answer directly.\n\n");

        // Priority order — only list tools that actually exist
        sb.append("**Priority order for getting web information:**\n");
        int priority = 1;
        if (registeredToolNames.contains("web_search")) {
            sb.append(priority++).append(". **web_search** — fastest, returns text results directly. ");
            sb.append("Try this first for any real-time information (weather, news, prices, docs, etc.)\n");
        }
        if (registeredToolNames.contains("web_fetch")) {
            sb.append(priority++).append(". **web_fetch** — fetch a specific URL and extract text content. ");
            sb.append("Use when you know the URL");
            if (registeredToolNames.contains("web_search")) {
                sb.append(" or found one via web_search");
            }
            sb.append(".\n");
        }
        if (registeredToolNames.contains("browser")) {
            sb.append(priority++).append(". **browser** with get_text — when web_fetch fails ");
            sb.append("(JavaScript-rendered pages, login walls). Launch browser, navigate, ");
            sb.append("then use get_text to extract text.\n");
        }
        if (registeredToolNames.contains("screen_capture")) {
            sb.append(priority++).append(". **screen_capture** — LAST RESORT ONLY. Never use ");
            sb.append("screen_capture to get information that can be obtained as text. ");
            sb.append("Only use for tasks that inherently require visual output ");
            sb.append("(UI debugging, visual verification).\n");
        }

        sb.append("\n**NEVER do this:** Take a screenshot of a website and give the user a PNG file. ");
        sb.append("Instead, fetch the data as text and present it directly.\n");

        // Tool-specific guidelines — only for registered tools
        sb.append("\n## Tool-specific guidelines\n\n");
        if (registeredToolNames.contains("web_fetch")) {
            sb.append("- **web_fetch**: Prefer over bash with curl. Good for documentation, APIs, static pages.\n");
        }
        if (registeredToolNames.contains("web_search")) {
            sb.append("- **web_search**: Good for real-time information — weather, news, current events, finding URLs.");
            if (hasBraveApiKey) {
                sb.append(" Using Brave Search API.\n");
            } else {
                sb.append(" Using DuckDuckGo (free). For better results, configure BRAVE_SEARCH_API_KEY.\n");
            }
        }
        if (registeredToolNames.contains("browser")) {
            sb.append("- **browser**: Only for pages that require JavaScript rendering or interaction ");
            sb.append("(clicking, typing, form submission). Always use get_text to extract text content, ");
            sb.append("not screenshot.\n");
        }
        if (registeredToolNames.contains("list_directory")) {
            sb.append("- **list_directory**: Use instead of bash with ls.\n");
        }
        if (registeredToolNames.contains("applescript")) {
            sb.append("- **applescript**: macOS automation — controlling applications, system dialogs, ");
            sb.append("Finder operations. Only available on macOS.\n");
        }
        if (registeredToolNames.contains("skill")) {
            sb.append("- **skill**: For desktop UI clicking, always prefer `click-precision-robust` ");
            sb.append("instead of ad-hoc AppleScript/cliclick loops. Require post-click verification and ");
            sb.append("bounded retries with explicit failure reasons.\n");
            sb.append("  For Microsoft Teams calendar tasks, do this sequence: ");
            sb.append("click `Calendar` first, verify `element_exists=Go to next week`, ");
            sb.append("then click `Go to next week`.\n");
        }
        if (registeredToolNames.contains("screen_capture")) {
            sb.append("- **screen_capture**: ONLY for visual tasks (UI screenshots, visual debugging). ");
            sb.append("Never use to \"read\" information that can be fetched as text.\n");
            sb.append("  Region must be `x,y,width,height`; normalize before calling.\n");
        }

        // Fallback chain — adapts based on available web tools
        sb.append("\n## Mandatory Fallback Chain for Web Information\n\n");
        generateFallbackChain(sb, registeredToolNames);

        // Availability notes
        if (!hasBraveApiKey && registeredToolNames.contains("web_search")) {
            sb.append("\n**Note:** web_search is using DuckDuckGo (free, no API key needed). ");
            sb.append("For higher-quality results, configure BRAVE_SEARCH_API_KEY in ");
            sb.append("~/.aceclaw/config.json or as an environment variable.\n");
        }

        // Tool composition guidance (always appended)
        sb.append(loadCompositionGuidance());

        return sb.toString();
    }

    private static void generateFallbackChain(StringBuilder sb, Set<String> tools) {
        boolean hasWebSearch = tools.contains("web_search");
        boolean hasWebFetch = tools.contains("web_fetch");
        boolean hasBrowser = tools.contains("browser");
        boolean hasBash = tools.contains("bash");

        sb.append("If one approach fails, immediately try the next — NEVER stop and ask the user:\n");

        var chain = new java.util.ArrayList<String>();
        if (hasWebSearch) chain.add("web_search");
        if (hasWebFetch) chain.add("web_fetch with a known URL");
        if (hasWebFetch) chain.add("web_fetch with a different URL");
        if (hasBrowser) chain.add("browser (launch -> navigate -> get_text)");
        if (hasBash) chain.add("bash with curl");

        for (int i = 0; i < chain.size(); i++) {
            if (i == 0) {
                sb.append("Try ").append(chain.get(i));
            } else {
                sb.append(" -> if that fails, try ").append(chain.get(i));
            }
        }
        if (!chain.isEmpty()) {
            sb.append(" -> only THEN report failure with all attempts listed.\n");
        }
    }

    private static String loadCompositionGuidance() {
        try (InputStream is = ToolGuidanceGenerator.class.getResourceAsStream(COMPOSITION_RESOURCE)) {
            if (is != null) {
                return "\n\n" + new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("Failed to load tool composition guidance: {}", e.getMessage());
        }
        return "";
    }
}

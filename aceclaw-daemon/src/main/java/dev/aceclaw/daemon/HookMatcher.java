package dev.aceclaw.daemon;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Associates a tool name pattern with a list of hook configurations.
 *
 * <p>The {@code matcher} is a regex pattern. If null, the hooks match all tools.
 *
 * @param matcher compiled regex pattern (null = match all tools)
 * @param hooks   list of hook configurations to execute on match
 */
public record HookMatcher(Pattern matcher, List<HookConfig> hooks) {

    public HookMatcher {
        if (hooks == null || hooks.isEmpty()) {
            throw new IllegalArgumentException("Hooks list must not be empty");
        }
        hooks = List.copyOf(hooks);
    }

    /**
     * Tests whether this matcher applies to the given tool name.
     *
     * @param toolName the tool name to test
     * @return true if this matcher's pattern matches (or if matcher is null, matching all)
     */
    public boolean matches(String toolName) {
        if (matcher == null) {
            return true;
        }
        return matcher.matcher(toolName).matches();
    }

    /**
     * Creates a matcher that matches all tools.
     */
    public static HookMatcher matchAll(List<HookConfig> hooks) {
        return new HookMatcher(null, hooks);
    }

    /**
     * Creates a matcher for an exact tool name.
     */
    public static HookMatcher exact(String toolName, List<HookConfig> hooks) {
        return new HookMatcher(Pattern.compile(Pattern.quote(toolName)), hooks);
    }

    /**
     * Creates a matcher from a regex pattern string.
     */
    public static HookMatcher regex(String pattern, List<HookConfig> hooks) {
        return new HookMatcher(Pattern.compile(pattern), hooks);
    }
}

package dev.aceclaw.daemon;

import dev.aceclaw.core.agent.HookEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Immutable registry of hook matchers organized by event type.
 *
 * <p>Resolves matching {@link HookConfig} entries for a given {@link HookEvent}
 * by testing the event's tool name against all registered matchers for that event type.
 * Matchers are tested in registration order; all matching configs are collected.
 */
public final class HookRegistry {

    private static final Logger log = LoggerFactory.getLogger(HookRegistry.class);

    /** Event type name → ordered list of matchers. */
    private final Map<String, List<HookMatcher>> matchers;

    private HookRegistry(Map<String, List<HookMatcher>> matchers) {
        // Deep-copy to ensure immutability
        var copy = new LinkedHashMap<String, List<HookMatcher>>();
        for (var entry : matchers.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        this.matchers = Map.copyOf(copy);
    }

    /**
     * Returns all matching hook configs for the given event.
     * Matchers are tested in order; all matching hooks are returned in order.
     *
     * @param event the hook event
     * @return list of matching hook configs (may be empty)
     */
    public List<HookConfig> resolve(HookEvent event) {
        var eventMatchers = matchers.get(event.eventName());
        if (eventMatchers == null || eventMatchers.isEmpty()) {
            return List.of();
        }
        var result = new ArrayList<HookConfig>();
        for (var matcher : eventMatchers) {
            if (matcher.matches(event.toolName())) {
                result.addAll(matcher.hooks());
            }
        }
        return List.copyOf(result);
    }

    /**
     * Returns whether this registry has any hooks for the given event type.
     */
    public boolean hasHooksFor(String eventName) {
        var eventMatchers = matchers.get(eventName);
        return eventMatchers != null && !eventMatchers.isEmpty();
    }

    /**
     * Returns true if this registry has no hooks at all.
     */
    public boolean isEmpty() {
        return matchers.isEmpty();
    }

    /**
     * Returns the total number of hook matchers across all event types.
     */
    public int size() {
        return matchers.values().stream().mapToInt(List::size).sum();
    }

    /**
     * Loads a HookRegistry from the config map format used in {@code config.json}.
     *
     * <p>Expected structure:
     * <pre>
     * {
     *   "PreToolUse": [
     *     { "matcher": "bash", "hooks": [{ "type": "command", "command": "...", "timeout": 30 }] }
     *   ],
     *   "PostToolUse": [...]
     * }
     * </pre>
     *
     * @param configMap raw config map (event name → list of matcher configs)
     * @return the loaded registry
     */
    public static HookRegistry load(Map<String, List<AceClawConfig.HookMatcherFormat>> configMap) {
        if (configMap == null || configMap.isEmpty()) {
            return empty();
        }

        var result = new LinkedHashMap<String, List<HookMatcher>>();

        for (var entry : configMap.entrySet()) {
            String eventName = entry.getKey();
            // Validate event name
            if (!isValidEventName(eventName)) {
                log.warn("Ignoring unknown hook event type: {}", eventName);
                continue;
            }

            var matcherFormats = entry.getValue();
            if (matcherFormats == null || matcherFormats.isEmpty()) {
                continue;
            }

            var hookMatchers = new ArrayList<HookMatcher>();
            for (var mf : matcherFormats) {
                try {
                    var hookConfigs = new ArrayList<HookConfig>();
                    if (mf.hooks != null) {
                        for (var hf : mf.hooks) {
                            String type = hf.type != null ? hf.type : "command";
                            hookConfigs.add(new HookConfig(type, hf.command, hf.timeout));
                        }
                    }
                    if (hookConfigs.isEmpty()) {
                        continue;
                    }

                    Pattern pattern = null;
                    if (mf.matcher != null && !mf.matcher.isBlank()) {
                        pattern = Pattern.compile(mf.matcher);
                    }

                    hookMatchers.add(new HookMatcher(pattern, hookConfigs));
                } catch (PatternSyntaxException e) {
                    log.warn("Invalid hook matcher regex '{}': {}", mf.matcher, e.getMessage());
                } catch (IllegalArgumentException e) {
                    log.warn("Invalid hook config: {}", e.getMessage());
                }
            }

            if (!hookMatchers.isEmpty()) {
                result.computeIfAbsent(eventName, _ -> new ArrayList<>()).addAll(hookMatchers);
            }
        }

        return new HookRegistry(result);
    }

    /**
     * Returns an empty registry with no hooks.
     */
    public static HookRegistry empty() {
        return new HookRegistry(Map.of());
    }

    private static boolean isValidEventName(String name) {
        return "PreToolUse".equals(name)
                || "PostToolUse".equals(name)
                || "PostToolUseFailure".equals(name);
    }
}

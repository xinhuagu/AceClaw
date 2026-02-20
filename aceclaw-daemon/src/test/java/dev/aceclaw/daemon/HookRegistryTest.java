package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.agent.HookEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link HookRegistry} — loading from config, matching, and resolution.
 */
class HookRegistryTest {

    @Test
    void emptyRegistryResolvesNothing() {
        var registry = HookRegistry.empty();
        assertThat(registry.isEmpty()).isTrue();
        assertThat(registry.size()).isZero();

        var event = new HookEvent.PreToolUse("sess1", "/tmp", "bash", null);
        assertThat(registry.resolve(event)).isEmpty();
    }

    @Test
    void exactMatchReturnsHooks() {
        var configMap = buildConfigMap("PreToolUse", "bash",
                "command", "echo allow", 0);

        var registry = HookRegistry.load(configMap);
        assertThat(registry.isEmpty()).isFalse();
        assertThat(registry.hasHooksFor("PreToolUse")).isTrue();

        var event = new HookEvent.PreToolUse("sess1", "/tmp", "bash", null);
        var hooks = registry.resolve(event);
        assertThat(hooks).hasSize(1);
        assertThat(hooks.getFirst().command()).isEqualTo("echo allow");
    }

    @Test
    void regexMatchReturnsHooks() {
        var configMap = buildConfigMap("PreToolUse", "bash|write_file",
                "command", "echo matched", 0);

        var registry = HookRegistry.load(configMap);

        var bashEvent = new HookEvent.PreToolUse("s1", "/tmp", "bash", null);
        assertThat(registry.resolve(bashEvent)).hasSize(1);

        var writeEvent = new HookEvent.PreToolUse("s1", "/tmp", "write_file", null);
        assertThat(registry.resolve(writeEvent)).hasSize(1);

        var readEvent = new HookEvent.PreToolUse("s1", "/tmp", "read_file", null);
        assertThat(registry.resolve(readEvent)).isEmpty();
    }

    @Test
    void nullMatcherMatchesAll() {
        // null matcher = match all tools
        var configMap = buildConfigMap("PostToolUse", null,
                "command", "echo audit", 0);

        var registry = HookRegistry.load(configMap);

        var event = new HookEvent.PostToolUse("s1", "/tmp", "any_tool", null, "output");
        assertThat(registry.resolve(event)).hasSize(1);
    }

    @Test
    void multipleMatchersOrder() {
        var matcherFormats = new ArrayList<AceClawConfig.HookMatcherFormat>();

        // First matcher: all tools
        var mf1 = new AceClawConfig.HookMatcherFormat();
        mf1.matcher = null;
        var hf1 = new AceClawConfig.HookConfigFormat();
        hf1.type = "command";
        hf1.command = "echo first";
        mf1.hooks = List.of(hf1);
        matcherFormats.add(mf1);

        // Second matcher: bash only
        var mf2 = new AceClawConfig.HookMatcherFormat();
        mf2.matcher = "bash";
        var hf2 = new AceClawConfig.HookConfigFormat();
        hf2.type = "command";
        hf2.command = "echo second";
        mf2.hooks = List.of(hf2);
        matcherFormats.add(mf2);

        var configMap = new HashMap<String, List<AceClawConfig.HookMatcherFormat>>();
        configMap.put("PreToolUse", matcherFormats);

        var registry = HookRegistry.load(configMap);

        // bash should match both
        var bashEvent = new HookEvent.PreToolUse("s1", "/tmp", "bash", null);
        var hooks = registry.resolve(bashEvent);
        assertThat(hooks).hasSize(2);
        assertThat(hooks.get(0).command()).isEqualTo("echo first");
        assertThat(hooks.get(1).command()).isEqualTo("echo second");

        // read_file should only match the first (match-all)
        var readEvent = new HookEvent.PreToolUse("s1", "/tmp", "read_file", null);
        assertThat(registry.resolve(readEvent)).hasSize(1);
    }

    @Test
    void noMatchReturnsEmpty() {
        var configMap = buildConfigMap("PreToolUse", "bash",
                "command", "echo block", 0);
        var registry = HookRegistry.load(configMap);

        var event = new HookEvent.PreToolUse("s1", "/tmp", "read_file", null);
        assertThat(registry.resolve(event)).isEmpty();
    }

    @Test
    void hasHooksForReportsCorrectly() {
        var configMap = buildConfigMap("PreToolUse", "bash",
                "command", "echo test", 0);
        var registry = HookRegistry.load(configMap);

        assertThat(registry.hasHooksFor("PreToolUse")).isTrue();
        assertThat(registry.hasHooksFor("PostToolUse")).isFalse();
        assertThat(registry.hasHooksFor("PostToolUseFailure")).isFalse();
    }

    @Test
    void loadFromConfigWithInvalidRegexSkips() {
        var configMap = buildConfigMap("PreToolUse", "[invalid",
                "command", "echo test", 0);
        var registry = HookRegistry.load(configMap);

        // Invalid regex should be skipped
        assertThat(registry.isEmpty()).isTrue();
    }

    @Test
    void loadFromConfigWithUnknownEventSkips() {
        var configMap = buildConfigMap("UnknownEvent", "bash",
                "command", "echo test", 0);
        var registry = HookRegistry.load(configMap);

        assertThat(registry.isEmpty()).isTrue();
    }

    @Test
    void defaultTimeoutApplied() {
        var configMap = buildConfigMap("PreToolUse", "bash",
                "command", "echo test", 0);
        var registry = HookRegistry.load(configMap);

        var event = new HookEvent.PreToolUse("s1", "/tmp", "bash", null);
        var hooks = registry.resolve(event);
        assertThat(hooks).hasSize(1);
        assertThat(hooks.getFirst().timeout()).isEqualTo(HookConfig.DEFAULT_TIMEOUT);
    }

    // -- Helpers --

    private static Map<String, List<AceClawConfig.HookMatcherFormat>> buildConfigMap(
            String eventName, String matcher, String type, String command, int timeout) {
        var hf = new AceClawConfig.HookConfigFormat();
        hf.type = type;
        hf.command = command;
        hf.timeout = timeout;

        var mf = new AceClawConfig.HookMatcherFormat();
        mf.matcher = matcher;
        mf.hooks = List.of(hf);

        var list = new ArrayList<AceClawConfig.HookMatcherFormat>();
        list.add(mf);

        var map = new HashMap<String, List<AceClawConfig.HookMatcherFormat>>();
        map.put(eventName, list);
        return map;
    }
}

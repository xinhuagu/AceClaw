package dev.chelava.core.agent;

import dev.chelava.core.llm.ToolDefinition;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry for tools available to the agent loop.
 *
 * <p>Tools are registered by name and can be looked up for execution
 * or exported as {@link ToolDefinition} lists for LLM requests.
 */
public final class ToolRegistry {

    private final ConcurrentHashMap<String, Tool> tools = new ConcurrentHashMap<>();

    /**
     * Registers a tool. Replaces any existing tool with the same name.
     *
     * @param tool the tool to register
     */
    public void register(Tool tool) {
        tools.put(tool.name(), tool);
    }

    /**
     * Looks up a tool by name.
     *
     * @param name the tool name
     * @return the tool, or empty if not registered
     */
    public Optional<Tool> get(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * Returns all registered tools.
     */
    public List<Tool> all() {
        return List.copyOf(tools.values());
    }

    /**
     * Converts all registered tools to {@link ToolDefinition} instances
     * for inclusion in LLM requests.
     */
    public List<ToolDefinition> toDefinitions() {
        return tools.values().stream()
                .map(Tool::toDefinition)
                .toList();
    }

    /**
     * Returns the number of registered tools.
     */
    public int size() {
        return tools.size();
    }
}

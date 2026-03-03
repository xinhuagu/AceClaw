package dev.aceclaw.core.agent;

import dev.aceclaw.core.llm.ToolDefinition;

import java.util.ArrayList;
import java.util.Comparator;
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
     * Converts all registered tools to {@link ToolDefinition} instances,
     * truncating descriptions to fit within a total character budget.
     *
     * <p>When the sum of all description lengths exceeds {@code maxTotalDescriptionChars},
     * the longest descriptions are truncated first. Each truncated description keeps
     * its first paragraph and a {@code [TRUNCATED]} marker.
     *
     * @param maxTotalDescriptionChars maximum total characters allowed across all descriptions
     * @return tool definitions with descriptions fitting within the budget
     */
    public List<ToolDefinition> toDefinitions(int maxTotalDescriptionChars) {
        var defs = toDefinitions();
        int totalChars = defs.stream()
                .mapToInt(d -> d.description() != null ? d.description().length() : 0)
                .sum();

        if (totalChars <= maxTotalDescriptionChars || defs.isEmpty()) {
            return defs;
        }

        // Sort indices by description length descending to truncate longest first
        var indexed = new ArrayList<>(defs);
        var order = new ArrayList<Integer>();
        for (int i = 0; i < indexed.size(); i++) order.add(i);
        order.sort(Comparator.comparingInt(
                (Integer i) -> indexed.get(i).description() != null
                        ? indexed.get(i).description().length() : 0).reversed());

        var descriptions = new String[defs.size()];
        for (int i = 0; i < defs.size(); i++) {
            descriptions[i] = defs.get(i).description() != null ? defs.get(i).description() : "";
        }
        int remaining = totalChars;

        for (int idx : order) {
            if (remaining <= maxTotalDescriptionChars) break;

            String desc = descriptions[idx];
            if (desc.isEmpty()) continue;

            int excess = remaining - maxTotalDescriptionChars;
            int targetLen = Math.max(0, desc.length() - excess);

            // Keep at least first paragraph (up to first double newline)
            int firstParaEnd = desc.indexOf("\n\n");
            int minLen = firstParaEnd > 0 ? firstParaEnd : Math.min(desc.length(), 200);

            int cutLen = Math.max(targetLen, minLen);
            if (cutLen < desc.length()) {
                String truncated = desc.substring(0, cutLen) + "\n[TRUNCATED]";
                remaining -= (desc.length() - truncated.length());
                descriptions[idx] = truncated;
            }
        }

        var result = new ArrayList<ToolDefinition>(defs.size());
        for (int i = 0; i < defs.size(); i++) {
            var d = defs.get(i);
            result.add(new ToolDefinition(d.name(), descriptions[i], d.inputSchema()));
        }
        return List.copyOf(result);
    }

    /**
     * Returns the number of registered tools.
     */
    public int size() {
        return tools.size();
    }
}

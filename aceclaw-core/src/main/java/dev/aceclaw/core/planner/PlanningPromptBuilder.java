package dev.aceclaw.core.planner;

import dev.aceclaw.core.llm.ToolDefinition;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds the system and user prompts for LLM-based plan generation.
 */
public final class PlanningPromptBuilder {

    static final String SYSTEM_PROMPT = """
            You are a task planning agent. Your job is to break down a complex goal into \
            a sequence of concrete, actionable steps. Each step should be achievable in a single \
            agent turn (one ReAct loop iteration with tool calls).

            Rules:
            - Output ONLY valid JSON. No markdown, no explanation, no preamble.
            - Return a JSON object with a single key "steps" containing an array.
            - Each step must have: "name" (short title), "description" (what to do), \
              "requiredTools" (list of tool names), "fallbackApproach" (alternative if primary fails, or null).
            - Order steps logically: research first, then implement, then verify.
            - Keep steps focused: one logical unit of work per step.
            - Use 2-15 steps. Fewer for simpler tasks, more for complex ones.
            - Reference actual tool names from the available tools list.
            """;

    private PlanningPromptBuilder() {}

    /**
     * Builds the user prompt for plan generation.
     *
     * @param goal           the user's original goal
     * @param availableTools tools available to the agent
     * @return the formatted user prompt
     */
    public static String buildUserPrompt(String goal, List<ToolDefinition> availableTools) {
        var toolNames = availableTools.stream()
                .map(ToolDefinition::name)
                .collect(Collectors.joining(", "));

        return """
                ## Available Tools
                %s

                ## Goal
                %s

                Generate a step-by-step plan as JSON:
                """.formatted(toolNames, goal);
    }
}

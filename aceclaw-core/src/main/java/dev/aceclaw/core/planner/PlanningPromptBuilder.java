package dev.aceclaw.core.planner;

import dev.aceclaw.core.llm.ToolDefinition;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Builds the system and user prompts for LLM-based plan generation.
 */
public final class PlanningPromptBuilder {

    static final String SYSTEM_PROMPT = """
            You are a task planning agent. Your job is to break down a complex goal into \
            a sequence of concrete, actionable steps. Each step is one full ReAct iteration \
            — the agent can read context, reason, call several tools, and produce output, \
            all within ONE step.

            Rules:
            - Output ONLY valid JSON. No markdown, no explanation, no preamble.
            - Return a JSON object with a single key "steps" containing an array.
            - Each step must have: "name" (short title), "description" (what to do), \
              "requiredTools" (list of tool names), "fallbackApproach" (alternative if primary fails, or null).
            - Order steps logically: research first, then implement, then verify.
            - Keep steps focused: one logical unit of work per step.
            - Default to 4-6 steps. Use 2-3 for narrowly-scoped tasks; only exceed 6 \
              when the task has genuinely distinct phases (e.g., research → design → \
              implement → test → deploy). Hard cap is 15.
            - Do NOT over-decompose. A single step covers "read context, decide, call \
              tools, summarise" as one ReAct iteration. Splitting "read X" and "use X" \
              across separate steps is wrong — they are one step. Trivial bookkeeping \
              steps ("save the result", "report what happened") are also not steps; \
              they are part of the surrounding step.
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
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("goal must not be null or blank");
        }
        var tools = availableTools != null ? availableTools : List.<ToolDefinition>of();
        var toolNames = tools.stream()
                .filter(Objects::nonNull)
                .map(ToolDefinition::name)
                .filter(name -> name != null && !name.isBlank())
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

package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Builds the LLM extraction prompt for session skill packing and parses the response.
 *
 * <p>The extraction prompt instructs the LLM to analyze a conversation and extract
 * the successful execution path as a structured JSON object that can be rendered
 * into a SKILL.md draft.
 */
final class SessionSkillPackPrompt {

    /** Pattern to extract JSON from markdown code fences. */
    private static final Pattern CODE_FENCE = Pattern.compile(
            "```(?:json)?\\s*\\n?(\\{.*?})\\s*```",
            Pattern.DOTALL);

    private static final int MAX_STEPS = 20;

    private static final String SYSTEM_PROMPT = """
            You are a workflow extraction assistant. Your job is to analyze a conversation \
            between a user and an AI coding agent, identify the successful execution path, \
            and output a structured JSON representation of the workflow steps.

            Rules:
            - Focus on the SUCCESSFUL path only. Ignore retries, dead-ends, and failed attempts.
            - Each step should represent a distinct action that contributed to the final result.
            - Extract the tools used and their key parameters (as hints, not exact values).
            - Include success checks so a future replay can verify each step.
            - If the conversation contains no clear successful outcome, still extract the \
              best-effort path with whatever steps were completed.
            - Output ONLY valid JSON (no markdown, no commentary outside the JSON block).
            """;

    private SessionSkillPackPrompt() {}

    /**
     * Builds the user prompt containing the conversation and extraction instructions.
     *
     * @param messages the conversation messages to analyze
     * @return the formatted user prompt
     */
    static String buildExtractionPrompt(List<AgentSession.ConversationMessage> messages) {
        Objects.requireNonNull(messages, "messages");

        var sb = new StringBuilder();
        sb.append("Analyze the following conversation and extract the successful workflow.\n\n");
        sb.append("=== CONVERSATION START ===\n");

        for (var msg : messages) {
            String role = switch (msg) {
                case AgentSession.ConversationMessage.User _ -> "USER";
                case AgentSession.ConversationMessage.Assistant _ -> "ASSISTANT";
                case AgentSession.ConversationMessage.System _ -> "SYSTEM";
            };
            String content = switch (msg) {
                case AgentSession.ConversationMessage.User u -> u.content();
                case AgentSession.ConversationMessage.Assistant a -> a.content();
                case AgentSession.ConversationMessage.System s -> s.content();
            };
            sb.append("[").append(role).append("]: ").append(content != null ? content : "").append("\n\n");
        }

        sb.append("=== CONVERSATION END ===\n\n");
        sb.append("""
                Output a JSON object with exactly this structure:
                {
                  "name": "short-kebab-case-name",
                  "description": "One-line description of what this workflow does",
                  "preconditions": ["precondition 1", "precondition 2"],
                  "steps": [
                    {
                      "description": "What this step does",
                      "tool": "tool_name or null if no tool",
                      "parameters_hint": "Key parameters in plain text",
                      "success_check": "How to verify this step succeeded",
                      "failure_guidance": "What to do if this step fails"
                    }
                  ],
                  "tools": ["list", "of", "all", "tools", "used"],
                  "success_checks": ["Final success check 1", "Final success check 2"]
                }

                Output ONLY the JSON object, no other text.
                """);

        return sb.toString();
    }

    /**
     * Returns the system prompt for the extraction LLM call.
     */
    static String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    /**
     * Parses the LLM response into a {@link SkillDraft}.
     *
     * @param llmOutput the raw LLM text output
     * @param mapper    the Jackson ObjectMapper
     * @return the parsed skill draft
     * @throws IllegalArgumentException if parsing fails
     */
    static SkillDraft parseResponse(String llmOutput, ObjectMapper mapper) {
        Objects.requireNonNull(llmOutput, "llmOutput");
        Objects.requireNonNull(mapper, "mapper");

        if (llmOutput.isBlank()) {
            throw new IllegalArgumentException("Empty LLM response for skill extraction");
        }

        String jsonText = extractJson(llmOutput);

        try {
            var root = mapper.readTree(jsonText);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("Skill extraction response is not a JSON object");
            }

            String name = getTextOrDefault(root, "name", "extracted-skill");
            String description = getTextOrDefault(root, "description", "");
            List<String> preconditions = getStringList(root, "preconditions");
            List<String> tools = getStringList(root, "tools");
            List<String> successChecks = getStringList(root, "success_checks");

            var stepsNode = root.get("steps");
            if (stepsNode == null || !stepsNode.isArray() || stepsNode.isEmpty()) {
                throw new IllegalArgumentException("Extraction response has no 'steps' array");
            }

            var steps = new ArrayList<SkillStep>();
            for (var stepNode : stepsNode) {
                if (steps.size() >= MAX_STEPS) break;
                steps.add(parseStep(stepNode));
            }

            return new SkillDraft(name, description, preconditions, steps, tools, successChecks);

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse skill extraction JSON: " + e.getMessage(), e);
        }
    }

    private static SkillStep parseStep(JsonNode stepNode) {
        return new SkillStep(
                getTextOrDefault(stepNode, "description", ""),
                getTextOrDefault(stepNode, "tool", null),
                getTextOrDefault(stepNode, "parameters_hint", null),
                getTextOrDefault(stepNode, "success_check", null),
                getTextOrDefault(stepNode, "failure_guidance", null)
        );
    }

    /**
     * Extracts JSON from the LLM text, handling code fences.
     */
    static String extractJson(String text) {
        var matcher = CODE_FENCE.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }

        int braceStart = text.indexOf('{');
        int braceEnd = text.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return text.substring(braceStart, braceEnd + 1);
        }

        return text;
    }

    private static String getTextOrDefault(JsonNode node, String field, String defaultValue) {
        var child = node.get(field);
        if (child == null || child.isNull()) {
            return defaultValue;
        }
        return child.asText(defaultValue);
    }

    private static List<String> getStringList(JsonNode node, String field) {
        var child = node.get(field);
        if (child == null || !child.isArray()) {
            return List.of();
        }
        var result = new ArrayList<String>();
        for (var item : child) {
            if (!item.isNull()) {
                result.add(item.asText());
            }
        }
        return List.copyOf(result);
    }

    // -- Data records --

    record SkillDraft(
            String name,
            String description,
            List<String> preconditions,
            List<SkillStep> steps,
            List<String> tools,
            List<String> successChecks
    ) {
        SkillDraft {
            preconditions = preconditions != null ? List.copyOf(preconditions) : List.of();
            steps = steps != null ? List.copyOf(steps) : List.of();
            tools = tools != null ? List.copyOf(tools) : List.of();
            successChecks = successChecks != null ? List.copyOf(successChecks) : List.of();
        }
    }

    record SkillStep(
            String description,
            String tool,
            String parametersHint,
            String successCheck,
            String failureGuidance
    ) {}
}

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

    /**
     * Maximum characters per individual message before per-message truncation.
     * Large tool results or assistant outputs get trimmed before we even measure
     * the conversation total. 8K chars per message is generous for extraction.
     */
    static final int MAX_PER_MESSAGE_CHARS = 8_000;

    /**
     * Overhead chars reserved for the instruction wrapper (preamble + JSON schema).
     * The conversation body budget = maxConversationChars - INSTRUCTION_OVERHEAD.
     */
    private static final int INSTRUCTION_OVERHEAD = 1_500;

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
     * <p>Applies a two-level truncation strategy to fit within the LLM context window:
     * <ol>
     *   <li><b>Per-message</b>: each message body is capped at {@link #MAX_PER_MESSAGE_CHARS}
     *       (head/tail split) to prune large tool outputs.</li>
     *   <li><b>Total conversation</b>: the assembled conversation block is capped at
     *       {@code maxConversationChars - INSTRUCTION_OVERHEAD} using 40/60 head/tail split
     *       so the LLM sees both the initial goal and the final outcome.</li>
     * </ol>
     *
     * @param messages            the conversation messages to analyze
     * @param maxConversationChars total character budget for the user prompt
     *                             (conversation + instructions combined)
     * @return the formatted user prompt
     */
    static String buildExtractionPrompt(List<AgentSession.ConversationMessage> messages,
                                         int maxConversationChars) {
        Objects.requireNonNull(messages, "messages");

        // Phase 1: per-message truncation — trim large tool results
        var conversationBody = new StringBuilder();
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
            String safe = content != null ? content : "";
            if (safe.length() > MAX_PER_MESSAGE_CHARS) {
                safe = headTailTruncate(safe, MAX_PER_MESSAGE_CHARS);
            }
            conversationBody.append("[").append(role).append("]: ").append(safe).append("\n\n");
        }

        // Phase 2: total conversation truncation
        int bodyBudget = Math.max(1_000, maxConversationChars - INSTRUCTION_OVERHEAD);
        String conversation = conversationBody.toString();
        if (conversation.length() > bodyBudget) {
            conversation = headTailTruncate(conversation, bodyBudget);
        }

        // Assemble final prompt
        var sb = new StringBuilder();
        sb.append("Analyze the following conversation and extract the successful workflow.\n");
        if (conversationBody.length() > bodyBudget) {
            sb.append("NOTE: The conversation was truncated to fit the context window. ")
              .append("Focus on the beginning (user's goal) and end (final outcome).\n");
        }
        sb.append("\n=== CONVERSATION START ===\n");
        sb.append(conversation);
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
     * Truncates text using a 40% head / 60% tail split, preserving the beginning
     * (user's goal) and the end (final outcome).
     */
    static String headTailTruncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        int headChars = (int) (maxChars * 0.4);
        int tailChars = maxChars - headChars;
        return text.substring(0, headChars)
                + "\n\n... [truncated: " + text.length() + " chars total, showing first "
                + headChars + " and last " + tailChars + "] ...\n\n"
                + text.substring(text.length() - tailChars);
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

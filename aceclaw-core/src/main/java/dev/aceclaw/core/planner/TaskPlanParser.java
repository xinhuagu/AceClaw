package dev.aceclaw.core.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Parses LLM text output into a {@link TaskPlan}.
 *
 * <p>Handles common LLM output quirks:
 * <ul>
 *   <li>JSON wrapped in markdown code fences ({@code ```json ... ```})</li>
 *   <li>Missing or extra fields in step objects</li>
 *   <li>Too many or too few steps (clamped to 1-20)</li>
 * </ul>
 */
public final class TaskPlanParser {

    private static final Logger log = LoggerFactory.getLogger(TaskPlanParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_STEPS = 20;
    private static final int MIN_STEPS = 1;

    /** Pattern to extract JSON from markdown code fences. */
    private static final Pattern CODE_FENCE = Pattern.compile(
            "```(?:json)?\\s*\\n?(\\{.*?})\\s*```",
            Pattern.DOTALL);

    private TaskPlanParser() {}

    /**
     * Parses the LLM's text response into a TaskPlan.
     *
     * @param llmText      the raw text output from the LLM
     * @param originalGoal the user's original goal
     * @return a parsed TaskPlan
     * @throws IllegalArgumentException if parsing fails completely
     */
    public static TaskPlan parse(String llmText, String originalGoal) {
        if (llmText == null || llmText.isBlank()) {
            throw new IllegalArgumentException("Empty LLM response for plan generation");
        }

        String jsonText = extractJson(llmText);

        try {
            var root = MAPPER.readTree(jsonText);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("Plan response is not a JSON object");
            }

            var stepsNode = root.get("steps");
            if (stepsNode == null || !stepsNode.isArray() || stepsNode.isEmpty()) {
                throw new IllegalArgumentException("Plan response has no 'steps' array");
            }

            var steps = new ArrayList<PlannedStep>();
            for (var stepNode : stepsNode) {
                if (steps.size() >= MAX_STEPS) {
                    log.warn("Plan exceeds {} steps, truncating", MAX_STEPS);
                    break;
                }
                steps.add(parseStep(stepNode));
            }

            if (steps.size() < MIN_STEPS) {
                throw new IllegalArgumentException(
                        "Plan has fewer than " + MIN_STEPS + " steps");
            }

            return new TaskPlan(
                    UUID.randomUUID().toString(),
                    originalGoal,
                    steps,
                    new PlanStatus.Draft(),
                    Instant.now());

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse plan JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Extracts JSON from the LLM text, handling code fences.
     */
    static String extractJson(String text) {
        // Try extracting from code fence first
        var matcher = CODE_FENCE.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }

        // Try to find raw JSON object
        int braceStart = text.indexOf('{');
        int braceEnd = text.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return text.substring(braceStart, braceEnd + 1);
        }

        // Return as-is and let the JSON parser produce a better error
        return text;
    }

    private static PlannedStep parseStep(JsonNode stepNode) {
        String name = getTextOrDefault(stepNode, "name", "Unnamed step");
        String description = getTextOrDefault(stepNode, "description", "");

        List<String> requiredTools = new ArrayList<>();
        var toolsNode = stepNode.get("requiredTools");
        if (toolsNode != null && toolsNode.isArray()) {
            for (var toolNode : toolsNode) {
                requiredTools.add(toolNode.asText());
            }
        }

        String fallback = null;
        var fallbackNode = stepNode.get("fallbackApproach");
        if (fallbackNode != null && !fallbackNode.isNull()) {
            fallback = fallbackNode.asText();
        }

        return new PlannedStep(
                UUID.randomUUID().toString(),
                name,
                description,
                requiredTools,
                fallback,
                StepStatus.PENDING);
    }

    private static String getTextOrDefault(JsonNode node, String field, String defaultValue) {
        var child = node.get(field);
        if (child == null || child.isNull()) {
            return defaultValue;
        }
        return child.asText(defaultValue);
    }
}

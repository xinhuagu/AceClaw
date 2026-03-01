package dev.aceclaw.core.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *   <li>dependsOn name resolution (step names → stepIds)</li>
 *   <li>Cycle detection with fallback to sequential execution</li>
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

            // First pass: parse steps with raw dependsOn names
            var rawSteps = new ArrayList<RawStep>();
            for (var stepNode : stepsNode) {
                if (rawSteps.size() >= MAX_STEPS) {
                    log.warn("Plan exceeds {} steps, truncating", MAX_STEPS);
                    break;
                }
                rawSteps.add(parseRawStep(stepNode));
            }

            if (rawSteps.size() < MIN_STEPS) {
                throw new IllegalArgumentException(
                        "Plan has fewer than " + MIN_STEPS + " steps");
            }

            // Second pass: resolve name-based deps to stepIds
            var nameToId = new HashMap<String, String>();
            for (var raw : rawSteps) {
                var existing = nameToId.putIfAbsent(raw.name, raw.stepId);
                if (existing != null) {
                    log.warn("Duplicate step name '{}', only the first occurrence will be used for dependency resolution", raw.name);
                }
            }

            var steps = new ArrayList<PlannedStep>();
            for (var raw : rawSteps) {
                var resolvedDeps = new HashSet<String>();
                for (var depName : raw.dependsOnNames) {
                    var depId = nameToId.get(depName);
                    if (depId != null) {
                        resolvedDeps.add(depId);
                    } else {
                        log.warn("Unknown dependency '{}' in step '{}', ignoring", depName, raw.name);
                    }
                }
                steps.add(new PlannedStep(
                        raw.stepId, raw.name, raw.description,
                        raw.requiredTools, raw.fallbackApproach,
                        resolvedDeps, StepStatus.PENDING));
            }

            // Third pass: cycle detection — if cycle found, clear all deps
            if (hasCycle(steps)) {
                log.warn("Cycle detected in plan dependencies, falling back to sequential execution");
                var sequential = new ArrayList<PlannedStep>();
                for (var step : steps) {
                    sequential.add(new PlannedStep(
                            step.stepId(), step.name(), step.description(),
                            step.requiredTools(), step.fallbackApproach(),
                            Set.of(), step.status()));
                }
                steps = sequential;
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

    /**
     * Detects cycles in the step dependency graph using DFS.
     */
    static boolean hasCycle(List<PlannedStep> steps) {
        var idToStep = new HashMap<String, PlannedStep>();
        for (var step : steps) {
            idToStep.put(step.stepId(), step);
        }

        var visited = new HashSet<String>();
        var inStack = new HashSet<String>();

        for (var step : steps) {
            if (hasCycleDfs(step.stepId(), idToStep, visited, inStack)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCycleDfs(
            String nodeId,
            Map<String, PlannedStep> idToStep,
            Set<String> visited,
            Set<String> inStack) {
        if (inStack.contains(nodeId)) {
            return true;
        }
        if (visited.contains(nodeId)) {
            return false;
        }
        visited.add(nodeId);
        inStack.add(nodeId);

        var step = idToStep.get(nodeId);
        if (step != null) {
            for (var dep : step.dependsOn()) {
                if (hasCycleDfs(dep, idToStep, visited, inStack)) {
                    return true;
                }
            }
        }

        inStack.remove(nodeId);
        return false;
    }

    /**
     * Intermediate representation for a parsed step before dependency resolution.
     */
    private record RawStep(
            String stepId,
            String name,
            String description,
            List<String> requiredTools,
            String fallbackApproach,
            Set<String> dependsOnNames
    ) {
        RawStep {
            Objects.requireNonNull(stepId, "stepId");
            name = name != null ? name : "Unnamed step";
            description = description != null ? description : "";
            requiredTools = requiredTools != null ? List.copyOf(requiredTools) : List.of();
            dependsOnNames = dependsOnNames != null ? Set.copyOf(dependsOnNames) : Set.of();
        }
    }

    private static RawStep parseRawStep(JsonNode stepNode) {
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

        var dependsOnNames = new LinkedHashSet<String>();
        var depsNode = stepNode.get("dependsOn");
        if (depsNode != null && depsNode.isArray()) {
            for (var depNode : depsNode) {
                var depText = depNode.asText();
                if (depText != null && !depText.isBlank()) {
                    dependsOnNames.add(depText);
                }
            }
        }

        return new RawStep(
                UUID.randomUUID().toString(),
                name, description, requiredTools, fallback, dependsOnNames);
    }

    private static String getTextOrDefault(JsonNode node, String field, String defaultValue) {
        var child = node.get(field);
        if (child == null || child.isNull()) {
            return defaultValue;
        }
        return child.asText(defaultValue);
    }
}

package dev.aceclaw.core.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.llm.LlmClient;
import dev.aceclaw.core.llm.LlmException;
import dev.aceclaw.core.llm.LlmRequest;
import dev.aceclaw.core.llm.Message;
import dev.aceclaw.core.llm.RequestAttribution;
import dev.aceclaw.core.llm.RequestSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Performs adaptive replanning when a plan step fails after fallback exhaustion.
 *
 * <p>Given the failure context and completed work, asks the LLM to produce a revised
 * plan that may skip, merge, or restructure the remaining steps. Limited to
 * {@value #MAX_REPLAN_ATTEMPTS} replan attempts per plan execution.
 */
public final class AdaptiveReplanner {

    private static final Logger log = LoggerFactory.getLogger(AdaptiveReplanner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Maximum replan attempts per plan execution. */
    public static final int MAX_REPLAN_ATTEMPTS = 3;

    /** Maximum number of revised steps the LLM can produce in a single replan. */
    static final int MAX_REVISED_STEPS = 20;

    static final String REPLAN_SYSTEM_PROMPT = """
            You are an adaptive planning assistant. A multi-step task plan has encountered a failure.
            Your job is to analyze the failure, consider the completed work, and decide whether to:
            1. REVISE the remaining plan with modified steps that work around the failure
            2. ESCALATE if recovery is not possible (e.g., missing credentials, fundamental blocker)

            Respond with a JSON object in this exact format:

            For revision:
            {
              "action": "revise",
              "rationale": "Brief explanation of the recovery strategy",
              "steps": [
                {
                  "name": "Step name",
                  "description": "What this step should accomplish",
                  "requiredTools": ["tool1", "tool2"],
                  "fallbackApproach": "Alternative if this step fails"
                }
              ]
            }

            For escalation:
            {
              "action": "escalate",
              "reason": "Why the plan cannot be recovered"
            }

            Guidelines:
            - Preserve completed work. Do not repeat steps that already succeeded.
            - Try to achieve the original goal through an alternative path.
            - Keep revised plans concise. Merge steps where possible.
            - Only escalate when recovery is truly impossible.
            """;

    private final LlmClient llmClient;
    private final String model;

    public AdaptiveReplanner(LlmClient llmClient, String model) {
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
        this.model = Objects.requireNonNull(model, "model");
    }

    /**
     * Attempts to replan given the failure context.
     *
     * @param trigger the replan context (completed steps, failure info, remaining steps)
     * @return either a {@link ReplanResult.Revised} with new steps or {@link ReplanResult.Escalated}
     * @throws LlmException if the LLM call fails
     */
    public ReplanResult replan(ReplanTrigger trigger) throws LlmException {
        return replan(trigger, null);
    }

    /**
     * Attribution-aware variant. Records one {@link RequestSource#REPLAN} entry per LLM
     * request on the supplied builder if non-null. Pre-attempt escalations (e.g. hitting
     * the attempt cap) return without issuing an LLM call, so nothing is recorded in that
     * path — the attribution total matches actual API cost.
     */
    public ReplanResult replan(ReplanTrigger trigger, RequestAttribution.Builder attribution)
            throws LlmException {
        Objects.requireNonNull(trigger, "trigger");

        if (trigger.replanAttempt() > MAX_REPLAN_ATTEMPTS) {
            return new ReplanResult.Escalated("Max replan attempts (" + MAX_REPLAN_ATTEMPTS + ") exceeded");
        }

        String prompt = buildReplanPrompt(trigger);
        log.info("Replanning attempt {} for goal: {}", trigger.replanAttempt(),
                truncate(trigger.originalGoal(), 80));

        var request = LlmRequest.builder()
                .model(model)
                .addMessage(Message.user(prompt))
                .systemPrompt(REPLAN_SYSTEM_PROMPT)
                .maxTokens(8192)
                .thinkingBudget(4096)
                .temperature(0.3)
                .build();

        var response = llmClient.sendMessage(request);
        if (attribution != null) {
            attribution.record(RequestSource.REPLAN);
        }
        var text = response.text();

        if (text == null || text.isBlank()) {
            throw new LlmException("Empty replan response from LLM");
        }

        log.debug("Replan response ({} chars): {}", text.length(), truncate(text, 200));

        return parseReplanResponse(text, trigger);
    }

    /**
     * Builds the user prompt describing the failure context for the LLM.
     */
    String buildReplanPrompt(ReplanTrigger trigger) {
        var sb = new StringBuilder();
        sb.append("## Original Goal\n");
        sb.append(trigger.originalGoal()).append("\n\n");

        sb.append("## Completed Steps\n");
        if (trigger.completedSummaries().isEmpty()) {
            sb.append("(none)\n");
        } else {
            for (int i = 0; i < trigger.completedSummaries().size(); i++) {
                var summary = trigger.completedSummaries().get(i);
                sb.append(i + 1).append(". **").append(summary.stepName()).append("** - ");
                sb.append(summary.success() ? "SUCCESS" : "FAILED");
                if (summary.outputSummary() != null) {
                    sb.append(": ").append(summary.outputSummary());
                }
                sb.append("\n");
            }
        }
        sb.append("\n");

        sb.append("## Failed Step (index ").append(trigger.failedStepIndex() + 1).append(")\n");
        sb.append("**Name:** ").append(trigger.failedStep().name()).append("\n");
        sb.append("**Description:** ").append(trigger.failedStep().description()).append("\n");
        sb.append("**Error:** ").append(trigger.failureReason()).append("\n\n");

        sb.append("## Remaining Steps (not yet attempted)\n");
        if (trigger.remainingSteps().isEmpty()) {
            sb.append("(none - the failed step was the last step)\n");
        } else {
            for (int i = 0; i < trigger.remainingSteps().size(); i++) {
                var step = trigger.remainingSteps().get(i);
                sb.append(i + 1).append(". **").append(step.name()).append("**: ");
                sb.append(step.description()).append("\n");
            }
        }
        sb.append("\n");

        sb.append("## Replan Attempt\n");
        sb.append("This is replan attempt ").append(trigger.replanAttempt())
                .append(" of ").append(MAX_REPLAN_ATTEMPTS).append(".\n");
        sb.append("Please analyze the failure and either revise the remaining plan or escalate.\n");

        return sb.toString();
    }

    /**
     * Parses the LLM's replan response into a {@link ReplanResult}.
     */
    ReplanResult parseReplanResponse(String text, ReplanTrigger trigger) throws LlmException {
        String json = TaskPlanParser.extractJson(text);

        try {
            var root = MAPPER.readTree(json);
            if (root == null || !root.isObject()) {
                throw new LlmException("Replan response is not a JSON object");
            }

            String action = getTextOrNull(root, "action");
            if (action == null) {
                throw new LlmException("Replan response missing 'action' field");
            }

            return switch (action.toLowerCase()) {
                case "revise" -> parseRevised(root);
                case "escalate" -> parseEscalated(root);
                default -> throw new LlmException("Unknown replan action: " + action);
            };

        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("Failed to parse replan response: " + e.getMessage(), e);
        }
    }

    private ReplanResult.Revised parseRevised(JsonNode root) throws LlmException {
        String rationale = getTextOrNull(root, "rationale");
        if (rationale == null) {
            rationale = "No rationale provided";
        }

        var stepsNode = root.get("steps");
        if (stepsNode == null || !stepsNode.isArray() || stepsNode.isEmpty()) {
            throw new LlmException("Revised plan has no 'steps' array");
        }
        if (stepsNode.size() > MAX_REVISED_STEPS) {
            throw new LlmException("Revised plan exceeds max step count (" + MAX_REVISED_STEPS + ")");
        }

        var steps = new ArrayList<PlannedStep>();
        for (var stepNode : stepsNode) {
            steps.add(parseStep(stepNode));
        }

        return new ReplanResult.Revised(steps, rationale);
    }

    private ReplanResult.Escalated parseEscalated(JsonNode root) {
        String reason = getTextOrNull(root, "reason");
        if (reason == null) {
            reason = "No reason provided";
        }
        return new ReplanResult.Escalated(reason);
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

    private static String getTextOrNull(JsonNode node, String field) {
        var child = node.get(field);
        if (child == null || child.isNull()) {
            return null;
        }
        return child.asText();
    }

    private static String getTextOrDefault(JsonNode node, String field, String defaultValue) {
        var child = node.get(field);
        if (child == null || child.isNull()) {
            return defaultValue;
        }
        return child.asText(defaultValue);
    }

    private static String truncate(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }
}

package dev.aceclaw.core.planner;

import dev.aceclaw.core.llm.LlmClient;
import dev.aceclaw.core.llm.LlmException;
import dev.aceclaw.core.llm.LlmRequest;
import dev.aceclaw.core.llm.Message;
import dev.aceclaw.core.llm.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Generates task plans using an LLM.
 *
 * <p>Uses {@link LlmClient#sendMessage(LlmRequest)} (non-streaming) with a
 * structured prompt that instructs the LLM to output a JSON plan.
 * A lower temperature (0.3) is used for more deterministic, structured output.
 */
public final class LLMTaskPlanner implements TaskPlanner {

    private static final Logger log = LoggerFactory.getLogger(LLMTaskPlanner.class);

    private final LlmClient llmClient;
    private final String model;

    public LLMTaskPlanner(LlmClient llmClient, String model) {
        this.llmClient = llmClient;
        this.model = model;
    }

    @Override
    public TaskPlan plan(String goal, List<ToolDefinition> availableTools) throws LlmException {
        log.info("Generating plan for goal: {}", truncate(goal, 100));

        var request = LlmRequest.builder()
                .model(model)
                .addMessage(Message.user(PlanningPromptBuilder.buildUserPrompt(goal, availableTools)))
                .systemPrompt(PlanningPromptBuilder.SYSTEM_PROMPT)
                .maxTokens(8192)
                .thinkingBudget(4096)
                .temperature(0.3)
                .build();

        var response = llmClient.sendMessage(request);
        var text = response.text();

        if (text == null || text.isBlank()) {
            throw new LlmException("Empty plan response from LLM");
        }

        log.debug("Plan generation response ({} chars): {}",
                text.length(), truncate(text, 200));

        try {
            return TaskPlanParser.parse(text, goal);
        } catch (IllegalArgumentException e) {
            throw new LlmException("Failed to parse plan response", e);
        }
    }

    private static String truncate(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }
}

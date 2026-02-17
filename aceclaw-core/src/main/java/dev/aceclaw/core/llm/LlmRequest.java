package dev.aceclaw.core.llm;

import java.util.ArrayList;
import java.util.List;

/**
 * An immutable request to an LLM provider.
 *
 * <p>Use the {@link Builder} for ergonomic construction.
 *
 * @param model          model identifier (e.g. "claude-sonnet-4-5-20250929")
 * @param messages       conversation history
 * @param systemPrompt   optional system prompt (may be null)
 * @param tools          tool definitions available to the model (may be empty)
 * @param maxTokens      maximum tokens to generate (includes thinking tokens)
 * @param temperature    sampling temperature (use -1 to let the provider decide)
 * @param thinkingBudget tokens reserved for extended thinking (0 = disabled)
 */
public record LlmRequest(
        String model,
        List<Message> messages,
        String systemPrompt,
        List<ToolDefinition> tools,
        int maxTokens,
        double temperature,
        int thinkingBudget
) {

    public LlmRequest {
        messages = List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /**
     * Creates a new builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for {@link LlmRequest}.
     */
    public static final class Builder {

        private String model;
        private final List<Message> messages = new ArrayList<>();
        private String systemPrompt;
        private final List<ToolDefinition> tools = new ArrayList<>();
        private int maxTokens = 16384;
        private double temperature = 1.0;
        private int thinkingBudget = 10240;

        private Builder() {}

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder addMessage(Message message) {
            this.messages.add(message);
            return this;
        }

        public Builder messages(List<Message> messages) {
            this.messages.clear();
            this.messages.addAll(messages);
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder addTool(ToolDefinition tool) {
            this.tools.add(tool);
            return this;
        }

        public Builder tools(List<ToolDefinition> tools) {
            this.tools.clear();
            this.tools.addAll(tools);
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder temperature(double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder thinkingBudget(int thinkingBudget) {
            this.thinkingBudget = thinkingBudget;
            return this;
        }

        public LlmRequest build() {
            if (model == null || model.isBlank()) {
                throw new IllegalStateException("model is required");
            }
            if (messages.isEmpty()) {
                throw new IllegalStateException("at least one message is required");
            }
            return new LlmRequest(model, messages, systemPrompt, tools, maxTokens, temperature, thinkingBudget);
        }
    }
}

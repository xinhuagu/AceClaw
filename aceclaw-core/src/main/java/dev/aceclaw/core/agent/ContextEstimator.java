package dev.aceclaw.core.agent;

import dev.aceclaw.core.llm.ContentBlock;
import dev.aceclaw.core.llm.Message;
import dev.aceclaw.core.llm.ToolDefinition;

import java.util.List;

/**
 * Estimates token counts for conversation messages, system prompts, and tool definitions.
 *
 * <p>Uses the heuristic of ~4 characters per token, which is a well-established
 * approximation for English text and code. This provides a fast, no-API-call estimate
 * for deciding when context compaction is needed.
 *
 * <p>When actual token counts are available from API responses ({@code usage.inputTokens}),
 * those should be preferred over estimation for accuracy.
 */
public final class ContextEstimator {

    /**
     * Average characters per token for Claude's BPE tokenizer.
     * Empirical calibration from replay data shows ~2.2 chars/token
     * for mixed content (English text, JSON, code, tool schemas).
     */
    private static final double CHARS_PER_TOKEN = 2.1;

    /** Overhead tokens per message (role markers, structural JSON). */
    private static final int MESSAGE_OVERHEAD = 4;

    /** Overhead tokens per tool use block (id, name, JSON wrapping). */
    private static final int TOOL_USE_OVERHEAD = 20;

    /** Overhead tokens per tool result block (id, wrapping). */
    private static final int TOOL_RESULT_OVERHEAD = 10;

    /** Overhead tokens per tool definition (name, description, schema wrapping). */
    private static final int TOOL_DEF_OVERHEAD = 10;

    private ContextEstimator() {} // utility class

    /**
     * Structured breakdown of a pre-flight context budget check.
     *
     * @param systemPromptTokens  estimated tokens for the system prompt
     * @param toolDefinitionTokens estimated tokens for all tool definitions
     * @param messageTokens       estimated tokens for conversation messages
     * @param totalEstimated      total estimated tokens (sum of all above)
     * @param availableTokens     available tokens (contextWindow - maxOutputTokens)
     * @param overBudget          true if totalEstimated exceeds availableTokens
     */
    public record BudgetCheck(
            int systemPromptTokens, int toolDefinitionTokens, int messageTokens,
            int totalEstimated, int availableTokens, boolean overBudget
    ) {
        /** Returns the excess tokens if over budget, or 0 if within budget. */
        public int excessTokens() { return Math.max(0, totalEstimated - availableTokens); }
    }

    /**
     * Performs a pre-flight budget check to determine if the full LLM request
     * fits within the available context window.
     *
     * @param systemPrompt       the system prompt (may be null)
     * @param tools              tool definitions (may be empty or null)
     * @param messages           conversation messages (may be empty or null)
     * @param contextWindowTokens total context window size in tokens
     * @param maxOutputTokens    tokens reserved for output generation
     * @return a structured budget breakdown
     */
    public static BudgetCheck checkBudget(
            String systemPrompt, List<ToolDefinition> tools,
            List<Message> messages, int contextWindowTokens, int maxOutputTokens) {
        int sysTokens = estimateTokens(systemPrompt);
        var safeTools = tools != null ? tools : List.<ToolDefinition>of();
        int toolTokens = estimateToolDefinitions(safeTools);
        var safeMsgs = messages != null ? messages : List.<Message>of();
        int msgTokens = estimateMessageTokens(safeMsgs);
        int total = sysTokens + toolTokens + msgTokens;
        int available = Math.max(0, contextWindowTokens - maxOutputTokens);
        return new BudgetCheck(sysTokens, toolTokens, msgTokens, total, available, total > available);
    }

    /**
     * Estimates the token count for a plain text string.
     *
     * @param text the text to estimate (may be null)
     * @return estimated token count (0 for null or empty text)
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }

    /**
     * Estimates the total token count for a list of messages.
     *
     * @param messages the conversation messages
     * @return estimated total token count
     */
    public static int estimateMessageTokens(List<Message> messages) {
        int total = 0;
        for (var msg : messages) {
            total += estimateSingleMessageTokens(msg);
        }
        return total;
    }

    /**
     * Estimates the token count for a single message.
     *
     * @param message the message to estimate
     * @return estimated token count
     */
    public static int estimateSingleMessageTokens(Message message) {
        int tokens = MESSAGE_OVERHEAD;
        List<ContentBlock> content = switch (message) {
            case Message.UserMessage u -> u.content();
            case Message.AssistantMessage a -> a.content();
        };
        for (var block : content) {
            tokens += estimateBlockTokens(block);
        }
        return tokens;
    }

    /**
     * Estimates the token count for a single content block.
     *
     * @param block the content block
     * @return estimated token count
     */
    public static int estimateBlockTokens(ContentBlock block) {
        return switch (block) {
            case ContentBlock.Text t -> estimateTokens(t.text());
            case ContentBlock.Thinking t -> estimateTokens(t.text());
            case ContentBlock.ToolUse t ->
                    estimateTokens(t.name()) + estimateTokens(t.inputJson()) + TOOL_USE_OVERHEAD;
            case ContentBlock.ToolResult t ->
                    estimateTokens(t.content()) + TOOL_RESULT_OVERHEAD;
        };
    }

    /**
     * Estimates the token count for tool definitions.
     *
     * @param tools the tool definitions
     * @return estimated total token count
     */
    public static int estimateToolDefinitions(List<ToolDefinition> tools) {
        int total = 0;
        for (var tool : tools) {
            total += estimateTokens(tool.name()) + estimateTokens(tool.description());
            if (tool.inputSchema() != null) {
                total += estimateTokens(tool.inputSchema().toString());
            }
            total += TOOL_DEF_OVERHEAD;
        }
        return total;
    }

    /**
     * Estimates the total context size for a complete LLM request.
     *
     * @param systemPrompt the system prompt (may be null)
     * @param tools        tool definitions (may be empty)
     * @param messages     conversation messages
     * @return estimated total token count
     */
    public static int estimateFullContext(String systemPrompt, List<ToolDefinition> tools,
                                          List<Message> messages) {
        return estimateTokens(systemPrompt)
                + estimateToolDefinitions(tools)
                + estimateMessageTokens(messages);
    }
}

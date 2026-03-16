package dev.aceclaw.core.agent;

import dev.aceclaw.core.llm.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Three-phase context compaction engine for managing conversation history size.
 *
 * <p>When the conversation context approaches the model's token limit, the compactor
 * reduces it through three progressive phases:
 *
 * <ol>
 *   <li><b>Phase 0: Memory Flush</b> — Extracts key context items (file paths modified,
 *       commands executed, errors encountered) from the conversation using heuristics.
 *       These items are returned to the caller for persistence to auto-memory.</li>
 *   <li><b>Phase 1: Prune</b> — Replaces old tool result content with stubs (keeping
 *       first 200 chars for context) and removes old thinking blocks. Protects the
 *       last N turns from modification. This phase is free (no LLM call).</li>
 *   <li><b>Phase 2: Summarize</b> — Uses an LLM call to generate a structured summary
 *       of the conversation, then replaces old messages with the summary while
 *       preserving recent turns. Includes an anti-re-summarization marker.</li>
 * </ol>
 *
 * <p>Design inspired by Claude Code (boundary-based partial compaction), OpenClaw
 * (pre-compaction memory flush), OpenCode (prune-then-compact), and Codex CLI
 * (anti-re-summarization).
 */
public final class MessageCompactor {

    private static final Logger log = LoggerFactory.getLogger(MessageCompactor.class);

    /** Marker text replacing pruned tool result content. */
    private static final String PRUNED_MARKER =
            "[content pruned during context compaction - original content was too large to retain]";

    /** Maximum characters to keep from a pruned tool result as context. */
    private static final int PRUNE_STUB_CHARS = 200;

    /** Anti-re-summarization marker: prevents summarizing a summary. */
    static final String COMPACTION_MARKER = "[COMPACTED]";

    /** Continuation instruction injected after compaction. */
    static final String CONTINUATION_INSTRUCTION =
            "This session is being continued from a previous conversation that ran out of context. " +
            "The summary below covers the earlier portion of the conversation.\n\n" +
            "Please continue the conversation from where we left it off without asking the user " +
            "any further questions. Continue with the last task that you were asked to work on.";

    /** Summarization prompt sent to the LLM in Phase 2. */
    private static final String SUMMARIZATION_PROMPT = """
            You have been working on the task described above but have not yet completed it. \
            Write a continuation summary that will allow you (or another instance of yourself) \
            to resume work efficiently in a future context window where the conversation history \
            will be replaced with this summary. Your summary should be structured, concise, and \
            actionable. Include:

            1. **Task Overview**: The user's core request and success criteria, any clarifications \
            or constraints they specified
            2. **Current State**: What has been completed so far, files created/modified/analyzed \
            (with paths if relevant), key outputs or artifacts produced
            3. **Key Decisions**: Technical choices made and their rationale, decisions that should \
            not be revisited
            4. **Errors Encountered**: What went wrong and how it was resolved, approaches that \
            were tried but did not work (and why)
            5. **Next Steps**: Specific actions needed to complete the task, any blockers or open \
            questions, priority order if multiple steps remain
            6. **Context to Preserve**: User preferences or style requirements, domain-specific \
            details that are not obvious, any promises made to the user

            Be concise but complete. Err on the side of including information that prevents \
            duplicate work or repeated mistakes. Write in a way that enables immediate resumption.

            Wrap your summary in <summary></summary> tags.""";

    private final LlmClient llmClient;
    private final String model;
    private final CompactionConfig config;

    /**
     * Creates a message compactor.
     *
     * @param llmClient the LLM client for Phase 2 summarization calls
     * @param model     the model to use for summarization
     * @param config    compaction configuration
     */
    public MessageCompactor(LlmClient llmClient, String model, CompactionConfig config) {
        this.llmClient = llmClient;
        this.model = model;
        this.config = config;
    }

    /**
     * Returns the compaction configuration.
     */
    public CompactionConfig config() {
        return config;
    }

    /**
     * Checks if compaction is needed based on actual token count from the API.
     *
     * @param inputTokens the actual input token count from the last API response
     * @return true if compaction should be triggered
     */
    public boolean needsCompaction(int inputTokens) {
        return inputTokens > config.triggerTokens();
    }

    /**
     * Checks if compaction is needed based on estimated token count.
     *
     * @param messages     conversation messages
     * @param systemPrompt the system prompt
     * @param tools        tool definitions
     * @return true if compaction should be triggered
     */
    public boolean needsCompactionEstimate(List<Message> messages, String systemPrompt,
                                           List<ToolDefinition> tools) {
        int estimate = ContextEstimator.estimateFullContext(systemPrompt, tools, messages);
        return estimate > config.triggerTokens();
    }

    /**
     * Applies the lightweight pruning phase as a request-time budget relief step.
     *
     * <p>Unlike {@link #compact(List, String)}, this never runs memory extraction or
     * summarization and is intended only for the transient request assembled for the
     * next model call. Callers should not persist the returned pruned messages back
     * into long-lived session history.
     */
    public RequestPruneResult pruneForRequest(List<Message> messages, String systemPrompt,
                                              List<ToolDefinition> tools) {
        var safeMessages = messages != null ? List.copyOf(messages) : List.<Message>of();
        var safeTools = tools != null ? List.copyOf(tools) : List.<ToolDefinition>of();
        int originalEstimate = ContextEstimator.estimateFullContext(systemPrompt, safeTools, safeMessages);
        if (originalEstimate <= config.triggerTokens()) {
            return new RequestPruneResult(safeMessages, originalEstimate, originalEstimate, false);
        }

        var prunedMessages = pruneMessages(safeMessages, config.protectedTurns());
        int prunedEstimate = ContextEstimator.estimateFullContext(systemPrompt, safeTools, prunedMessages);
        boolean applied = prunedEstimate < originalEstimate;
        return new RequestPruneResult(
                applied ? prunedMessages : safeMessages,
                originalEstimate,
                applied ? prunedEstimate : originalEstimate,
                applied);
    }

    /**
     * Runs the full 3-phase compaction pipeline.
     *
     * <p>Phase 0 (memory flush) extracts context items for the caller to persist.
     * Phase 1 (prune) removes old tool results and thinking blocks.
     * Phase 2 (summarize) generates an LLM summary if pruning was insufficient.
     *
     * @param messages     the conversation messages to compact
     * @param systemPrompt the system prompt (for token estimation and summarization context)
     * @return the compaction result with new messages and extracted context
     * @throws LlmException if the Phase 2 summarization LLM call fails
     */
    public CompactionResult compact(List<Message> messages, String systemPrompt) throws LlmException {
        int originalEstimate = ContextEstimator.estimateFullContext(systemPrompt, List.of(), messages);

        log.info("Starting context compaction: estimatedTokens={}, threshold={}, messages={}",
                originalEstimate, config.triggerTokens(), messages.size());

        // Check for anti-re-summarization: if first message is already a compacted summary,
        // skip Phase 0 extraction (summary is already curated)
        boolean isAlreadyCompacted = !messages.isEmpty() && isCompactionMarker(messages);

        // Phase 0: Memory flush — extract key context items
        List<String> extractedContext;
        if (isAlreadyCompacted) {
            extractedContext = List.of();
            log.info("Phase 0 (memory flush): skipped (already compacted conversation)");
        } else {
            extractedContext = extractContextItems(messages);
            log.info("Phase 0 (memory flush): extracted {} context items", extractedContext.size());
        }

        // Phase 1: Prune old tool results and thinking blocks
        var pruned = pruneMessages(messages, config.protectedTurns());
        int prunedEstimate = ContextEstimator.estimateFullContext(systemPrompt, List.of(), pruned);

        log.info("Phase 1 (prune): {} -> {} estimated tokens ({} -> {} messages)",
                originalEstimate, prunedEstimate, messages.size(), pruned.size());

        // Check if pruning was sufficient
        if (prunedEstimate <= config.pruneTargetTokens()) {
            return new CompactionResult(
                    pruned, originalEstimate, prunedEstimate,
                    CompactionResult.Phase.PRUNED, extractedContext);
        }

        // Phase 2: LLM summarization
        var summarized = summarizeMessages(pruned, systemPrompt);
        int summarizedEstimate = ContextEstimator.estimateFullContext(
                systemPrompt, List.of(), summarized);

        double reduction = 100.0 * (1.0 - (double) summarizedEstimate / originalEstimate);
        log.info("Phase 2 (summarize): {} -> {} estimated tokens ({} -> {} messages), reduction={}%",
                prunedEstimate, summarizedEstimate, pruned.size(), summarized.size(),
                String.format("%.1f", reduction));

        return new CompactionResult(
                summarized, originalEstimate, summarizedEstimate,
                CompactionResult.Phase.SUMMARIZED, extractedContext);
    }

    // -- Phase 0: Memory Flush -----------------------------------------------

    /**
     * Extracts key context items from the conversation for persistence to auto-memory.
     * Uses heuristics (not LLM calls) to identify important information.
     *
     * @param messages the conversation messages
     * @return extracted context items as human-readable strings
     */
    static List<String> extractContextItems(List<Message> messages) {
        var items = new ArrayList<String>();

        for (var msg : messages) {
            List<ContentBlock> content = switch (msg) {
                case Message.UserMessage u -> u.content();
                case Message.AssistantMessage a -> a.content();
            };

            for (var block : content) {
                switch (block) {
                    case ContentBlock.ToolUse tu -> extractFromToolUse(tu, items);
                    case ContentBlock.ToolResult tr -> {
                        if (tr.isError()) {
                            String errorMsg = tr.content();
                            if (errorMsg != null && errorMsg.length() > 200) {
                                errorMsg = errorMsg.substring(0, 200) + "...";
                            }
                            items.add("Error encountered: " + errorMsg);
                        }
                    }
                    default -> { /* Text and Thinking blocks don't need extraction */ }
                }
            }
        }

        return items;
    }

    private static final Pattern FILE_PATH_PATTERN = Pattern.compile(
            "\"file_path\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern COMMAND_PATTERN = Pattern.compile(
            "\"command\"\\s*:\\s*\"([^\"]+)\"");

    private static void extractFromToolUse(ContentBlock.ToolUse toolUse, List<String> items) {
        String input = toolUse.inputJson();
        if (input == null) return;

        switch (toolUse.name()) {
            case "write_file", "edit_file" -> {
                Matcher m = FILE_PATH_PATTERN.matcher(input);
                if (m.find()) {
                    items.add("Modified file: " + m.group(1));
                }
            }
            case "bash" -> {
                Matcher m = COMMAND_PATTERN.matcher(input);
                if (m.find()) {
                    String cmd = m.group(1);
                    if (cmd.length() > 10 && !cmd.startsWith("cd ") && !cmd.startsWith("ls")) {
                        items.add("Executed: " + (cmd.length() > 100
                                ? cmd.substring(0, 100) + "..." : cmd));
                    }
                }
            }
            default -> { /* read_file, glob, grep don't modify state */ }
        }
    }

    // -- Phase 1: Prune ------------------------------------------------------

    /**
     * Prunes old tool results and thinking blocks, keeping recent turns intact.
     *
     * @param messages       the conversation messages
     * @param protectedTurns number of recent turns to protect
     * @return a new list with pruned content
     */
    static List<Message> pruneMessages(List<Message> messages, int protectedTurns) {
        if (messages.isEmpty()) return messages;

        int protectedBoundary = calculateProtectedBoundary(messages, protectedTurns);

        var result = new ArrayList<Message>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            if (i >= protectedBoundary) {
                result.add(messages.get(i));
            } else {
                result.add(pruneMessage(messages.get(i)));
            }
        }

        return result;
    }

    /**
     * Calculates the message index at which protected turns begin.
     * A "turn" starts with a UserMessage.
     */
    static int calculateProtectedBoundary(List<Message> messages, int protectedTurns) {
        if (protectedTurns <= 0) return messages.size();

        int turnCount = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof Message.UserMessage) {
                turnCount++;
                if (turnCount >= protectedTurns) {
                    return i;
                }
            }
        }

        return 0; // protect everything if fewer turns than requested
    }

    /**
     * Prunes a single message: replaces tool result content with stubs,
     * removes thinking blocks entirely.
     */
    private static Message pruneMessage(Message message) {
        List<ContentBlock> content = switch (message) {
            case Message.UserMessage u -> u.content();
            case Message.AssistantMessage a -> a.content();
        };

        boolean needsPruning = content.stream().anyMatch(
                b -> b instanceof ContentBlock.ToolResult || b instanceof ContentBlock.Thinking);

        if (!needsPruning) return message;

        var prunedContent = new ArrayList<ContentBlock>();
        for (var block : content) {
            switch (block) {
                case ContentBlock.ToolResult tr -> {
                    String prunedOutput = pruneToolResultContent(tr.content());
                    prunedContent.add(new ContentBlock.ToolResult(
                            tr.toolUseId(), prunedOutput, tr.isError()));
                }
                case ContentBlock.Thinking _ -> {
                    // Drop old thinking blocks entirely — they are ephemeral reasoning
                }
                default -> prunedContent.add(block);
            }
        }

        // Don't create empty messages (would violate API requirements)
        if (prunedContent.isEmpty()) {
            return message;
        }

        return switch (message) {
            case Message.UserMessage _ -> new Message.UserMessage(prunedContent);
            case Message.AssistantMessage _ -> new Message.AssistantMessage(prunedContent);
        };
    }

    /**
     * Replaces tool result content with a stub if it exceeds the prune threshold.
     * Keeps the first 200 characters for context.
     */
    private static String pruneToolResultContent(String content) {
        if (content == null || content.length() <= PRUNE_STUB_CHARS * 2) {
            return content; // small enough to keep
        }

        String stub = content.substring(0, PRUNE_STUB_CHARS);
        return stub + "\n\n" + PRUNED_MARKER;
    }

    /**
     * Outcome of request-time pruning before formal compaction.
     *
     * @param messages               messages to use for the immediate request
     * @param originalTokenEstimate  estimated full-context size before pruning
     * @param prunedTokenEstimate    estimated full-context size after pruning
     * @param applied                whether pruning actually reduced the request
     */
    public record RequestPruneResult(
            List<Message> messages,
            int originalTokenEstimate,
            int prunedTokenEstimate,
            boolean applied
    ) {
        public RequestPruneResult {
            messages = messages != null ? List.copyOf(messages) : List.of();
        }
    }

    // -- Phase 2: Summarize --------------------------------------------------

    /**
     * Uses the LLM to generate a structured summary, then replaces old messages
     * with the summary while preserving protected recent turns.
     */
    private List<Message> summarizeMessages(List<Message> messages, String systemPrompt)
            throws LlmException {
        // Build a summarization request
        var summaryMessages = new ArrayList<>(messages);
        summaryMessages.add(Message.user(SUMMARIZATION_PROMPT));

        var builder = LlmRequest.builder()
                .model(model)
                .messages(summaryMessages)
                .maxTokens(4096)       // summary should be concise
                .thinkingBudget(0);    // no thinking needed for summarization

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            builder.systemPrompt(systemPrompt);
        }

        var response = llmClient.sendMessage(builder.build());

        // Extract summary text from the response
        String summaryText = extractSummaryText(response);
        log.debug("Generated summary: {} chars", summaryText.length());

        // Build the compacted conversation:
        // 1. Continuation instruction as user message
        // 2. Summary as assistant message (with compaction marker)
        // 3. Protected recent turns preserved verbatim
        var compactedMessages = new ArrayList<Message>();
        compactedMessages.add(Message.user(CONTINUATION_INSTRUCTION));
        compactedMessages.add(Message.assistant(COMPACTION_MARKER + "\n\n" + summaryText));

        // Append protected recent turns
        int protectedBoundary = calculateProtectedBoundary(messages, config.protectedTurns());
        for (int i = protectedBoundary; i < messages.size(); i++) {
            compactedMessages.add(messages.get(i));
        }

        return compactedMessages;
    }

    /**
     * Extracts the summary text from the LLM response, looking for summary tags.
     */
    private static String extractSummaryText(LlmResponse response) {
        var sb = new StringBuilder();
        for (var block : response.content()) {
            if (block instanceof ContentBlock.Text text) {
                sb.append(text.text());
            }
        }

        String fullText = sb.toString();

        // Try to extract from <summary> tags
        int start = fullText.indexOf("<summary>");
        int end = fullText.indexOf("</summary>");
        if (start >= 0 && end > start) {
            return fullText.substring(start + "<summary>".length(), end).trim();
        }

        // Fallback: use the entire response
        return fullText.trim();
    }

    /**
     * Checks if the conversation starts with a compacted summary
     * (anti-re-summarization check).
     */
    private static boolean isCompactionMarker(List<Message> messages) {
        if (messages.size() < 2) return false;

        // Look for the pattern: user continuation instruction + assistant compaction marker
        if (messages.get(0) instanceof Message.UserMessage u) {
            String text = u.content().stream()
                    .filter(b -> b instanceof ContentBlock.Text)
                    .map(b -> ((ContentBlock.Text) b).text())
                    .reduce("", (a, b) -> a + b);
            if (text.contains("continued from a previous conversation")) {
                if (messages.get(1) instanceof Message.AssistantMessage a) {
                    String assistantText = a.content().stream()
                            .filter(b -> b instanceof ContentBlock.Text)
                            .map(b -> ((ContentBlock.Text) b).text())
                            .reduce("", (ab, bb) -> ab + bb);
                    return assistantText.startsWith(COMPACTION_MARKER);
                }
            }
        }

        return false;
    }
}

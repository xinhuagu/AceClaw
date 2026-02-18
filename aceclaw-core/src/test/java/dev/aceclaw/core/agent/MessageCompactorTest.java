package dev.aceclaw.core.agent;

import dev.aceclaw.core.llm.ContentBlock;
import dev.aceclaw.core.llm.Message;
import dev.aceclaw.core.llm.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link MessageCompactor}, {@link CompactionConfig},
 * {@link CompactionResult}, and {@link ContextEstimator}.
 */
class MessageCompactorTest {

    // =========================================================================
    // CompactionConfig tests
    // =========================================================================

    @Test
    void defaultConfigHasExpectedValues() {
        var config = CompactionConfig.DEFAULT;

        assertThat(config.contextWindowTokens()).isEqualTo(200_000);
        assertThat(config.maxOutputTokens()).isEqualTo(16384);
        assertThat(config.compactionThreshold()).isEqualTo(0.85);
        assertThat(config.pruneTarget()).isEqualTo(0.60);
        assertThat(config.protectedTurns()).isEqualTo(5);
    }

    @Test
    void effectiveWindowTokensCalculation() {
        var config = CompactionConfig.DEFAULT;

        assertThat(config.effectiveWindowTokens()).isEqualTo(200_000 - 16384);
    }

    @Test
    void triggerTokensCalculation() {
        var config = CompactionConfig.DEFAULT;
        int effective = 200_000 - 16384; // 183616

        assertThat(config.triggerTokens()).isEqualTo((int) (effective * 0.85));
    }

    @Test
    void pruneTargetTokensCalculation() {
        var config = CompactionConfig.DEFAULT;
        int effective = 200_000 - 16384; // 183616

        assertThat(config.pruneTargetTokens()).isEqualTo((int) (effective * 0.60));
    }

    @Test
    void invalidContextWindowTokensThrowsForZero() {
        assertThatThrownBy(() -> new CompactionConfig(0, 100, 0.85, 0.60, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contextWindowTokens must be positive");
    }

    @Test
    void invalidContextWindowTokensThrowsForNegative() {
        assertThatThrownBy(() -> new CompactionConfig(-1, 100, 0.85, 0.60, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contextWindowTokens must be positive");
    }

    @Test
    void invalidCompactionThresholdThrowsForZero() {
        assertThatThrownBy(() -> new CompactionConfig(100_000, 1000, 0.0, 0.60, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("compactionThreshold must be in (0, 1]");
    }

    @Test
    void invalidCompactionThresholdThrowsForNegative() {
        assertThatThrownBy(() -> new CompactionConfig(100_000, 1000, -0.1, 0.60, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("compactionThreshold must be in (0, 1]");
    }

    @Test
    void invalidCompactionThresholdThrowsForAboveOne() {
        assertThatThrownBy(() -> new CompactionConfig(100_000, 1000, 1.1, 0.60, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("compactionThreshold must be in (0, 1]");
    }

    @Test
    void pruneTargetMustBeLessThanThreshold() {
        // pruneTarget == compactionThreshold should throw
        assertThatThrownBy(() -> new CompactionConfig(100_000, 1000, 0.85, 0.85, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pruneTarget must be in (0, compactionThreshold)");
    }

    @Test
    void pruneTargetAboveThresholdThrows() {
        assertThatThrownBy(() -> new CompactionConfig(100_000, 1000, 0.85, 0.90, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pruneTarget must be in (0, compactionThreshold)");
    }

    @Test
    void invalidMaxOutputTokensThrows() {
        // maxOutputTokens >= contextWindowTokens
        assertThatThrownBy(() -> new CompactionConfig(100, 100, 0.85, 0.60, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxOutputTokens must be >= 0 and < contextWindowTokens");
    }

    @Test
    void negativeProtectedTurnsThrows() {
        assertThatThrownBy(() -> new CompactionConfig(100_000, 1000, 0.85, 0.60, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("protectedTurns must be >= 0");
    }

    @Test
    void customConfigCalculatesCorrectly() {
        var config = new CompactionConfig(100_000, 10_000, 0.90, 0.50, 3);

        assertThat(config.effectiveWindowTokens()).isEqualTo(90_000);
        assertThat(config.triggerTokens()).isEqualTo((int) (90_000 * 0.90));
        assertThat(config.pruneTargetTokens()).isEqualTo((int) (90_000 * 0.50));
    }

    // =========================================================================
    // ContextEstimator tests
    // =========================================================================

    @Test
    void estimateTokensForText() {
        // "hello" = 5 chars -> ceil(5/4.0) = 2
        assertThat(ContextEstimator.estimateTokens("hello")).isEqualTo(2);
    }

    @Test
    void estimateTokensForNull() {
        assertThat(ContextEstimator.estimateTokens(null)).isEqualTo(0);
    }

    @Test
    void estimateTokensForEmpty() {
        assertThat(ContextEstimator.estimateTokens("")).isEqualTo(0);
    }

    @Test
    void estimateTokensForLongerText() {
        // 12 chars -> ceil(12/4.0) = 3
        assertThat(ContextEstimator.estimateTokens("hello world!")).isEqualTo(3);
    }

    @Test
    void estimateTokensRoundsUp() {
        // 5 chars -> ceil(5/4.0) = ceil(1.25) = 2
        assertThat(ContextEstimator.estimateTokens("abcde")).isEqualTo(2);
        // 4 chars -> ceil(4/4.0) = 1
        assertThat(ContextEstimator.estimateTokens("abcd")).isEqualTo(1);
        // 1 char -> ceil(1/4.0) = 1
        assertThat(ContextEstimator.estimateTokens("a")).isEqualTo(1);
    }

    @Test
    void estimateSingleMessageTokens() {
        // Message.user("hello") -> MESSAGE_OVERHEAD(4) + estimateTokens("hello")(2) = 6
        Message msg = Message.user("hello");
        assertThat(ContextEstimator.estimateSingleMessageTokens(msg)).isEqualTo(6);
    }

    @Test
    void estimateToolUseBlockTokens() {
        var toolUse = new ContentBlock.ToolUse("id1", "write_file",
                "{\"file_path\":\"/src/Foo.java\",\"content\":\"class Foo {}\"}");
        int expected = ContextEstimator.estimateTokens("write_file")
                + ContextEstimator.estimateTokens(toolUse.inputJson())
                + 20; // TOOL_USE_OVERHEAD

        assertThat(ContextEstimator.estimateBlockTokens(toolUse)).isEqualTo(expected);
    }

    @Test
    void estimateToolResultBlockTokens() {
        var toolResult = new ContentBlock.ToolResult("id1", "File written successfully", false);
        int expected = ContextEstimator.estimateTokens("File written successfully") + 10; // TOOL_RESULT_OVERHEAD

        assertThat(ContextEstimator.estimateBlockTokens(toolResult)).isEqualTo(expected);
    }

    @Test
    void estimateThinkingBlockTokens() {
        var thinking = new ContentBlock.Thinking("Let me reason about this...");
        int expected = ContextEstimator.estimateTokens("Let me reason about this...");

        assertThat(ContextEstimator.estimateBlockTokens(thinking)).isEqualTo(expected);
    }

    @Test
    void estimateMessageTokensForList() {
        List<Message> messages = List.of(
                Message.user("hello"),
                Message.assistant("world")
        );
        int expected = ContextEstimator.estimateSingleMessageTokens(messages.get(0))
                + ContextEstimator.estimateSingleMessageTokens(messages.get(1));

        assertThat(ContextEstimator.estimateMessageTokens(messages)).isEqualTo(expected);
    }

    @Test
    void estimateToolDefinitions() {
        var tools = List.of(
                new ToolDefinition("read_file", "Reads a file from the filesystem", null)
        );
        int expected = ContextEstimator.estimateTokens("read_file")
                + ContextEstimator.estimateTokens("Reads a file from the filesystem")
                + 10; // TOOL_DEF_OVERHEAD

        assertThat(ContextEstimator.estimateToolDefinitions(tools)).isEqualTo(expected);
    }

    @Test
    void estimateFullContextCombinesAll() {
        String systemPrompt = "You are a helpful assistant.";
        var tools = List.of(
                new ToolDefinition("bash", "Execute a bash command", null)
        );
        List<Message> messages = List.of(
                Message.user("Run the tests"),
                Message.assistant("I will run the tests now.")
        );

        int expected = ContextEstimator.estimateTokens(systemPrompt)
                + ContextEstimator.estimateToolDefinitions(tools)
                + ContextEstimator.estimateMessageTokens(messages);

        assertThat(ContextEstimator.estimateFullContext(systemPrompt, tools, messages))
                .isEqualTo(expected);
    }

    @Test
    void estimateFullContextWithNullSystemPrompt() {
        List<Message> messages = List.of(Message.user("hello"));

        int expected = ContextEstimator.estimateTokens(null)
                + ContextEstimator.estimateToolDefinitions(List.of())
                + ContextEstimator.estimateMessageTokens(messages);

        assertThat(ContextEstimator.estimateFullContext(null, List.of(), messages))
                .isEqualTo(expected);
    }

    // =========================================================================
    // MessageCompactor Phase 0 tests (extractContextItems)
    // =========================================================================

    @Test
    void extractContextItemsFindsWriteFile() {
        var messages = List.<Message>of(
                new Message.AssistantMessage(List.of(
                        new ContentBlock.ToolUse("tu1", "write_file",
                                "{\"file_path\":\"/src/Foo.java\",\"content\":\"class Foo {}\"}")
                )),
                Message.toolResult("tu1", "File written", false)
        );

        List<String> items = MessageCompactor.extractContextItems(messages);

        assertThat(items).containsExactly("Modified file: /src/Foo.java");
    }

    @Test
    void extractContextItemsFindsEditFile() {
        var messages = List.<Message>of(
                new Message.AssistantMessage(List.of(
                        new ContentBlock.ToolUse("tu1", "edit_file",
                                "{\"file_path\":\"/src/Bar.java\",\"old_string\":\"foo\",\"new_string\":\"bar\"}")
                )),
                Message.toolResult("tu1", "File edited", false)
        );

        List<String> items = MessageCompactor.extractContextItems(messages);

        assertThat(items).containsExactly("Modified file: /src/Bar.java");
    }

    @Test
    void extractContextItemsFindsBashCommands() {
        var messages = List.<Message>of(
                new Message.AssistantMessage(List.of(
                        new ContentBlock.ToolUse("tu1", "bash",
                                "{\"command\":\"./gradlew build\"}")
                )),
                Message.toolResult("tu1", "BUILD SUCCESSFUL", false)
        );

        List<String> items = MessageCompactor.extractContextItems(messages);

        assertThat(items).containsExactly("Executed: ./gradlew build");
    }

    @Test
    void extractContextItemsFindsErrors() {
        var messages = List.<Message>of(
                Message.toolResult("tu1", "FileNotFoundException: /missing.txt", true)
        );

        List<String> items = MessageCompactor.extractContextItems(messages);

        assertThat(items).containsExactly("Error encountered: FileNotFoundException: /missing.txt");
    }

    @Test
    void extractContextItemsTruncatesLongErrors() {
        String longError = "A".repeat(300);
        var messages = List.<Message>of(
                Message.toolResult("tu1", longError, true)
        );

        List<String> items = MessageCompactor.extractContextItems(messages);

        assertThat(items).hasSize(1);
        assertThat(items.get(0)).startsWith("Error encountered: ");
        // Should be truncated to 200 chars + "..."
        assertThat(items.get(0)).endsWith("...");
        assertThat(items.get(0).length()).isLessThan(300);
    }

    @Test
    void extractContextItemsSkipsReadOnly() {
        var messages = List.<Message>of(
                new Message.AssistantMessage(List.of(
                        new ContentBlock.ToolUse("tu1", "read_file",
                                "{\"file_path\":\"/src/Foo.java\"}")
                )),
                Message.toolResult("tu1", "class Foo {}", false)
        );

        List<String> items = MessageCompactor.extractContextItems(messages);

        assertThat(items).isEmpty();
    }

    @Test
    void extractContextItemsIgnoresShortBashCommands() {
        var messages = List.<Message>of(
                new Message.AssistantMessage(List.of(
                        new ContentBlock.ToolUse("tu1", "bash",
                                "{\"command\":\"cd foo\"}")
                )),
                Message.toolResult("tu1", "ok", false)
        );

        List<String> items = MessageCompactor.extractContextItems(messages);

        assertThat(items).isEmpty();
    }

    @Test
    void extractContextItemsIgnoresLsCommand() {
        var messages = List.<Message>of(
                new Message.AssistantMessage(List.of(
                        new ContentBlock.ToolUse("tu1", "bash",
                                "{\"command\":\"ls -la /src\"}")
                )),
                Message.toolResult("tu1", "file1.java\nfile2.java", false)
        );

        List<String> items = MessageCompactor.extractContextItems(messages);

        assertThat(items).isEmpty();
    }

    @Test
    void extractContextItemsIgnoresVeryShortCommands() {
        // Commands with length <= 10 should be ignored
        var messages = List.<Message>of(
                new Message.AssistantMessage(List.of(
                        new ContentBlock.ToolUse("tu1", "bash",
                                "{\"command\":\"pwd\"}")
                )),
                Message.toolResult("tu1", "/home/user", false)
        );

        List<String> items = MessageCompactor.extractContextItems(messages);

        assertThat(items).isEmpty();
    }

    @Test
    void extractContextItemsFromMultipleMessages() {
        var messages = List.<Message>of(
                new Message.AssistantMessage(List.of(
                        new ContentBlock.ToolUse("tu1", "write_file",
                                "{\"file_path\":\"/src/A.java\",\"content\":\"class A {}\"}")
                )),
                Message.toolResult("tu1", "Written", false),
                new Message.AssistantMessage(List.of(
                        new ContentBlock.ToolUse("tu2", "bash",
                                "{\"command\":\"./gradlew test\"}")
                )),
                Message.toolResult("tu2", "FAILED", true)
        );

        List<String> items = MessageCompactor.extractContextItems(messages);

        assertThat(items).containsExactly(
                "Modified file: /src/A.java",
                "Executed: ./gradlew test",
                "Error encountered: FAILED"
        );
    }

    @Test
    void extractContextItemsIgnoresNullToolInput() {
        var messages = List.<Message>of(
                new Message.AssistantMessage(List.of(
                        new ContentBlock.ToolUse("tu1", "write_file", null)
                ))
        );

        List<String> items = MessageCompactor.extractContextItems(messages);

        assertThat(items).isEmpty();
    }

    @Test
    void extractContextItemsTruncatesLongBashCommands() {
        String longCommand = "A".repeat(150);
        var messages = List.<Message>of(
                new Message.AssistantMessage(List.of(
                        new ContentBlock.ToolUse("tu1", "bash",
                                "{\"command\":\"" + longCommand + "\"}")
                )),
                Message.toolResult("tu1", "ok", false)
        );

        List<String> items = MessageCompactor.extractContextItems(messages);

        assertThat(items).hasSize(1);
        assertThat(items.get(0)).startsWith("Executed: ");
        assertThat(items.get(0)).endsWith("...");
    }

    // =========================================================================
    // MessageCompactor Phase 1 tests (pruneMessages)
    // =========================================================================

    @Test
    void pruneMessagesReplacesOldToolResults() {
        // Create a large tool result (> 400 chars = PRUNE_STUB_CHARS * 2)
        String largeContent = "X".repeat(500);
        var messages = List.<Message>of(
                Message.user("Do something"),
                Message.toolResult("tu1", largeContent, false),
                Message.user("And then this")  // this is the protected turn
        );

        List<Message> pruned = MessageCompactor.pruneMessages(messages, 1);

        // First two messages should be pruned (before protected boundary)
        // The tool result (index 1) should have stubbed content
        Message prunedToolResult = pruned.get(1);
        assertThat(prunedToolResult).isInstanceOf(Message.UserMessage.class);
        var userMsg = (Message.UserMessage) prunedToolResult;
        assertThat(userMsg.content()).hasSize(1);
        var tr = (ContentBlock.ToolResult) userMsg.content().get(0);
        // Should be first 200 chars + pruned marker
        assertThat(tr.content()).startsWith("X".repeat(200));
        assertThat(tr.content()).contains("[content pruned during context compaction");
        assertThat(tr.content().length()).isLessThan(largeContent.length());
    }

    @Test
    void pruneMessagesRemovesThinkingBlocks() {
        var thinkingContent = List.<ContentBlock>of(
                new ContentBlock.Thinking("Let me think about this..."),
                new ContentBlock.Text("Here is my response.")
        );
        var messages = List.<Message>of(
                Message.user("Help me"),
                new Message.AssistantMessage(thinkingContent),
                Message.user("Thanks")  // protected turn
        );

        List<Message> pruned = MessageCompactor.pruneMessages(messages, 1);

        // The assistant message at index 1 should have thinking removed
        Message prunedAssistant = pruned.get(1);
        assertThat(prunedAssistant).isInstanceOf(Message.AssistantMessage.class);
        var assistantMsg = (Message.AssistantMessage) prunedAssistant;
        // Only the Text block should remain, Thinking should be removed
        assertThat(assistantMsg.content()).hasSize(1);
        assertThat(assistantMsg.content().get(0)).isInstanceOf(ContentBlock.Text.class);
        assertThat(((ContentBlock.Text) assistantMsg.content().get(0)).text())
                .isEqualTo("Here is my response.");
    }

    @Test
    void pruneMessagesProtectsRecentTurns() {
        String largeContent = "X".repeat(500);
        var messages = List.<Message>of(
                Message.user("Old question"),
                Message.assistant("Old answer"),
                Message.user("Recent question"),          // protected turn 2
                Message.toolResult("tu1", largeContent, false),
                Message.user("Latest question"),           // protected turn 1
                Message.assistant("Latest answer")
        );

        List<Message> pruned = MessageCompactor.pruneMessages(messages, 2);

        // Messages at index 2 onward should be protected (unchanged)
        // protected boundary is at index 2 (2nd UserMessage from the end)
        // Index 0 and 1 should be pruned (but they have no tool results or thinking)
        // Index 3 has a large tool result in a protected turn, so it should be kept as-is
        Message protectedToolResult = pruned.get(3);
        assertThat(protectedToolResult).isInstanceOf(Message.UserMessage.class);
        var userMsg = (Message.UserMessage) protectedToolResult;
        var tr = (ContentBlock.ToolResult) userMsg.content().get(0);
        assertThat(tr.content()).isEqualTo(largeContent); // unchanged
    }

    @Test
    void pruneMessagesKeepsSmallToolResults() {
        // Small tool result (<= 400 chars = PRUNE_STUB_CHARS * 2) should be kept
        String smallContent = "File written successfully";
        var messages = List.<Message>of(
                Message.toolResult("tu1", smallContent, false),
                Message.user("Next question")  // protected turn
        );

        List<Message> pruned = MessageCompactor.pruneMessages(messages, 1);

        // The tool result at index 0 is before the protected boundary,
        // but the content is small so it should be kept as-is
        Message prunedMsg = pruned.get(0);
        assertThat(prunedMsg).isInstanceOf(Message.UserMessage.class);
        var userMsg = (Message.UserMessage) prunedMsg;
        var tr = (ContentBlock.ToolResult) userMsg.content().get(0);
        assertThat(tr.content()).isEqualTo(smallContent);
    }

    @Test
    void pruneEmptyMessagesReturnsEmpty() {
        List<Message> pruned = MessageCompactor.pruneMessages(List.of(), 5);

        assertThat(pruned).isEmpty();
    }

    @Test
    void pruneMessagesKeepsTextBlocksIntact() {
        var messages = List.<Message>of(
                Message.user("Question one"),
                Message.assistant("Answer one"),
                Message.user("Question two")  // protected turn
        );

        List<Message> pruned = MessageCompactor.pruneMessages(messages, 1);

        // Text-only messages should pass through unchanged
        assertThat(pruned).hasSize(3);
        assertThat(pruned.get(0)).isInstanceOf(Message.UserMessage.class);
        assertThat(pruned.get(1)).isInstanceOf(Message.AssistantMessage.class);
    }

    // =========================================================================
    // MessageCompactor boundary calculation tests
    // =========================================================================

    @Test
    void calculateProtectedBoundaryWithEnoughTurns() {
        var messages = List.<Message>of(
                Message.user("Turn 1"),                    // index 0 - UserMessage
                Message.assistant("Response 1"),           // index 1
                Message.user("Turn 2"),                    // index 2 - UserMessage
                Message.assistant("Response 2"),           // index 3
                Message.user("Turn 3"),                    // index 4 - UserMessage
                Message.assistant("Response 3"),           // index 5
                Message.user("Turn 4"),                    // index 6 - UserMessage
                Message.assistant("Response 4"),           // index 7
                Message.user("Turn 5"),                    // index 8 - UserMessage
                Message.assistant("Response 5")            // index 9
        );

        // protectedTurns=2 -> walk backward, find 2nd UserMessage from end
        // Last UserMessage at index 8 -> count 1
        // Next UserMessage at index 6 -> count 2 -> return index 6
        int boundary = MessageCompactor.calculateProtectedBoundary(messages, 2);
        assertThat(boundary).isEqualTo(6);
    }

    @Test
    void calculateProtectedBoundaryWithFewerTurns() {
        var messages = List.<Message>of(
                Message.user("Only turn"),
                Message.assistant("Response")
        );

        // protectedTurns=5 but only 1 UserMessage -> protect everything -> return 0
        int boundary = MessageCompactor.calculateProtectedBoundary(messages, 5);
        assertThat(boundary).isEqualTo(0);
    }

    @Test
    void calculateProtectedBoundaryWithZero() {
        var messages = List.<Message>of(
                Message.user("Turn 1"),
                Message.assistant("Response 1"),
                Message.user("Turn 2"),
                Message.assistant("Response 2")
        );

        // protectedTurns=0 -> return messages.size() (nothing protected)
        int boundary = MessageCompactor.calculateProtectedBoundary(messages, 0);
        assertThat(boundary).isEqualTo(messages.size());
    }

    @Test
    void calculateProtectedBoundaryWithExactTurnCount() {
        var messages = List.<Message>of(
                Message.user("Turn 1"),
                Message.assistant("Response 1"),
                Message.user("Turn 2"),
                Message.assistant("Response 2")
        );

        // protectedTurns=2 = exact number of user turns -> return 0
        int boundary = MessageCompactor.calculateProtectedBoundary(messages, 2);
        assertThat(boundary).isEqualTo(0);
    }

    @Test
    void calculateProtectedBoundaryWithToolResultsBetweenTurns() {
        var messages = List.<Message>of(
                Message.user("Turn 1"),                    // index 0 - UserMessage
                Message.assistant("Calling tool"),         // index 1
                Message.toolResult("tu1", "result", false), // index 2 - UserMessage (tool result)
                Message.assistant("Got result"),           // index 3
                Message.user("Turn 2"),                    // index 4 - UserMessage
                Message.assistant("Done")                  // index 5
        );

        // protectedTurns=2 -> walk backward, find 2nd UserMessage from end
        // Index 4 = UserMessage -> count 1
        // Index 2 = UserMessage (tool result) -> count 2 -> return index 2
        int boundary = MessageCompactor.calculateProtectedBoundary(messages, 2);
        assertThat(boundary).isEqualTo(2);
    }

    // =========================================================================
    // MessageCompactor trigger tests
    // =========================================================================

    @Test
    void needsCompactionReturnsTrueAboveThreshold() {
        var config = CompactionConfig.DEFAULT;
        var compactor = new MessageCompactor(null, "test-model", config);

        // triggerTokens for DEFAULT is (int)(183616 * 0.85) = 156073
        assertThat(compactor.needsCompaction(config.triggerTokens() + 1)).isTrue();
        assertThat(compactor.needsCompaction(200_000)).isTrue();
    }

    @Test
    void needsCompactionReturnsFalseBelowThreshold() {
        var config = CompactionConfig.DEFAULT;
        var compactor = new MessageCompactor(null, "test-model", config);

        assertThat(compactor.needsCompaction(config.triggerTokens() - 1)).isFalse();
        assertThat(compactor.needsCompaction(0)).isFalse();
        assertThat(compactor.needsCompaction(1000)).isFalse();
    }

    @Test
    void needsCompactionReturnsFalseAtExactThreshold() {
        var config = CompactionConfig.DEFAULT;
        var compactor = new MessageCompactor(null, "test-model", config);

        // At exactly the threshold, it should be false (uses > not >=)
        assertThat(compactor.needsCompaction(config.triggerTokens())).isFalse();
    }

    @Test
    void needsCompactionEstimateUsesContextEstimator() {
        var config = new CompactionConfig(1000, 100, 0.85, 0.60, 2);
        var compactor = new MessageCompactor(null, "test-model", config);

        // effective = 900, trigger = (int)(900*0.85) = 765
        // Create messages that exceed the trigger
        var longText = "X".repeat(4000); // ~1000 tokens
        var messages = List.<Message>of(Message.user(longText));

        assertThat(compactor.needsCompactionEstimate(messages, "system", List.of())).isTrue();
    }

    @Test
    void needsCompactionEstimateReturnsFalseForSmallConversation() {
        var config = CompactionConfig.DEFAULT;
        var compactor = new MessageCompactor(null, "test-model", config);

        var messages = List.<Message>of(Message.user("hello"));

        assertThat(compactor.needsCompactionEstimate(messages, "You are helpful.", List.of()))
                .isFalse();
    }

    // =========================================================================
    // CompactionResult tests
    // =========================================================================

    @Test
    void reductionPercentCalculation() {
        var result = new CompactionResult(
                List.of(Message.user("compacted")),
                1000,  // original
                400,   // compacted
                CompactionResult.Phase.PRUNED,
                List.of()
        );

        assertThat(result.reductionPercent()).isEqualTo(60.0);
    }

    @Test
    void reductionPercentWithZeroOriginal() {
        var result = new CompactionResult(
                List.of(),
                0,  // original
                0,  // compacted
                CompactionResult.Phase.NONE,
                List.of()
        );

        assertThat(result.reductionPercent()).isEqualTo(0.0);
    }

    @Test
    void reductionPercentWithNoReduction() {
        var result = new CompactionResult(
                List.of(Message.user("same")),
                1000,
                1000,
                CompactionResult.Phase.PRUNED,
                List.of()
        );

        assertThat(result.reductionPercent()).isEqualTo(0.0);
    }

    @Test
    void reductionPercentWithFullReduction() {
        var result = new CompactionResult(
                List.of(),
                1000,
                0,
                CompactionResult.Phase.SUMMARIZED,
                List.of()
        );

        assertThat(result.reductionPercent()).isEqualTo(100.0);
    }

    @Test
    void compactionResultPreservesExtractedContext() {
        var context = List.of("Modified file: /src/Foo.java", "Executed: ./gradlew build");
        var result = new CompactionResult(
                List.of(Message.user("compacted")),
                1000,
                500,
                CompactionResult.Phase.PRUNED,
                context
        );

        assertThat(result.extractedContext()).containsExactly(
                "Modified file: /src/Foo.java",
                "Executed: ./gradlew build"
        );
    }

    @Test
    void compactionResultDefensiveCopyOfMessages() {
        var messages = new ArrayList<Message>(List.of(Message.user("msg")));
        var result = new CompactionResult(
                messages,
                100, 50,
                CompactionResult.Phase.PRUNED,
                List.of()
        );

        // Modifying the original list should not affect the result
        messages.add(Message.user("extra"));
        assertThat(result.compactedMessages()).hasSize(1);
    }

    @Test
    void compactionResultDefensiveCopyOfContext() {
        var context = new ArrayList<>(List.of("item1"));
        var result = new CompactionResult(
                List.of(Message.user("msg")),
                100, 50,
                CompactionResult.Phase.PRUNED,
                context
        );

        // Modifying the original list should not affect the result
        context.add("item2");
        assertThat(result.extractedContext()).hasSize(1);
    }

    @Test
    void compactionResultPhaseEnum() {
        assertThat(CompactionResult.Phase.values()).containsExactly(
                CompactionResult.Phase.NONE,
                CompactionResult.Phase.PRUNED,
                CompactionResult.Phase.SUMMARIZED
        );
    }

    // =========================================================================
    // MessageCompactor config accessor test
    // =========================================================================

    @Test
    void configAccessorReturnsConfig() {
        var config = CompactionConfig.DEFAULT;
        var compactor = new MessageCompactor(null, "test-model", config);

        assertThat(compactor.config()).isSameAs(config);
    }
}

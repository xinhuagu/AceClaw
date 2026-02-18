package dev.aceclaw.daemon;

import dev.aceclaw.memory.MemoryEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SessionEndExtractor} heuristic memory extraction.
 */
class SessionEndExtractorTest {

    @Test
    void extractsUserCorrections() {
        var messages = List.<AgentSession.ConversationMessage>of(
                new AgentSession.ConversationMessage.Assistant("Use HashMap for this"),
                new AgentSession.ConversationMessage.User("no, use ConcurrentHashMap instead")
        );

        var extracted = SessionEndExtractor.extract(messages);

        assertThat(extracted).hasSize(1);
        assertThat(extracted.getFirst().category()).isEqualTo(MemoryEntry.Category.CORRECTION);
        assertThat(extracted.getFirst().content()).contains("ConcurrentHashMap");
    }

    @Test
    void extractsExplicitPreferences() {
        var messages = List.<AgentSession.ConversationMessage>of(
                new AgentSession.ConversationMessage.User("always use Java 21 features")
        );

        var extracted = SessionEndExtractor.extract(messages);

        assertThat(extracted).hasSize(1);
        assertThat(extracted.getFirst().category()).isEqualTo(MemoryEntry.Category.PREFERENCE);
        assertThat(extracted.getFirst().content()).contains("Java 21");
    }

    @Test
    void extractsNothingFromEmptySession() {
        assertThat(SessionEndExtractor.extract(List.of())).isEmpty();
        assertThat(SessionEndExtractor.extract(null)).isEmpty();
    }

    @Test
    void ignoresNormalConversation() {
        var messages = List.<AgentSession.ConversationMessage>of(
                new AgentSession.ConversationMessage.User("what time is it"),
                new AgentSession.ConversationMessage.Assistant("It's 3pm")
        );

        var extracted = SessionEndExtractor.extract(messages);

        assertThat(extracted).isEmpty();
    }

    @Test
    void extractsMultipleInsights() {
        var messages = List.<AgentSession.ConversationMessage>of(
                new AgentSession.ConversationMessage.Assistant("I'll use var here"),
                new AgentSession.ConversationMessage.User("no, use explicit types for public APIs"),
                new AgentSession.ConversationMessage.User("always add Javadoc to public methods")
        );

        var extracted = SessionEndExtractor.extract(messages);

        assertThat(extracted).hasSizeGreaterThanOrEqualTo(2);

        var categories = extracted.stream()
                .map(SessionEndExtractor.ExtractedMemory::category)
                .toList();
        assertThat(categories).contains(MemoryEntry.Category.CORRECTION);
        assertThat(categories).contains(MemoryEntry.Category.PREFERENCE);
    }

    @Test
    void skipsDuplicateContent() {
        var messages = List.<AgentSession.ConversationMessage>of(
                new AgentSession.ConversationMessage.Assistant("Using HashMap"),
                new AgentSession.ConversationMessage.User("no, use ConcurrentHashMap"),
                new AgentSession.ConversationMessage.Assistant("Using HashMap again"),
                new AgentSession.ConversationMessage.User("no, use ConcurrentHashMap")
        );

        var extracted = SessionEndExtractor.extract(messages);

        long correctionCount = extracted.stream()
                .filter(m -> m.category() == MemoryEntry.Category.CORRECTION)
                .count();
        assertThat(correctionCount).isEqualTo(1);
    }

    @Test
    void extractsModifiedFilesSummary() {
        var messages = new ArrayList<AgentSession.ConversationMessage>();
        messages.add(new AgentSession.ConversationMessage.Assistant(
                "File written to /src/main/java/Foo.java"));
        messages.add(new AgentSession.ConversationMessage.Assistant(
                "File edited at /src/main/java/Bar.java"));
        messages.add(new AgentSession.ConversationMessage.Assistant(
                "Used write_file for /src/test/java/FooTest.java"));

        var extracted = SessionEndExtractor.extract(messages);

        var codebaseInsights = extracted.stream()
                .filter(m -> m.category() == MemoryEntry.Category.CODEBASE_INSIGHT)
                .toList();
        assertThat(codebaseInsights).hasSize(1);
        assertThat(codebaseInsights.getFirst().content()).contains("3 files");
    }

    @Test
    void truncatesLongMessages() {
        String longMessage = "always " + "x".repeat(300);
        var messages = List.<AgentSession.ConversationMessage>of(
                new AgentSession.ConversationMessage.User(longMessage)
        );

        var extracted = SessionEndExtractor.extract(messages);

        assertThat(extracted).hasSize(1);
        assertThat(extracted.getFirst().content().length()).isLessThanOrEqualTo(200);
        assertThat(extracted.getFirst().content()).endsWith("...");
    }

    @Test
    void handlesNullAndBlankContent() {
        var messages = List.<AgentSession.ConversationMessage>of(
                new AgentSession.ConversationMessage.Assistant("something"),
                new AgentSession.ConversationMessage.User(""),
                new AgentSession.ConversationMessage.User("   ")
        );

        var extracted = SessionEndExtractor.extract(messages);

        assertThat(extracted).isEmpty();
    }

    @Test
    void correctionRequiresPrecedingAssistantMessage() {
        // A "no, ..." at the start of conversation (no preceding assistant) should not match
        var messages = List.<AgentSession.ConversationMessage>of(
                new AgentSession.ConversationMessage.User("no, use the other approach")
        );

        var extracted = SessionEndExtractor.extract(messages);

        // Should not extract a correction since there's no preceding assistant message
        var corrections = extracted.stream()
                .filter(m -> m.category() == MemoryEntry.Category.CORRECTION)
                .toList();
        assertThat(corrections).isEmpty();
    }
}

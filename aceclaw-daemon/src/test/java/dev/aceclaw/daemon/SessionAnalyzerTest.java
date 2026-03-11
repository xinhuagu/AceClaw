package dev.aceclaw.daemon;

import dev.aceclaw.core.agent.ToolMetrics;
import dev.aceclaw.memory.MemoryEntry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SessionAnalyzerTest {

    private final SessionAnalyzer analyzer = new SessionAnalyzer();

    @Test
    void analyzesFullSessionRetrospective() {
        var messages = List.<AgentSession.ConversationMessage>of(
                new AgentSession.ConversationMessage.User("Investigate src/main/App.java and run tests"),
                new AgentSession.ConversationMessage.Assistant("""
                        $ rg "login" src/main/App.java
                        File edited at src/main/App.java
                        error: login handler not found
                        """),
                new AgentSession.ConversationMessage.User("wrong, use src/main/LoginService.java instead"),
                new AgentSession.ConversationMessage.Assistant("""
                        $ ./gradlew test
                        File written to src/test/LoginServiceTest.java
                        build succeeded
                        """)
        );
        var metrics = Map.of(
                "bash", new ToolMetrics("bash", 2, 1, 1, 1200, Instant.now()));

        var learnings = analyzer.analyze(messages, metrics);

        assertThat(learnings.extractedFilePaths())
                .contains("src/main/App.java", "src/main/LoginService.java", "src/test/LoginServiceTest.java");
        assertThat(learnings.executedCommands())
                .contains("rg \"login\" src/main/App.java", "./gradlew test");
        assertThat(learnings.errorsEncountered()).anyMatch(line -> line.contains("login handler not found"));
        assertThat(learnings.backtrackingDetected()).isTrue();
        assertThat(learnings.endToEndStrategy()).contains("inspect files");
        assertThat(learnings.sessionSummary()).contains("Session retrospective");
        assertThat(learnings.toolMetrics()).containsKey("bash");
        assertThat(learnings.insights().stream().map(SessionAnalyzer.SessionInsight::category))
                .contains(
                        MemoryEntry.Category.CODEBASE_INSIGHT,
                        MemoryEntry.Category.TOOL_USAGE,
                        MemoryEntry.Category.ERROR_RECOVERY,
                        MemoryEntry.Category.SUCCESSFUL_STRATEGY,
                        MemoryEntry.Category.SESSION_SUMMARY);
    }

    @Test
    void handlesEmptyHistory() {
        var learnings = analyzer.analyze(List.of(), Map.of());

        assertThat(learnings.insights()).isEmpty();
        assertThat(learnings.extractedFilePaths()).isEmpty();
        assertThat(learnings.executedCommands()).isEmpty();
        assertThat(learnings.errorsEncountered()).isEmpty();
        assertThat(learnings.sessionSummary()).isEmpty();
        assertThat(learnings.backtrackingDetected()).isFalse();
    }

    @Test
    void detectsBacktrackingFromCorrectionsAndErrors() {
        var messages = List.<AgentSession.ConversationMessage>of(
                new AgentSession.ConversationMessage.Assistant("error: file not found"),
                new AgentSession.ConversationMessage.User("no, use a different path"),
                new AgentSession.ConversationMessage.Assistant("updated path and retried")
        );

        var learnings = analyzer.analyze(messages, null);

        assertThat(learnings.backtrackingDetected()).isTrue();
        assertThat(learnings.insights().stream().map(SessionAnalyzer.SessionInsight::category))
                .contains(MemoryEntry.Category.ANTI_PATTERN);
    }
}

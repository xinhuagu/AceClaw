package dev.aceclaw.memory;

import dev.aceclaw.core.agent.ToolMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HistoricalLogIndexTest {

    @TempDir
    Path tempDir;

    @Test
    void indexesAndQueriesToolAndErrorHistory() throws Exception {
        var index = new HistoricalLogIndex(tempDir);
        var t0 = Instant.parse("2026-03-12T10:00:00Z");

        index.index(new HistoricalSessionSnapshot(
                "session-1",
                "ws-a",
                t0,
                List.of("./gradlew test"),
                List.of("Command timed out after 30s"),
                List.of("src/main/App.java"),
                Map.of("bash", new ToolMetrics("bash", 3, 2, 1, 1500, t0)),
                true,
                "The end-to-end strategy was to inspect files, then run commands such as ./gradlew test."
        ));
        index.index(new HistoricalSessionSnapshot(
                "session-2",
                "ws-a",
                t0.plusSeconds(3600),
                List.of("rg login src/main/App.java"),
                List.of("Permission denied: /etc/shadow"),
                List.of("src/main/App.java"),
                Map.of("rg", new ToolMetrics("rg", 2, 2, 0, 120, t0.plusSeconds(3600))),
                false,
                "The end-to-end strategy was to inspect files like src/main/App.java."
        ));

        assertThat(index.queryByTool("bash", t0.minusSeconds(1), t0.plusSeconds(7200)))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.invocationCount()).isEqualTo(3);
                    assertThat(entry.errorCount()).isEqualTo(1);
                });

        assertThat(index.queryByErrorClass(ErrorClass.TIMEOUT, t0.minusSeconds(1), t0.plusSeconds(7200)))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.tool()).isEqualTo("bash");
                    assertThat(entry.resolution()).contains("timeout");
                });

        assertThat(index.toolInvocationCounts(t0.minusSeconds(1), t0.plusSeconds(7200)))
                .containsEntry("bash", 3)
                .containsEntry("rg", 2);

        assertThat(index.errorCounts(t0.minusSeconds(1), t0.plusSeconds(7200)))
                .containsEntry(ErrorClass.TIMEOUT, 1)
                .containsEntry(ErrorClass.PERMISSION, 1);

        assertThat(index.patterns(t0.minusSeconds(1), t0.plusSeconds(7200)))
                .extracting(HistoricalLogIndex.PatternEntry::patternType)
                .contains(PatternType.ERROR_CORRECTION, PatternType.WORKFLOW);
    }

    @Test
    void queryRangeFiltersEntries() throws Exception {
        var index = new HistoricalLogIndex(tempDir);
        var t0 = Instant.parse("2026-03-12T10:00:00Z");

        index.index(new HistoricalSessionSnapshot(
                "old-session",
                "ws-a",
                t0.minusSeconds(7200),
                List.of("bash build.sh"),
                List.of(),
                List.of(),
                Map.of("bash", new ToolMetrics("bash", 1, 1, 0, 100, t0.minusSeconds(7200))),
                false,
                ""
        ));
        index.index(new HistoricalSessionSnapshot(
                "new-session",
                "ws-a",
                t0,
                List.of("bash build.sh"),
                List.of(),
                List.of(),
                Map.of("bash", new ToolMetrics("bash", 2, 2, 0, 200, t0)),
                false,
                ""
        ));

        assertThat(index.queryByTool("bash", t0.minusSeconds(60), t0.plusSeconds(60)))
                .singleElement()
                .extracting(HistoricalLogIndex.ToolInvocationEntry::sessionId)
                .isEqualTo("new-session");
    }

    @Test
    void skipsMalformedJsonlLinesWithoutDroppingValidHistory() throws Exception {
        var index = new HistoricalLogIndex(tempDir);
        var t0 = Instant.parse("2026-03-12T10:00:00Z");

        index.index(new HistoricalSessionSnapshot(
                "session-1",
                "ws-a",
                t0,
                List.of("bash build.sh"),
                List.of(),
                List.of(),
                Map.of("bash", new ToolMetrics("bash", 1, 1, 0, 100, t0)),
                false,
                ""
        ));

        Files.writeString(
                tempDir.resolve("index").resolve("tool_invocations.jsonl"),
                "{\"broken\":\n",
                java.nio.file.StandardOpenOption.APPEND
        );

        assertThat(index.queryByTool("bash", t0.minusSeconds(60), t0.plusSeconds(60)))
                .singleElement()
                .extracting(HistoricalLogIndex.ToolInvocationEntry::sessionId)
                .isEqualTo("session-1");
    }

    @Test
    void workspaceScopedQueriesDoNotLeakAcrossProjects() throws Exception {
        var index = new HistoricalLogIndex(tempDir);
        var t0 = Instant.parse("2026-03-12T10:00:00Z");

        index.index(new HistoricalSessionSnapshot(
                "session-a",
                "ws-a",
                t0,
                List.of("bash build.sh"),
                List.of("Command timed out after 30s"),
                List.of(),
                Map.of("bash", new ToolMetrics("bash", 2, 1, 1, 200, t0)),
                true,
                "The end-to-end strategy was to inspect files, then run build commands."
        ));
        index.index(new HistoricalSessionSnapshot(
                "session-b",
                "ws-b",
                t0.plusSeconds(60),
                List.of("bash build.sh"),
                List.of("Permission denied"),
                List.of(),
                Map.of("bash", new ToolMetrics("bash", 1, 0, 1, 120, t0.plusSeconds(60))),
                false,
                "The end-to-end strategy was to inspect files, then run build commands."
        ));

        assertThat(index.toolInvocations("ws-a", t0.minusSeconds(1), t0.plusSeconds(120)))
                .extracting(HistoricalLogIndex.ToolInvocationEntry::sessionId)
                .containsExactly("session-a");
        assertThat(index.errorEntries("ws-b", t0.minusSeconds(1), t0.plusSeconds(120)))
                .extracting(HistoricalLogIndex.ErrorEntry::sessionId)
                .containsExactly("session-b");
        assertThat(index.patterns("ws-a", t0.minusSeconds(1), t0.plusSeconds(120)))
                .extracting(HistoricalLogIndex.PatternEntry::sessionId)
                .containsOnly("session-a");
    }

    @Test
    void sessionIdsAndReplaceWorkspaceAreScoped() throws Exception {
        var index = new HistoricalLogIndex(tempDir);
        var t0 = Instant.parse("2026-03-12T10:00:00Z");

        index.index(new HistoricalSessionSnapshot(
                "old-a",
                "ws-a",
                t0,
                List.of("bash old-a.sh"),
                List.of("Command timed out"),
                List.of(),
                Map.of("bash", new ToolMetrics("bash", 1, 0, 1, 100, t0)),
                false,
                "old-a"
        ));
        index.index(new HistoricalSessionSnapshot(
                "keep-b",
                "ws-b",
                t0.plusSeconds(60),
                List.of("bash keep-b.sh"),
                List.of(),
                List.of(),
                Map.of("bash", new ToolMetrics("bash", 2, 2, 0, 200, t0.plusSeconds(60))),
                false,
                "keep-b"
        ));

        assertThat(index.sessionIds("ws-a")).isEqualTo(Set.of("old-a"));
        assertThat(index.sessionIds("ws-b")).isEqualTo(Set.of("keep-b"));

        index.replaceWorkspace("ws-a", List.of(
                new HistoricalSessionSnapshot(
                        "new-a",
                        "ws-a",
                        t0.plusSeconds(120),
                        List.of("bash new-a.sh"),
                        List.of(),
                        List.of(),
                        Map.of("bash", new ToolMetrics("bash", 3, 3, 0, 300, t0.plusSeconds(120))),
                        false,
                        "new-a"
                )
        ));

        assertThat(index.sessionIds("ws-a")).isEqualTo(Set.of("new-a"));
        assertThat(index.sessionIds("ws-b")).isEqualTo(Set.of("keep-b"));
        assertThat(index.queryByTool("bash", t0.minusSeconds(1), t0.plusSeconds(300)))
                .extracting(HistoricalLogIndex.ToolInvocationEntry::sessionId)
                .contains("new-a", "keep-b")
                .doesNotContain("old-a");
    }
}

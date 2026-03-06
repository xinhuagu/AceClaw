package dev.aceclaw.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ActionableContentCheckerTest {

    @ParameterizedTest
    @NullAndEmptySource
    void rejectsNullAndEmpty(String content) {
        assertThat(ActionableContentChecker.isActionable(content)).isFalse();
    }

    @Test
    void rejectsTooShortContent() {
        assertThat(ActionableContentChecker.isActionable("short")).isFalse();
        assertThat(ActionableContentChecker.isActionable("a]b".repeat(6))).isFalse(); // 18 chars
    }

    // -- Denylist: known non-actionable patterns --

    @ParameterizedTest
    @ValueSource(strings = {
            "Tool 'bash' repeatedly fails (6 times in session)",
            "Tool 'grep' repeatedly fails with exit code 1",
            "API returns 500 status code consistently in all calls",
            "The operation timed out after 30 seconds each attempt",
            "Permission denied on every single access to the file",
            "Connection refused on every attempt to reach the server"
    })
    void rejectsPureErrorObservations(String content) {
        assertThat(ActionableContentChecker.isActionable(content)).isFalse();
    }

    // -- Allowlist: content that should pass --

    @ParameterizedTest
    @ValueSource(strings = {
            // Action-oriented strategies
            "Use retry with bounded timeout for flaky commands",
            "Set encoding to UTF-8 instead of system default",
            "Add fallback logic for network failures",
            // User preference patterns (P1 regression test)
            "User repeatedly corrects: prefer tabs over spaces",
            "User repeatedly corrects (cross-session): always use single quotes",
            "User prefers concise output format when reviewing diffs",
            // Non-English content (P2 regression test)
            "Verwende UTF-8 Kodierung fuer alle Dateien im Projekt",
            "Benutzer korrigiert wiederholt: Einrueckung mit Tabs statt Leerzeichen"
    })
    void acceptsActionableAndPreferenceContent(String content) {
        assertThat(ActionableContentChecker.isActionable(content)).isTrue();
    }

    @Test
    void caseInsensitiveMatchingOfDenyPatterns() {
        assertThat(ActionableContentChecker.isActionable(
                "TOOL 'BASH' REPEATEDLY FAILS (3 TIMES IN SESSION)")).isFalse();
        assertThat(ActionableContentChecker.isActionable(
                "error occurred while processing the batch job")).isFalse();
    }

    @Test
    void contentWithErrorSubstringButNotMatchingDenyPatternPasses() {
        // Contains "error" but does not match denylist pattern
        assertThat(ActionableContentChecker.isActionable(
                "When error rate exceeds 10% switch to fallback endpoint")).isTrue();
        // Contains "fails" but not in the denylist format
        assertThat(ActionableContentChecker.isActionable(
                "If the build fails try clearing the Gradle cache first")).isTrue();
    }
}

package dev.aceclaw.daemon;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the static keyword detection used by the explicit /continue
 * command (issue #501). The detection logic lives in
 * {@link StreamingAgentHandler#isExplicitContinue} so we don't pay the cost
 * of spinning up the daemon for what is purely string normalization.
 */
class ExplicitContinueKeywordTest {

    @Test
    void recognizesChineseContinue() {
        assertTrue(StreamingAgentHandler.isExplicitContinue("继续"));
        assertTrue(StreamingAgentHandler.isExplicitContinue("  继续  "));
        assertTrue(StreamingAgentHandler.isExplicitContinue("\n继续\n"));
    }

    @Test
    void recognizesEnglishContinue() {
        assertTrue(StreamingAgentHandler.isExplicitContinue("continue"));
        assertTrue(StreamingAgentHandler.isExplicitContinue("Continue"));
        assertTrue(StreamingAgentHandler.isExplicitContinue("CONTINUE"));
        assertTrue(StreamingAgentHandler.isExplicitContinue("  continue  "));
    }

    @Test
    void recognizesResume() {
        assertTrue(StreamingAgentHandler.isExplicitContinue("resume"));
        assertTrue(StreamingAgentHandler.isExplicitContinue("RESUME"));
    }

    @Test
    void recognizesSlashCommands() {
        assertTrue(StreamingAgentHandler.isExplicitContinue("/resume"));
        assertTrue(StreamingAgentHandler.isExplicitContinue("/continue"));
        assertTrue(StreamingAgentHandler.isExplicitContinue("/CONTINUE"));
    }

    @Test
    void rejectsPromptsThatContainButDontEqualKeyword() {
        // Must be a full-line match — substrings or sentences mentioning
        // "continue" are real prompts, not the command.
        assertFalse(StreamingAgentHandler.isExplicitContinue("please continue"));
        assertFalse(StreamingAgentHandler.isExplicitContinue("continue with the next task"));
        assertFalse(StreamingAgentHandler.isExplicitContinue("继续 写代码"));
        assertFalse(StreamingAgentHandler.isExplicitContinue("resume the migration"));
    }

    @Test
    void rejectsEmptyAndNull() {
        assertFalse(StreamingAgentHandler.isExplicitContinue(null));
        assertFalse(StreamingAgentHandler.isExplicitContinue(""));
        assertFalse(StreamingAgentHandler.isExplicitContinue("   "));
        assertFalse(StreamingAgentHandler.isExplicitContinue("\n\t"));
    }

    @Test
    void rejectsUnrelatedShortInputs() {
        assertFalse(StreamingAgentHandler.isExplicitContinue("yes"));
        assertFalse(StreamingAgentHandler.isExplicitContinue("ok"));
        assertFalse(StreamingAgentHandler.isExplicitContinue("继"));
    }
}

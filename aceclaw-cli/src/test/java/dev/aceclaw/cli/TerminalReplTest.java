package dev.aceclaw.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests slash command handling in {@link TerminalRepl}.
 *
 * <p>Since the REPL is tightly coupled to JLine for I/O, we test the
 * slash command dispatch directly via the package-private
 * {@code handleSlashCommand} method.
 */
class TerminalReplTest {

    private TerminalRepl repl;
    private StringWriter outputBuffer;
    private PrintWriter out;

    @BeforeEach
    void setUp() {
        // DaemonClient is needed but slash commands that don't touch it won't fail
        // We pass null — commands that need it (/tools, /compact) will NPE,
        // but we only test commands that don't require the client
        var sessionInfo = new TerminalRepl.SessionInfo("1.0.0", "claude-sonnet-4-5-20250929",
                "/tmp/project", 200_000, "main");
        repl = new TerminalRepl(null, "test-session", sessionInfo);
        outputBuffer = new StringWriter();
        out = new PrintWriter(outputBuffer);
    }

    @Test
    void help_producesHelpOutput() {
        boolean shouldExit = repl.handleSlashCommand(out, "/help");
        assertThat(shouldExit).isFalse();
        String output = outputBuffer.toString();
        assertThat(output).contains("Available commands");
        assertThat(output).contains("/help");
        assertThat(output).contains("/exit");
        assertThat(output).contains("/model");
        assertThat(output).contains("/tools");
    }

    @Test
    void questionMark_isHelpAlias() {
        boolean shouldExit = repl.handleSlashCommand(out, "/?");
        assertThat(shouldExit).isFalse();
        assertThat(outputBuffer.toString()).contains("Available commands");
    }

    @Test
    void clear_returnsFalse() {
        boolean shouldExit = repl.handleSlashCommand(out, "/clear");
        assertThat(shouldExit).isFalse();
        // Outputs ANSI clear screen sequence
        assertThat(outputBuffer.toString()).contains("\033[2J");
    }

    @Test
    void modelWithNoArg_showsNotConnectedWhenNullClient() {
        boolean shouldExit = repl.handleSlashCommand(out, "/model");
        assertThat(shouldExit).isFalse();
        assertThat(outputBuffer.toString()).contains("Not connected to daemon");
    }

    @Test
    void modelWithArg_showsNotConnectedWhenNullClient() {
        boolean shouldExit = repl.handleSlashCommand(out, "/model gpt-4o");
        assertThat(shouldExit).isFalse();
        assertThat(outputBuffer.toString()).contains("Not connected to daemon");
    }

    @Test
    void exit_returnsTrue() {
        boolean shouldExit = repl.handleSlashCommand(out, "/exit");
        assertThat(shouldExit).isTrue();
        assertThat(outputBuffer.toString()).contains("Goodbye");
    }

    @Test
    void quit_returnsTrue() {
        boolean shouldExit = repl.handleSlashCommand(out, "/quit");
        assertThat(shouldExit).isTrue();
    }

    @Test
    void unknownCommand_showsWarning() {
        boolean shouldExit = repl.handleSlashCommand(out, "/foo");
        assertThat(shouldExit).isFalse();
        assertThat(outputBuffer.toString()).contains("Unknown command");
    }

    @Test
    void status_showsSessionInfo() {
        boolean shouldExit = repl.handleSlashCommand(out, "/status");
        assertThat(shouldExit).isFalse();
        String output = outputBuffer.toString();
        assertThat(output).contains("Session Status");
        assertThat(output).contains("claude-sonnet-4-5-20250929");
        assertThat(output).contains("/tmp/project");
    }
}

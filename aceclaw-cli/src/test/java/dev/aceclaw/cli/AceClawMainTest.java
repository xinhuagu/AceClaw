package dev.aceclaw.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

class AceClawMainTest {

    @Test
    void daemonStartAcceptsShortProviderOption() {
        var command = new AceClawMain.DaemonStartCommand();
        new CommandLine(command).parseArgs("-p", "copilot");

        assertThat(command.provider).isEqualTo("copilot");
    }

    @Test
    void daemonStartAcceptsLongProviderOption() {
        var command = new AceClawMain.DaemonStartCommand();
        new CommandLine(command).parseArgs("--provider", "openai");

        assertThat(command.provider).isEqualTo("openai");
    }

    @Test
    void daemonStartDefaultsToBackgroundMode() {
        var command = new AceClawMain.DaemonStartCommand();
        new CommandLine(command).parseArgs();

        assertThat(command.foreground).isFalse();
    }

    @Test
    void daemonStartAcceptsForegroundFlag() {
        var command = new AceClawMain.DaemonStartCommand();
        new CommandLine(command).parseArgs("--foreground", "-p", "copilot");

        assertThat(command.foreground).isTrue();
        assertThat(command.provider).isEqualTo("copilot");
    }
}

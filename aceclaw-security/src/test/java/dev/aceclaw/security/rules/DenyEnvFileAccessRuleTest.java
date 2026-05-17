package dev.aceclaw.security.rules;

import dev.aceclaw.security.Capability;
import dev.aceclaw.security.PermissionDecision;
import dev.aceclaw.security.PolicyContext;
import dev.aceclaw.security.Provenance;
import dev.aceclaw.security.WriteMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

final class DenyEnvFileAccessRuleTest {

    private static final PolicyContext CTX = new PolicyContext("any_tool", "any");
    private static final Provenance PROV = Provenance.daemonInternal();
    private static final DenyEnvFileAccessRule RULE = new DenyEnvFileAccessRule();

    @ParameterizedTest
    @ValueSource(strings = {
            // .env family
            ".env",
            ".env.local",
            ".env.production",
            "/home/me/project/.env",
            "/home/me/project/.env.test",
            // SSH keys
            "/home/me/.ssh/id_rsa",     // also matches via .ssh directory segment
            "id_ed25519",
            "id_dsa",
            "id_ecdsa",
            // Cert / keystore extensions
            "/etc/ssl/server.pem",
            "/var/secrets/api.key",
            "wallet.kdbx",
            "release.keystore",
            "java.jks",
            "client.p12",
            "client.pfx",
            // Auth-token configs
            ".netrc",
            ".npmrc",
            ".pypirc",
            ".git-credentials",
            // AWS / cloud credentials
            "/home/me/.aws/credentials",
            "/home/me/.config/gcloud/credentials.json",
            // Bare credentials
            "credentials",
            "credentials.json",
    })
    void denyForCredentialPaths(String pathStr) {
        var read = new Capability.FileRead(Path.of(pathStr));
        var decision = RULE.evaluate(read, PROV, CTX);
        assertThat(decision)
                .as("FileRead(%s) should be denied by env-file rule", pathStr)
                .hasValueSatisfying(d -> assertThat(d).isInstanceOf(PermissionDecision.Denied.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/etc/hosts",
            "/home/me/notes.md",
            "envoy-config.yaml",        // contains "env" but isn't .env
            "environment.txt",
            "main.py",
            "credentials_helper.go",    // not exactly "credentials"
            "/home/me/code/.env.example.txt",  // .env.example.txt is .env.<segment> — match!
            ".gitignore",
            "id_rsa.pub",               // public key — fine to read
    })
    void deferForNonCredentialPaths(String pathStr) {
        var read = new Capability.FileRead(Path.of(pathStr));
        var decision = RULE.evaluate(read, PROV, CTX);
        // NOTE: ".env.example.txt" actually MATCHES the .env.<...> pattern.
        // That's the intended conservative bias — operators get a single
        // false positive on the "example" template file rather than a real
        // .env slipping through. Adjust the test if the rule pattern tightens.
        if (pathStr.equals("/home/me/code/.env.example.txt")) {
            assertThat(decision).isPresent();
        } else {
            assertThat(decision)
                    .as("FileRead(%s) should NOT be matched by env-file rule", pathStr)
                    .isEmpty();
        }
    }

    @Test
    void denyAppliesToWriteAndDelete() {
        var path = Path.of("/home/me/.env");
        assertThat(RULE.evaluate(new Capability.FileWrite(path, WriteMode.OVERWRITE), PROV, CTX))
                .as("FileWrite to .env must be denied")
                .isPresent();
        assertThat(RULE.evaluate(new Capability.FileDelete(path), PROV, CTX))
                .as("FileDelete on .env must be denied")
                .isPresent();
    }

    @Test
    void grepRootedAtCredentialFileIsDenied() {
        // GREP rooted directly at a credential file reads the file's
        // bytes and ships matches into the agent transcript — same leak
        // shape as FileRead, so the rule denies it.
        var grep = new Capability.FileSearch(
                Path.of("/home/me/.env"), "PASSWORD", dev.aceclaw.security.SearchKind.GREP);
        assertThat(RULE.evaluate(grep, PROV, CTX))
                .as("GREP on a credential file must be denied (content-disclosing)")
                .isPresent();
    }

    @Test
    void globAndListAreDeferEvenOnCredentialPaths() {
        // GLOB / LIST only see filenames, not contents. Denying them
        // would break the normal "find my dotfiles" flow without
        // adding meaningful protection (operators already know .env
        // exists). Defer to the fallback policy (which decides
        // read-vs-prompt based on PermissionLevel).
        var glob = new Capability.FileSearch(
                Path.of("/home/me/.env"), "*", dev.aceclaw.security.SearchKind.GLOB);
        var list = new Capability.FileSearch(
                Path.of("/home/me/.ssh"), "*", dev.aceclaw.security.SearchKind.LIST);
        assertThat(RULE.evaluate(glob, PROV, CTX)).isEmpty();
        assertThat(RULE.evaluate(list, PROV, CTX)).isEmpty();
    }

    @Test
    void grepOnNonCredentialPathDefers() {
        var grep = new Capability.FileSearch(
                Path.of("/home/me/src"), "TODO", dev.aceclaw.security.SearchKind.GREP);
        assertThat(RULE.evaluate(grep, PROV, CTX)).isEmpty();
    }

    @Test
    void doesNotMatchUnrelatedCapabilityVariants() {
        var bash = new Capability.BashExec("echo hi", Path.of("/tmp"));
        var http = new Capability.HttpFetch(java.net.URI.create("https://example.com"), "GET");
        assertThat(RULE.evaluate(bash, PROV, CTX)).isEmpty();
        assertThat(RULE.evaluate(http, PROV, CTX)).isEmpty();
    }

    @Test
    void denialReasonNamesTheRuleForAuditability() {
        var decision = RULE.evaluate(
                new Capability.FileRead(Path.of(".env")), PROV, CTX);
        assertThat(decision).hasValueSatisfying(d -> {
            var denied = (PermissionDecision.Denied) d;
            assertThat(denied.reason())
                    .as("denial reason must name the rule so audit readers know which rule fired")
                    .contains("DenyEnvFileAccessRule");
        });
    }
}

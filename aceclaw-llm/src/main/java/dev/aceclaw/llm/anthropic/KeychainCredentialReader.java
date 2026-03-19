package dev.aceclaw.llm.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Reads Claude CLI OAuth credentials from macOS Keychain and credential files.
 *
 * <p>Credential discovery priority (same as OpenClaw):
 * <ol>
 *   <li>macOS Keychain: {@code security find-generic-password -s "Claude Code-credentials" -w}</li>
 *   <li>File: {@code ~/.claude/.credentials.json}</li>
 * </ol>
 *
 * <p>The stored JSON structure in both sources:
 * <pre>{@code
 * {
 *   "claudeAiOauth": {
 *     "accessToken": "sk-ant-oat01-...",
 *     "refreshToken": "sk-ant-ort01-...",
 *     "expiresAt": 1773971975754
 *   }
 * }
 * }</pre>
 */
public final class KeychainCredentialReader {

    private static final Logger log = LoggerFactory.getLogger(KeychainCredentialReader.class);

    private static final String KEYCHAIN_SERVICE = "Claude Code-credentials";
    private static final String KEYCHAIN_ACCOUNT = "Claude Code";
    private static final Path CREDENTIALS_FILE =
            Path.of(System.getProperty("user.home"), ".claude", ".credentials.json");

    private KeychainCredentialReader() {}

    /**
     * Credential data read from Claude CLI storage.
     *
     * @param accessToken  the OAuth access token
     * @param refreshToken the OAuth refresh token (may be null for token-only auth)
     * @param expiresAt    expiry timestamp in milliseconds since epoch
     */
    public record Credential(String accessToken, String refreshToken, long expiresAt) {
        public Credential {
            Objects.requireNonNull(accessToken, "accessToken");
        }

        /** Returns true if this credential has expired (with 60s grace period). */
        public boolean isExpired() {
            return System.currentTimeMillis() > (expiresAt - 60_000L);
        }
    }

    /**
     * Attempts to read Claude CLI credentials, preferring Keychain on macOS.
     *
     * @return credential data, or null if not found
     */
    public static Credential read() {
        // 1. Try macOS Keychain first
        if (isMacOS()) {
            Credential keychainCred = readFromKeychain();
            if (keychainCred != null) {
                log.info("Read Claude CLI credentials from macOS Keychain");
                return keychainCred;
            }
        }

        // 2. Fall back to credential file
        Credential fileCred = readFromFile(CREDENTIALS_FILE);
        if (fileCred != null) {
            log.info("Read Claude CLI credentials from file: {}", CREDENTIALS_FILE);
        }
        return fileCred;
    }

    /**
     * Writes refreshed credentials back to the macOS Keychain.
     *
     * @param newAccessToken  the new access token
     * @param newRefreshToken the new refresh token
     * @param newExpiresAt    the new expiry timestamp
     * @return true if write succeeded
     */
    public static boolean writeToKeychain(String newAccessToken, String newRefreshToken, long newExpiresAt) {
        Objects.requireNonNull(newAccessToken, "newAccessToken");
        if (!isMacOS()) {
            return false;
        }
        try {
            // Read existing keychain entry
            String existingJson = readKeychainRaw();
            if (existingJson == null) {
                return false;
            }

            var mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(existingJson);
            if (!root.has("claudeAiOauth")) {
                return false;
            }

            // Update the OAuth fields
            var rootObj = (com.fasterxml.jackson.databind.node.ObjectNode) root;
            var oauthObj = (com.fasterxml.jackson.databind.node.ObjectNode) root.get("claudeAiOauth");
            oauthObj.put("accessToken", newAccessToken);
            if (newRefreshToken != null) {
                oauthObj.put("refreshToken", newRefreshToken);
            }
            oauthObj.put("expiresAt", newExpiresAt);

            String updatedJson = mapper.writeValueAsString(rootObj);

            // ProcessBuilder passes arguments directly to execve (no shell interpolation),
            // preventing command injection via token values containing $() or backticks.
            ProcessBuilder pb = new ProcessBuilder(
                    "security", "add-generic-password", "-U",
                    "-s", KEYCHAIN_SERVICE,
                    "-a", KEYCHAIN_ACCOUNT,
                    "-w", updatedJson);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            try {
                boolean finished = proc.waitFor(5, TimeUnit.SECONDS);
                if (finished && proc.exitValue() == 0) {
                    log.info("Wrote refreshed credentials to Claude CLI Keychain");
                    return true;
                }
                log.warn("Failed to write credentials to Keychain: exit={}", finished ? proc.exitValue() : "timeout");
                return false;
            } finally {
                destroyProcess(proc);
            }
        } catch (Exception e) {
            log.warn("Failed to write credentials to Keychain: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Writes refreshed credentials back to the credential file.
     *
     * @param newAccessToken  the new access token
     * @param newRefreshToken the new refresh token
     * @param newExpiresAt    the new expiry timestamp
     * @return true if write succeeded
     */
    public static boolean writeToFile(String newAccessToken, String newRefreshToken, long newExpiresAt) {
        Objects.requireNonNull(newAccessToken, "newAccessToken");
        if (!Files.isRegularFile(CREDENTIALS_FILE)) {
            return false;
        }
        try {
            var mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(CREDENTIALS_FILE.toFile());
            if (!root.has("claudeAiOauth") || !(root instanceof com.fasterxml.jackson.databind.node.ObjectNode rootObj)) {
                return false;
            }

            var oauthObj = (com.fasterxml.jackson.databind.node.ObjectNode) root.get("claudeAiOauth");
            oauthObj.put("accessToken", newAccessToken);
            if (newRefreshToken != null) {
                oauthObj.put("refreshToken", newRefreshToken);
            }
            oauthObj.put("expiresAt", newExpiresAt);

            // Atomic write via temp file with restrictive permissions
            Path tmp = CREDENTIALS_FILE.resolveSibling(CREDENTIALS_FILE.getFileName() + ".tmp");
            Files.writeString(tmp, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(rootObj));
            try {
                Files.setPosixFilePermissions(tmp,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX filesystem (e.g., Windows)
            }
            Files.move(tmp, CREDENTIALS_FILE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);

            log.info("Wrote refreshed credentials to file: {}", CREDENTIALS_FILE);
            return true;
        } catch (Exception e) {
            log.warn("Failed to write credentials to file: {}", e.getMessage());
            return false;
        }
    }

    // -- internal --

    static Credential readFromKeychain() {
        String json = readKeychainRaw();
        if (json == null) {
            return null;
        }
        return parseClaudeOauthJson(json);
    }

    static Credential readFromFile(Path path) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            String json = Files.readString(path);
            return parseClaudeOauthJson(json);
        } catch (Exception e) {
            log.warn("Could not read credentials from {}: {}", path, e.getMessage());
            return null;
        }
    }

    private static String readKeychainRaw() {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "security", "find-generic-password",
                    "-s", KEYCHAIN_SERVICE, "-w");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            try {
                String output;
                try (var reader = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    output = reader.lines().reduce("", (a, b) -> a + b);
                }

                boolean finished = proc.waitFor(5, TimeUnit.SECONDS);
                if (finished && proc.exitValue() == 0 && !output.isBlank()) {
                    return output.trim();
                }
                return null;
            } finally {
                destroyProcess(proc);
            }
        } catch (Exception e) {
            log.debug("Could not read from macOS Keychain: {}", e.getMessage());
            return null;
        }
    }

    private static Credential parseClaudeOauthJson(String json) {
        try {
            var mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(json);
            JsonNode oauth = root.path("claudeAiOauth");
            if (oauth.isMissingNode()) {
                return null;
            }

            String accessToken = oauth.path("accessToken").asText(null);
            if (accessToken == null || accessToken.isBlank()) {
                return null;
            }

            String refreshToken = oauth.path("refreshToken").asText(null);
            long expiresAt = oauth.path("expiresAt").asLong(0);

            return new Credential(accessToken,
                    (refreshToken != null && !refreshToken.isBlank()) ? refreshToken : null,
                    expiresAt);
        } catch (Exception e) {
            log.warn("Failed to parse Claude OAuth JSON: {}", e.getMessage());
            return null;
        }
    }

    /** Ensures a subprocess is terminated and reaped. */
    private static void destroyProcess(Process proc) {
        if (proc == null || !proc.isAlive()) return;
        proc.destroy();
        try {
            if (!proc.waitFor(2, TimeUnit.SECONDS)) {
                proc.destroyForcibly().waitFor(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            proc.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isMacOS() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }
}

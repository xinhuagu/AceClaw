package dev.aceclaw.llm.openai;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.util.WaitSupport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * GitHub OAuth device-code flow for Copilot authentication.
 *
 * <p>Uses the VS Code Copilot extension's OAuth client ID to obtain a token
 * that works with the {@code copilot_internal/v2/token} exchange endpoint.
 *
 * <p>The obtained token is cached at {@code ~/.aceclaw/copilot-oauth-token}.
 * This class is meant to run in the CLI (interactive terminal), not the daemon.
 */
public final class CopilotDeviceAuth {

    private static final String GITHUB_CLIENT_ID = "Iv1.b507a08c87ecfe98";
    private static final String DEVICE_CODE_URL = "https://github.com/login/device/code";
    private static final String ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** File where the obtained OAuth token is cached. */
    public static final Path TOKEN_FILE = Path.of(
            System.getProperty("user.home"), ".aceclaw", "copilot-oauth-token");

    private static final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private CopilotDeviceAuth() {}

    /**
     * Returns a cached OAuth token if it exists and is non-empty.
     */
    public static String loadCachedToken() {
        try {
            if (Files.exists(TOKEN_FILE)) {
                String token = Files.readString(TOKEN_FILE).trim();
                if (!token.isEmpty()) {
                    return token;
                }
            }
        } catch (IOException e) {
            // ignore
        }
        return null;
    }

    /**
     * Runs the interactive device-code OAuth flow.
     * Prints the user code and verification URL to stdout, polls for authorization,
     * and caches the obtained token.
     *
     * @return the OAuth access token
     * @throws RuntimeException if the flow fails or times out
     */
    public static String authenticate() {
        try {
            // Step 1: Request device code
            String body = mapper.writeValueAsString(Map.of(
                    "client_id", GITHUB_CLIENT_ID,
                    "scope", "read:user"
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(DEVICE_CODE_URL))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Device code request failed: HTTP " + response.statusCode());
            }

            JsonNode node = mapper.readTree(response.body());
            String deviceCode = node.path("device_code").asText();
            String userCode = node.path("user_code").asText();
            String verificationUri = node.path("verification_uri").asText("https://github.com/login/device");
            int expiresIn = node.path("expires_in").asInt(900);
            int interval = node.path("interval").asInt(5);

            // Print auth prompt
            System.out.println();
            System.out.println("  GitHub Copilot Authentication");
            System.out.println("  ─────────────────────────────");
            System.out.println("  1. Open: " + verificationUri);
            System.out.println("  2. Enter code: " + userCode);
            System.out.println();
            System.out.println("  Waiting for authorization...");

            // Step 2: Poll for access token
            String pollBody = mapper.writeValueAsString(Map.of(
                    "client_id", GITHUB_CLIENT_ID,
                    "device_code", deviceCode,
                    "grant_type", "urn:ietf:params:oauth:grant-type:device_code"
            ));

            long deadline = System.currentTimeMillis() + (expiresIn * 1000L);
            int pollMs = (interval + 1) * 1000;

            while (System.currentTimeMillis() < deadline) {
                WaitSupport.sleepInterruptibly(Duration.ofMillis(pollMs));

                HttpRequest pollRequest = HttpRequest.newBuilder()
                        .uri(URI.create(ACCESS_TOKEN_URL))
                        .timeout(TIMEOUT)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(pollBody))
                        .build();

                HttpResponse<String> pollResponse = httpClient.send(
                        pollRequest, HttpResponse.BodyHandlers.ofString());
                if (pollResponse.statusCode() != 200) {
                    continue;
                }

                JsonNode pollNode = mapper.readTree(pollResponse.body());
                String accessToken = pollNode.path("access_token").asText(null);
                String error = pollNode.path("error").asText(null);

                if (accessToken != null && !accessToken.isBlank()) {
                    System.out.println("  Authentication successful!");
                    System.out.println();
                    cacheToken(accessToken);
                    return accessToken;
                }

                if ("slow_down".equals(error)) {
                    pollMs += 5000;
                } else if (!"authorization_pending".equals(error)) {
                    throw new RuntimeException("OAuth error: " + error);
                }
            }

            throw new RuntimeException("Device code flow timed out after " + expiresIn + "s");

        } catch (RuntimeException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Authentication interrupted", e);
        } catch (Exception e) {
            throw new RuntimeException("Authentication failed: " + e.getMessage(), e);
        }
    }

    private static void cacheToken(String token) {
        try {
            Files.createDirectories(TOKEN_FILE.getParent());
            Files.writeString(TOKEN_FILE, token);
        } catch (IOException e) {
            System.err.println("Warning: could not cache token: " + e.getMessage());
        }
    }
}

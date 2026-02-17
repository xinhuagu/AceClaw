package dev.aceclaw.daemon;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Configuration for the AceClaw daemon and CLI.
 *
 * <p>Loaded from two optional JSON files (later values override earlier ones):
 * <ol>
 *   <li>{@code ~/.aceclaw/config.json} — global user config</li>
 *   <li>{@code {project}/.aceclaw/config.json} — project-specific overrides</li>
 * </ol>
 *
 * <p>Environment variables take highest precedence:
 * <ul>
 *   <li>{@code ANTHROPIC_API_KEY} → apiKey</li>
 *   <li>{@code ACECLAW_MODEL} → model</li>
 *   <li>{@code ACECLAW_LOG_LEVEL} → logLevel</li>
 * </ul>
 */
public final class AceClawConfig {

    private static final Logger log = LoggerFactory.getLogger(AceClawConfig.class);

    private static final String CONFIG_FILE_NAME = "config.json";
    private static final Path GLOBAL_CONFIG_DIR = Path.of(System.getProperty("user.home"), ".aceclaw");

    // Default values
    private static final String DEFAULT_MODEL = "claude-sonnet-4-5-20250929";
    private static final int DEFAULT_MAX_TOKENS = 16384;
    private static final String DEFAULT_LOG_LEVEL = "INFO";

    /** Claude CLI credentials directory. */
    private static final Path CLAUDE_CLI_DIR = Path.of(System.getProperty("user.home"), ".claude");

    private String apiKey;
    private String refreshToken;
    private String model;
    private int maxTokens;
    private String logLevel;

    private AceClawConfig() {
        this.model = DEFAULT_MODEL;
        this.maxTokens = DEFAULT_MAX_TOKENS;
        this.logLevel = DEFAULT_LOG_LEVEL;
    }

    /**
     * Loads configuration from global and project config files, with env var overrides.
     *
     * @param projectPath the project working directory (may be null)
     * @return the merged configuration
     */
    public static AceClawConfig load(Path projectPath) {
        var config = new AceClawConfig();

        // 1. Load global config
        var globalConfig = GLOBAL_CONFIG_DIR.resolve(CONFIG_FILE_NAME);
        config.mergeFromFile(globalConfig);

        // 2. Load project-specific config
        if (projectPath != null) {
            var projectConfig = projectPath.resolve(".aceclaw").resolve(CONFIG_FILE_NAME);
            config.mergeFromFile(projectConfig);
        }

        // 3. Environment variables (highest precedence)
        var envApiKey = System.getenv("ANTHROPIC_API_KEY");
        if (envApiKey != null && !envApiKey.isBlank()) {
            config.apiKey = envApiKey;
        }
        var envModel = System.getenv("ACECLAW_MODEL");
        if (envModel != null && !envModel.isBlank()) {
            config.model = envModel;
        }
        var envLogLevel = System.getenv("ACECLAW_LOG_LEVEL");
        if (envLogLevel != null && !envLogLevel.isBlank()) {
            config.logLevel = envLogLevel;
        }

        // 4. If apiKey is an OAuth token and no refresh token configured,
        //    try to load the refresh token from Claude CLI credentials
        if (config.apiKey != null && config.apiKey.startsWith("sk-ant-oat")
                && config.refreshToken == null) {
            config.loadClaudeCliCredentials();
        }

        log.info("Config loaded: model={}, maxTokens={}, logLevel={}, apiKey={}, refreshToken={}",
                config.model, config.maxTokens, config.logLevel,
                config.apiKey != null ? config.apiKey.substring(0, Math.min(15, config.apiKey.length())) + "***" : "(not set)",
                config.refreshToken != null ? "***" : "(not set)");

        return config;
    }

    /**
     * Returns the API key, or null if not configured.
     */
    public String apiKey() {
        return apiKey;
    }

    /**
     * Returns the model identifier.
     */
    public String model() {
        return model;
    }

    /**
     * Returns the max tokens limit.
     */
    public int maxTokens() {
        return maxTokens;
    }

    /**
     * Returns the log level string (e.g. "INFO", "DEBUG", "WARN").
     */
    public String logLevel() {
        return logLevel;
    }

    /**
     * Returns the OAuth refresh token, or null if not available.
     */
    public String refreshToken() {
        return refreshToken;
    }

    /**
     * Returns whether an API key is configured.
     */
    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Returns whether the configured key is an OAuth token.
     */
    public boolean isOAuthToken() {
        return apiKey != null && apiKey.startsWith("sk-ant-oat");
    }

    // -- internal --------------------------------------------------------

    /**
     * Attempts to load OAuth credentials from Claude CLI's credential storage.
     * Looks for refresh tokens in known Claude CLI locations.
     */
    private void loadClaudeCliCredentials() {
        // Claude CLI stores credentials in ~/.claude/.credentials or ~/.claude/credentials.json
        for (String fileName : new String[]{".credentials", "credentials.json"}) {
            var credFile = CLAUDE_CLI_DIR.resolve(fileName);
            if (Files.isRegularFile(credFile)) {
                try {
                    var mapper = new ObjectMapper();
                    var tree = mapper.readTree(credFile.toFile());

                    // Look for refresh token in the credentials
                    String rt = null;
                    if (tree.has("refreshToken")) {
                        rt = tree.get("refreshToken").asText(null);
                    } else if (tree.has("refresh_token")) {
                        rt = tree.get("refresh_token").asText(null);
                    }

                    if (rt != null && !rt.isBlank()) {
                        this.refreshToken = rt;
                        log.info("Loaded OAuth refresh token from Claude CLI: {}", credFile);
                        return;
                    }

                    // Also check if the file has an access token we can use
                    if (this.apiKey == null || this.apiKey.isBlank()) {
                        String at = null;
                        if (tree.has("accessToken")) {
                            at = tree.get("accessToken").asText(null);
                        } else if (tree.has("access_token")) {
                            at = tree.get("access_token").asText(null);
                        }
                        if (at != null && !at.isBlank()) {
                            this.apiKey = at;
                            log.info("Loaded OAuth access token from Claude CLI: {}", credFile);
                        }
                    }
                } catch (IOException e) {
                    log.debug("Could not read Claude CLI credentials from {}: {}", credFile, e.getMessage());
                }
            }
        }
    }

    private void mergeFromFile(Path configFile) {
        if (!Files.isRegularFile(configFile)) {
            return;
        }
        try {
            var mapper = new ObjectMapper();
            var fileConfig = mapper.readValue(configFile.toFile(), ConfigFileFormat.class);

            if (fileConfig.apiKey != null && !fileConfig.apiKey.isBlank()) {
                this.apiKey = fileConfig.apiKey;
            }
            if (fileConfig.refreshToken != null && !fileConfig.refreshToken.isBlank()) {
                this.refreshToken = fileConfig.refreshToken;
            }
            if (fileConfig.model != null && !fileConfig.model.isBlank()) {
                this.model = fileConfig.model;
            }
            if (fileConfig.maxTokens > 0) {
                this.maxTokens = fileConfig.maxTokens;
            }
            if (fileConfig.logLevel != null && !fileConfig.logLevel.isBlank()) {
                this.logLevel = fileConfig.logLevel;
            }

            log.debug("Loaded config from {}", configFile);
        } catch (IOException e) {
            log.warn("Failed to read config file {}: {}", configFile, e.getMessage());
        }
    }

    /**
     * JSON structure of the config file.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class ConfigFileFormat {
        public String apiKey;
        public String refreshToken;
        public String model;
        public int maxTokens;
        public String logLevel;
    }
}

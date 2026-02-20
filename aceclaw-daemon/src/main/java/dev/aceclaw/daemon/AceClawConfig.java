package dev.aceclaw.daemon;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 *   <li>{@code ACECLAW_PROFILE} → selects a named profile from config (applied after file load, before env overrides)</li>
 *   <li>{@code ACECLAW_PROVIDER} → provider</li>
 *   <li>{@code ACECLAW_BASE_URL} → baseUrl</li>
 *   <li>{@code ANTHROPIC_API_KEY} → apiKey</li>
 *   <li>{@code OPENAI_API_KEY} → apiKey (fallback for non-Anthropic providers)</li>
 *   <li>{@code ACECLAW_MODEL} → model</li>
 *   <li>{@code ACECLAW_LOG_LEVEL} → logLevel</li>
 *   <li>{@code BRAVE_SEARCH_API_KEY} → braveSearchApiKey</li>
 * </ul>
 */
public final class AceClawConfig {

    private static final Logger log = LoggerFactory.getLogger(AceClawConfig.class);

    private static final String CONFIG_FILE_NAME = "config.json";
    private static final Path GLOBAL_CONFIG_DIR = Path.of(System.getProperty("user.home"), ".aceclaw");

    // Default values
    private static final String DEFAULT_MODEL = "claude-sonnet-4-5-20250929";
    private static final int DEFAULT_MAX_TOKENS = 16384;
    private static final int DEFAULT_THINKING_BUDGET = 10240;
    private static final int DEFAULT_CONTEXT_WINDOW = 0;
    private static final String DEFAULT_LOG_LEVEL = "INFO";

    /** Claude CLI credentials directory. */
    private static final Path CLAUDE_CLI_DIR = Path.of(System.getProperty("user.home"), ".claude");

    private String provider;
    private String baseUrl;
    private String apiKey;
    private String refreshToken;
    private String model;
    private int maxTokens;
    private int thinkingBudget;
    private int contextWindowTokens;
    private String logLevel;
    private String braveSearchApiKey;
    private String permissionMode;
    private String defaultProfile;
    private Map<String, ConfigFileFormat> profiles;
    private Map<String, String> providerModels;
    private Map<String, List<HookMatcherFormat>> hooks;

    private AceClawConfig() {
        this.provider = "anthropic";
        this.model = null; // resolved dynamically via providerModels or LlmClientFactory defaults
        this.maxTokens = DEFAULT_MAX_TOKENS;
        this.thinkingBudget = DEFAULT_THINKING_BUDGET;
        this.contextWindowTokens = DEFAULT_CONTEXT_WINDOW;
        this.logLevel = DEFAULT_LOG_LEVEL;
        this.permissionMode = "normal";
        this.providerModels = new java.util.HashMap<>();
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

        // 3. Determine which profile to apply:
        //    ACECLAW_PROFILE > ACECLAW_PROVIDER (if matching profile exists) > defaultProfile
        var envProfile = System.getenv("ACECLAW_PROFILE");
        var envProvider = System.getenv("ACECLAW_PROVIDER");
        if (envProfile != null && !envProfile.isBlank()) {
            config.applyProfile(envProfile);
        } else if (envProvider != null && !envProvider.isBlank()
                && config.profiles != null && config.profiles.containsKey(envProvider.toLowerCase())) {
            config.applyProfile(envProvider.toLowerCase());
        } else if (config.defaultProfile != null && !config.defaultProfile.isBlank()) {
            config.applyProfile(config.defaultProfile);
        }

        // 4. Environment variables (highest precedence)
        if (envProvider != null && !envProvider.isBlank()) {
            config.provider = envProvider.toLowerCase();
        }
        var envBaseUrl = System.getenv("ACECLAW_BASE_URL");
        if (envBaseUrl != null && !envBaseUrl.isBlank()) {
            config.baseUrl = envBaseUrl;
        }
        var envApiKey = System.getenv("ANTHROPIC_API_KEY");
        if (envApiKey != null && !envApiKey.isBlank()) {
            config.apiKey = envApiKey;
        }
        // Fallback: check OPENAI_API_KEY for non-Anthropic providers
        if ((config.apiKey == null || config.apiKey.isBlank())
                && !"anthropic".equals(config.provider)) {
            var openaiKey = System.getenv("OPENAI_API_KEY");
            if (openaiKey != null && !openaiKey.isBlank()) {
                config.apiKey = openaiKey;
            }
        }
        var envModel = System.getenv("ACECLAW_MODEL");
        if (envModel != null && !envModel.isBlank()) {
            config.model = envModel;
        }
        var envLogLevel = System.getenv("ACECLAW_LOG_LEVEL");
        if (envLogLevel != null && !envLogLevel.isBlank()) {
            config.logLevel = envLogLevel;
        }
        var envBraveKey = System.getenv("BRAVE_SEARCH_API_KEY");
        if (envBraveKey != null && !envBraveKey.isBlank()) {
            config.braveSearchApiKey = envBraveKey;
        }
        var envPermMode = System.getenv("ACECLAW_PERMISSION_MODE");
        if (envPermMode != null && !envPermMode.isBlank()) {
            config.permissionMode = envPermMode.toLowerCase();
        }

        // 5. If apiKey is an OAuth token and no refresh token configured,
        //    try to load the refresh token from Claude CLI credentials
        if (config.apiKey != null && config.apiKey.startsWith("sk-ant-oat")
                && config.refreshToken == null) {
            config.loadClaudeCliCredentials();
        }

        log.info("Config loaded: provider={}, model={}, maxTokens={}, thinkingBudget={}, contextWindow={}, logLevel={}, baseUrl={}, apiKey={}, refreshToken={}",
                config.provider, config.model, config.maxTokens, config.thinkingBudget, config.contextWindowTokens, config.logLevel,
                config.baseUrl != null ? config.baseUrl : "(default)",
                config.apiKey != null ? config.apiKey.substring(0, Math.min(15, config.apiKey.length())) + "***" : "(not set)",
                config.refreshToken != null ? "***" : "(not set)");

        return config;
    }

    /**
     * Returns the LLM provider name (e.g. "anthropic", "openai", "groq", "ollama").
     */
    public String provider() {
        return provider;
    }

    /**
     * Returns the custom API base URL, or null to use the provider's default.
     */
    public String baseUrl() {
        return baseUrl;
    }

    /**
     * Returns the API key, or null if not configured.
     */
    public String apiKey() {
        return apiKey;
    }

    /**
     * Returns the explicitly configured model identifier, or null if not set.
     *
     * <p>Prefer {@link #resolvedModel()} which falls back to provider-specific defaults.
     */
    public String model() {
        return model;
    }

    /**
     * Resolves the model to use: explicit {@code model} config &gt; {@code providerModels[provider]}
     * &gt; {@link dev.aceclaw.llm.LlmClientFactory#getDefaultModel(String) factory default}.
     */
    public String resolvedModel() {
        if (model != null) {
            return model;
        }
        if (providerModels != null) {
            String pm = providerModels.get(provider);
            if (pm != null && !pm.isBlank()) {
                return pm;
            }
        }
        return dev.aceclaw.llm.LlmClientFactory.getDefaultModel(provider);
    }

    /**
     * Returns the max tokens limit.
     */
    public int maxTokens() {
        return maxTokens;
    }

    /**
     * Returns the thinking budget in tokens (0 = disabled).
     */
    public int thinkingBudget() {
        return thinkingBudget;
    }

    /**
     * Returns the context window size in tokens (e.g. 200,000 for Claude).
     */
    public int contextWindowTokens() {
        return contextWindowTokens;
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
     * Returns the Brave Search API key, or null if not configured.
     */
    public String braveSearchApiKey() {
        return braveSearchApiKey;
    }

    /**
     * Returns the permission mode: "normal", "accept-edits", "plan", or "auto-accept".
     *
     * <p>Defaults to "normal" (prompt for every dangerous operation).
     * Can be overridden via {@code ACECLAW_PERMISSION_MODE} env var or config file.
     *
     * @see dev.aceclaw.security.DefaultPermissionPolicy
     */
    public String permissionMode() {
        return permissionMode;
    }

    /**
     * Returns the hooks configuration map (event name to list of hook matchers).
     * Returns null if no hooks are configured.
     */
    public Map<String, List<HookMatcherFormat>> hooks() {
        return hooks;
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
     * Applies a named profile, overriding current config values with profile values.
     * Profile settings are merged (non-null values override, null values keep current).
     */
    private void applyProfile(String profileName) {
        if (profiles == null || profiles.isEmpty()) {
            log.warn("Profile '{}' requested but no profiles defined in config", profileName);
            return;
        }
        var profile = profiles.get(profileName);
        if (profile == null) {
            log.warn("Profile '{}' not found. Available profiles: {}", profileName, profiles.keySet());
            return;
        }
        log.info("Applying config profile: {}", profileName);
        mergeFromFormat(profile);
    }

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
            mergeFromFormat(fileConfig);

            // Collect defaultProfile, profiles, and providerModels from this file
            if (fileConfig.defaultProfile != null && !fileConfig.defaultProfile.isBlank()) {
                this.defaultProfile = fileConfig.defaultProfile;
            }
            if (fileConfig.profiles != null && !fileConfig.profiles.isEmpty()) {
                if (this.profiles == null) {
                    this.profiles = new java.util.HashMap<>();
                }
                this.profiles.putAll(fileConfig.profiles);
            }
            if (fileConfig.providerModels != null && !fileConfig.providerModels.isEmpty()) {
                this.providerModels.putAll(fileConfig.providerModels);
            }

            // Hooks: project config appends to global config per event type
            if (fileConfig.hooks != null && !fileConfig.hooks.isEmpty()) {
                if (this.hooks == null) {
                    this.hooks = new HashMap<>();
                }
                for (var hookEntry : fileConfig.hooks.entrySet()) {
                    this.hooks.computeIfAbsent(hookEntry.getKey(), _ -> new ArrayList<>())
                            .addAll(hookEntry.getValue());
                }
            }

            log.debug("Loaded config from {}", configFile);
        } catch (IOException e) {
            log.warn("Failed to read config file {}: {}", configFile, e.getMessage());
        }
    }

    private void mergeFromFormat(ConfigFileFormat fileConfig) {
        if (fileConfig.provider != null && !fileConfig.provider.isBlank()) {
            this.provider = fileConfig.provider.toLowerCase();
        }
        if (fileConfig.baseUrl != null && !fileConfig.baseUrl.isBlank()) {
            this.baseUrl = fileConfig.baseUrl;
        }
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
        if (fileConfig.thinkingBudget > 0) {
            this.thinkingBudget = fileConfig.thinkingBudget;
        }
        if (fileConfig.contextWindowTokens > 0) {
            this.contextWindowTokens = fileConfig.contextWindowTokens;
        }
        if (fileConfig.logLevel != null && !fileConfig.logLevel.isBlank()) {
            this.logLevel = fileConfig.logLevel;
        }
        if (fileConfig.braveSearchApiKey != null && !fileConfig.braveSearchApiKey.isBlank()) {
            this.braveSearchApiKey = fileConfig.braveSearchApiKey;
        }
        if (fileConfig.permissionMode != null && !fileConfig.permissionMode.isBlank()) {
            this.permissionMode = fileConfig.permissionMode.toLowerCase();
        }
    }

    /**
     * JSON structure of the config file.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class ConfigFileFormat {
        public String provider;
        public String baseUrl;
        public String apiKey;
        public String refreshToken;
        public String model;
        public int maxTokens;
        public int thinkingBudget;
        public int contextWindowTokens;
        public String logLevel;
        public String braveSearchApiKey;
        public String permissionMode;
        public String defaultProfile;
        public Map<String, ConfigFileFormat> profiles;
        public Map<String, String> providerModels;
        public Map<String, List<HookMatcherFormat>> hooks;
    }

    /**
     * JSON structure for a hook matcher entry in config.
     * <pre>{ "matcher": "bash", "hooks": [{ "type": "command", "command": "...", "timeout": 30 }] }</pre>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class HookMatcherFormat {
        public String matcher;
        public List<HookConfigFormat> hooks;
    }

    /**
     * JSON structure for a single hook config entry.
     * <pre>{ "type": "command", "command": "echo ok", "timeout": 60 }</pre>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class HookConfigFormat {
        public String type;
        public String command;
        public int timeout;
    }
}

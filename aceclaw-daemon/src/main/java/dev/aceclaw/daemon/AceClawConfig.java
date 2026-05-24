package dev.aceclaw.daemon;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.aceclaw.core.agent.AgentLoopConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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
 *   <li>{@code ACECLAW_MAX_TURNS} → maxTurns</li>
 *   <li>{@code ACECLAW_ADAPTIVE_CONTINUATION} → adaptiveContinuationEnabled</li>
 *   <li>{@code ACECLAW_ADAPTIVE_CONTINUATION_MAX_SEGMENTS} → adaptiveContinuationMaxSegments</li>
 *   <li>{@code ACECLAW_ADAPTIVE_CONTINUATION_NO_PROGRESS_THRESHOLD} → adaptiveContinuationNoProgressThreshold</li>
 *   <li>{@code ACECLAW_ADAPTIVE_CONTINUATION_MAX_TOTAL_TOKENS} → adaptiveContinuationMaxTotalTokens</li>
 *   <li>{@code ACECLAW_ADAPTIVE_CONTINUATION_MAX_WALL_CLOCK_SECONDS} → adaptiveContinuationMaxWallClockSeconds</li>
 *   <li>{@code ACECLAW_MAX_AGENT_TURNS} → maxAgentTurns (soft turn limit, default 200)</li>
 *   <li>{@code ACECLAW_MAX_AGENT_WALL_TIME_SEC} → maxAgentWallTimeSec (soft wall limit, default 1800)</li>
 *   <li>{@code ACECLAW_MAX_AGENT_HARD_TURNS} → maxAgentHardTurns (hard turn ceiling, default 0 = 3x soft)</li>
 *   <li>{@code ACECLAW_MAX_AGENT_HARD_WALL_TIME_SEC} → maxAgentHardWallTimeSec (hard wall ceiling, default 0 = 3x soft)</li>
 *   <li>{@code ACECLAW_LOG_LEVEL} → logLevel</li>
 *   <li>{@code ACECLAW_SKILL_AUTO_RELEASE_CANARY_DWELL_HOURS} → skillAutoReleaseCanaryDwellHours (minimum hours at CANARY before ACTIVE, default 24)</li>
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
    private static final int DEFAULT_MAX_TURNS = AgentLoopConfig.DEFAULT_MAX_ITERATIONS;
    // AdaptiveContinuation defaults moved to AdaptiveContinuationSettings.defaults()
    // (batch 2 of the AceClawConfig decomposition).
    private static final int DEFAULT_CONTEXT_WINDOW = 0;
    private static final String DEFAULT_LOG_LEVEL = "INFO";
    private static final boolean DEFAULT_BOOT_ENABLED = true;
    private static final int DEFAULT_BOOT_TIMEOUT_SECONDS = 120;
    private static final boolean DEFAULT_SCHEDULER_ENABLED = true;
    private static final int DEFAULT_SCHEDULER_TICK_SECONDS = 60;
    private static final boolean DEFAULT_HEARTBEAT_ENABLED = true;
    private static final boolean DEFAULT_PLANNER_ENABLED = true;
    /**
     * Default complexity score for triggering the planner. Restored
     * to 5 after live testing showed the planner's extra LLM call is
     * material cost, and lower thresholds either over-triggered (3:
     * every "extract", "refactor" fired the planner) or were a
     * borderline middle (4: still triggered on common 3+1 prompts
     * that didn't really need a separate planning pass).
     *
     * <p>The new model is "explicit by default": at 5, only
     * unambiguously compound work (two distinct +2/+3 signals)
     * triggers the heuristic. Everything else stays as plain ReAct
     * — which is what the agent loop is good at anyway. When the
     * operator wants planning on a borderline prompt, the
     * {@code /plan <prompt>} slash command (#467) bypasses the
     * heuristic entirely. That escape hatch is what makes the
     * conservative default acceptable: the user has explicit
     * control on the cases where the heuristic would have been
     * wrong either way.
     *
     * <p>See {@link ComplexityEstimator} for the score table.
     */
    private static final int DEFAULT_PLANNER_THRESHOLD = 5;
    private static final boolean DEFAULT_ADAPTIVE_REPLAN_ENABLED = true;
    // Candidate / anti-pattern-gate defaults moved to their respective
    // *Settings.defaults() factories (batch 3 of the AceClawConfig
    // decomposition).
    // SkillDraftValidation defaults moved to SkillDraftValidationSettings.defaults()
    // (batch 2 of the AceClawConfig decomposition).
    // Skill auto-release defaults live on SkillAutoReleaseSettings.defaults().
    // 13 individual DEFAULT_SKILL_AUTO_RELEASE_* constants used to live here —
    // grouped into the SkillAutoReleaseSettings record as part of the
    // AceClawConfig decomposition (batch 1).
    // Watchdog defaults moved to WatchdogSettings.defaults() (batch 4).
    // The "0 = derive from soft limit (3x)" convention on hard turns /
    // wall-time is preserved at the StreamingAgentHandler consumer.
    private static final boolean DEFAULT_DEFERRED_ACTION_ENABLED = true;
    private static final int DEFAULT_DEFERRED_ACTION_TICK_SECONDS = 5;
    /**
     * WebSocket bridge for browser dashboard (issue #431). Enabled by default since #446
     * <em>but only when the bind host is loopback</em> — the daemon now serves the
     * bundled dashboard on the same port, and the same-origin gate in {@link
     * WebSocketBridge} keeps cross-site browsers locked out without user config. The
     * loopback gate avoids a security regression for pre-existing configs that set
     * {@code webSocket.host = "0.0.0.0"} without ever setting {@code webSocket.enabled}
     * — flipping the default on for those users would suddenly expose the daemon to
     * everyone on their LAN. {@link #load} downgrades this default to {@code false}
     * when a non-loopback host is configured without an explicit enabled flag.
     */
    private static final boolean DEFAULT_WEBSOCKET_ENABLED = true;
    private static final int DEFAULT_WEBSOCKET_PORT = 3141;
    /** Bind only to localhost by default. Acceptance criterion of #431 (security). */
    private static final String DEFAULT_WEBSOCKET_HOST = "localhost";

    /** Claude CLI credentials directory. */
    private static final Path CLAUDE_CLI_DIR = Path.of(System.getProperty("user.home"), ".claude");
    /** Codex CLI credentials file for OpenAI Codex OAuth. */
    private static final Path CODEX_AUTH_FILE = Path.of(System.getProperty("user.home"), ".codex", "auth.json");
    private static final boolean DEFAULT_CONTEXT_1M = false;

    /**
     * Hosts treated as loopback for the purpose of safely defaulting the
     * WebSocket bridge on. Non-loopback hosts (e.g. {@code 0.0.0.0},
     * {@code 192.168.x.y}) require an explicit {@code webSocket.enabled = true}
     * — see {@link #DEFAULT_WEBSOCKET_ENABLED} javadoc.
     */
    private static boolean isLoopbackHost(String host) {
        return "localhost".equals(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "[::1]".equals(host);
    }

    private String provider;
    private String baseUrl;
    private String apiKey;
    private String refreshToken;
    private String model;
    private int maxTokens;
    private int thinkingBudget;
    private int maxTurns;
    /**
     * Adaptive continuation config — was 5 individual scalar fields, now
     * grouped under one {@link AdaptiveContinuationSettings} record
     * (batch 2 of the AceClawConfig decomposition).
     */
    private final AdaptiveContinuationSettings.Builder adaptiveContinuationBuilder =
            AdaptiveContinuationSettings.builder();
    private int contextWindowTokens;
    private String logLevel;
    private String braveSearchApiKey;
    private String permissionMode;
    /**
     * Whether the policy's structural sensitive-path denials are active.
     * Default {@code false} so an upgrade past #480 doesn't break workflows
     * that legitimately write {@code .env} templates, {@code .git/config}
     * entries, etc. Set to {@code true} via {@code security.denySensitivePaths}
     * in {@code ~/.aceclaw/config.json} to enable the hard-denial layer
     * (overrides every mode and prior approval — see
     * {@link dev.aceclaw.security.DefaultPermissionPolicy} for the rule set).
     */
    private boolean denySensitivePaths;
    private boolean bootEnabled;
    private int bootTimeoutSeconds;
    private boolean schedulerEnabled;
    private int schedulerTickSeconds;
    private boolean heartbeatEnabled;
    private String heartbeatActiveHours;
    private String defaultProfile;
    private Map<String, ConfigFileFormat> profiles;
    /**
     * Whether credentials (apiKey/refreshToken) were loaded from Claude CLI's
     * shared store (Keychain or ~/.claude/.credentials) rather than an explicit
     * profile/env value. When false, the Anthropic client must stay isolated
     * from that store: no read on 401, no write-back on refresh.
     */
    private boolean credentialsFromKeychain;
    /** Name of the profile applied during {@link #load}, or null if no profile was used. */
    private String activeProfileName;
    private Map<String, String> providerModels;
    private boolean plannerEnabled;
    private int plannerThreshold;
    private boolean adaptiveReplanEnabled;
    /**
     * Candidate / anti-pattern-gate config — was 9 individual scalar fields,
     * now grouped under three records (batch 3 of the AceClawConfig
     * decomposition). Held as mutable builders during {@link #load} so the
     * env-var + file-merge passes can update incrementally; the public
     * getters call {@code build()} on demand.
     */
    private final CandidatePromotionSettings.Builder candidatePromotionBuilder =
            CandidatePromotionSettings.builder();
    private final CandidateInjectionSettings.Builder candidateInjectionBuilder =
            CandidateInjectionSettings.builder();
    private final AntiPatternGateSettings.Builder antiPatternGateBuilder =
            AntiPatternGateSettings.builder();
    /**
     * Skill draft validation config — was 5 individual scalar fields, now
     * grouped under one {@link SkillDraftValidationSettings} record
     * (batch 2 of the AceClawConfig decomposition).
     */
    private final SkillDraftValidationSettings.Builder skillDraftValidationBuilder =
            SkillDraftValidationSettings.builder();
    /**
     * Skill auto-release config — was 12 individual scalar fields, now
     * grouped under one {@link SkillAutoReleaseSettings} record (batch 1 of
     * the AceClawConfig decomposition). Held as a mutable builder during
     * load() so env vars and file merges can update incrementally without
     * re-allocating; {@link #skillAutoRelease()} freezes it on demand.
     */
    private final SkillAutoReleaseSettings.Builder skillAutoReleaseBuilder =
            SkillAutoReleaseSettings.builder();
    /**
     * Watchdog config — was 6 individual scalar fields (4 agent budgets + 2
     * plan budgets), now grouped under one {@link WatchdogSettings} record
     * (batch 4 of the AceClawConfig decomposition).
     */
    private final WatchdogSettings.Builder watchdogBuilder = WatchdogSettings.builder();
    private boolean deferredActionEnabled;
    private int deferredActionTickSeconds;
    private boolean webSocketEnabled;
    /**
     * Tracks whether any config file explicitly set {@code webSocket.enabled}.
     * When false at the end of {@link #load}, the loopback-gating rule from
     * {@link #DEFAULT_WEBSOCKET_ENABLED}'s javadoc applies — non-loopback
     * hosts force enabled to false to avoid silently exposing the daemon to
     * a LAN that the user never opted into.
     */
    private boolean webSocketEnabledExplicit;
    private int webSocketPort;
    private String webSocketHost;
    /**
     * Allowlist of {@code Origin} headers permitted to open a WebSocket.
     * Default empty — browsers cannot connect until the user explicitly opts in
     * by listing the dashboard's origin (e.g. {@code http://localhost:5173}).
     * Tools that send no {@code Origin} (curl, Java HttpClient) are always
     * allowed because cross-site browser attacks cannot suppress the header.
     */
    private List<String> webSocketAllowedOrigins;
    private List<String> subAgentAutoApproveTools;
    private Map<String, List<HookMatcherFormat>> hooks;
    private boolean context1m;
    private List<String> extraAnthropicBetas;
    private int retryMaxRetries;
    private long retryInitialBackoffMs;
    private long retryMaxBackoffMs;
    private double retryJitterFactor;

    /**
     * Returns a default-valued config without touching {@code ~/.aceclaw/config.json}
     * or the environment. Package-private for unit tests that need a truly
     * blank starting point (the public {@link #load} would pick up whatever
     * apiKey the developer's machine has configured).
     */
    static AceClawConfig blankForTesting() {
        return new AceClawConfig();
    }

    private AceClawConfig() {
        this.provider = "anthropic";
        this.model = null; // resolved dynamically via providerModels or LlmClientFactory defaults
        this.maxTokens = DEFAULT_MAX_TOKENS;
        this.thinkingBudget = DEFAULT_THINKING_BUDGET;
        this.maxTurns = DEFAULT_MAX_TURNS;
        // adaptiveContinuation defaults seeded by AdaptiveContinuationSettings.builder()
        this.contextWindowTokens = DEFAULT_CONTEXT_WINDOW;
        this.logLevel = DEFAULT_LOG_LEVEL;
        this.permissionMode = "normal";
        this.denySensitivePaths = false;
        this.bootEnabled = DEFAULT_BOOT_ENABLED;
        this.bootTimeoutSeconds = DEFAULT_BOOT_TIMEOUT_SECONDS;
        this.schedulerEnabled = DEFAULT_SCHEDULER_ENABLED;
        this.schedulerTickSeconds = DEFAULT_SCHEDULER_TICK_SECONDS;
        this.heartbeatEnabled = DEFAULT_HEARTBEAT_ENABLED;
        this.plannerEnabled = DEFAULT_PLANNER_ENABLED;
        this.plannerThreshold = DEFAULT_PLANNER_THRESHOLD;
        this.adaptiveReplanEnabled = DEFAULT_ADAPTIVE_REPLAN_ENABLED;
        // Candidate / anti-pattern-gate defaults seeded by *Settings.builder()
        // skillDraftValidation defaults seeded by SkillDraftValidationSettings.builder()
        // skillAutoRelease defaults are seeded by SkillAutoReleaseSettings.builder()
        // at field-initialization time. 14 individual setter calls removed here.
        // Watchdog defaults seeded by WatchdogSettings.builder()
        this.deferredActionEnabled = DEFAULT_DEFERRED_ACTION_ENABLED;
        this.deferredActionTickSeconds = DEFAULT_DEFERRED_ACTION_TICK_SECONDS;
        this.webSocketEnabled = DEFAULT_WEBSOCKET_ENABLED;
        this.webSocketPort = DEFAULT_WEBSOCKET_PORT;
        this.webSocketHost = DEFAULT_WEBSOCKET_HOST;
        this.webSocketAllowedOrigins = List.of();
        this.subAgentAutoApproveTools = List.of();
        this.providerModels = new java.util.HashMap<>();
        this.context1m = DEFAULT_CONTEXT_1M;
        this.extraAnthropicBetas = List.of();
        this.retryMaxRetries = (int) dev.aceclaw.core.agent.RetryConfig.DEFAULT.maxRetries();
        this.retryInitialBackoffMs = dev.aceclaw.core.agent.RetryConfig.DEFAULT.initialBackoffMs();
        this.retryMaxBackoffMs = dev.aceclaw.core.agent.RetryConfig.DEFAULT.maxBackoffMs();
        this.retryJitterFactor = dev.aceclaw.core.agent.RetryConfig.DEFAULT.jitterFactor();
    }

    /**
     * Loads configuration from global and project config files, with env var overrides.
     *
     * @param projectPath the project working directory (may be null)
     * @return the merged configuration
     */
    public static AceClawConfig load(Path projectPath) {
        return load(projectPath, null);
    }

    /**
     * Loads configuration from global and project config files, with env var overrides
     * and an optional provider override used by foreground startup paths.
     *
     * @param projectPath the project working directory (may be null)
     * @param providerOverride optional provider override (may be null)
     * @return the merged configuration
     */
    public static AceClawConfig load(Path projectPath, String providerOverride) {
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
        //    ACECLAW_PROFILE > ACECLAW_PROVIDER (if matching profile exists)
        //    > defaultProfile (only when ACECLAW_PROVIDER is not explicitly set)
        var envProfile = System.getenv("ACECLAW_PROFILE");
        var envProvider = System.getenv("ACECLAW_PROVIDER");
        if (envProfile != null && !envProfile.isBlank()) {
            config.applyProfile(envProfile);
        } else if (envProvider != null && !envProvider.isBlank()
                && config.profiles != null && config.profiles.containsKey(envProvider.toLowerCase())) {
            config.applyProfile(envProvider.toLowerCase());
        } else if ((envProvider == null || envProvider.isBlank())
                && config.defaultProfile != null && !config.defaultProfile.isBlank()) {
            config.applyProfile(config.defaultProfile);
        }

        // 4. Environment variables (highest precedence)
        if (envProvider != null && !envProvider.isBlank()) {
            config.provider = envProvider.toLowerCase();
        }
        if (providerOverride != null && !providerOverride.isBlank()) {
            config.provider = providerOverride.trim().toLowerCase();
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
        var envMaxTurns = System.getenv("ACECLAW_MAX_TURNS");
        if (envMaxTurns != null && !envMaxTurns.isBlank()) {
            try {
                config.maxTurns = Math.max(1, Integer.parseInt(envMaxTurns));
            } catch (NumberFormatException e) {
                log.warn("Invalid ACECLAW_MAX_TURNS: {}", envMaxTurns);
            }
        }
        // -- Watchdog env vars (batch 4) -------------------------------------
        applyEnvInt("ACECLAW_MAX_AGENT_TURNS",
                v -> config.watchdogBuilder.agentTurns(Math.max(0, v)));
        applyEnvInt("ACECLAW_MAX_AGENT_WALL_TIME_SEC",
                v -> config.watchdogBuilder.agentWallTimeSec(Math.max(0, v)));
        applyEnvInt("ACECLAW_MAX_AGENT_HARD_TURNS",
                v -> config.watchdogBuilder.agentHardTurns(Math.max(0, v)));
        applyEnvInt("ACECLAW_MAX_AGENT_HARD_WALL_TIME_SEC",
                v -> config.watchdogBuilder.agentHardWallTimeSec(Math.max(0, v)));
        applyEnvInt("ACECLAW_MAX_PLAN_STEP_WALL_TIME_SEC",
                v -> config.watchdogBuilder.planStepWallTimeSec(Math.max(0, v)));
        applyEnvInt("ACECLAW_MAX_PLAN_TOTAL_WALL_TIME_SEC",
                v -> config.watchdogBuilder.planTotalWallTimeSec(Math.max(0, v)));
        // -- Adaptive continuation env vars (batch 2) ------------------------
        applyEnvBoolean("ACECLAW_ADAPTIVE_CONTINUATION",
                v -> config.adaptiveContinuationBuilder.enabled(v));
        applyEnvInt("ACECLAW_ADAPTIVE_CONTINUATION_MAX_SEGMENTS",
                v -> config.adaptiveContinuationBuilder.maxSegments(Math.max(1, v)));
        applyEnvInt("ACECLAW_ADAPTIVE_CONTINUATION_NO_PROGRESS_THRESHOLD",
                v -> config.adaptiveContinuationBuilder.noProgressThreshold(Math.max(1, v)));
        applyEnvInt("ACECLAW_ADAPTIVE_CONTINUATION_MAX_TOTAL_TOKENS",
                v -> config.adaptiveContinuationBuilder.maxTotalTokens(Math.max(0, v)));
        applyEnvInt("ACECLAW_ADAPTIVE_CONTINUATION_MAX_WALL_CLOCK_SECONDS",
                v -> config.adaptiveContinuationBuilder.maxWallClockSeconds(Math.max(0, v)));
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
        // -- Candidate + anti-pattern-gate env vars (batch 3) ----------------
        applyEnvBoolean("ACECLAW_CANDIDATE_INJECTION",
                v -> config.candidateInjectionBuilder.enabled(v));
        applyEnvBoolean("ACECLAW_CANDIDATE_PROMOTION",
                v -> config.candidatePromotionBuilder.enabled(v));
        applyEnvInt("ACECLAW_CANDIDATE_INJECTION_MAX_TOKENS",
                v -> config.candidateInjectionBuilder.maxTokens(Math.max(0, v)));
        applyEnvInt("ACECLAW_ANTI_PATTERN_GATE_MIN_BLOCKED_BEFORE_ROLLBACK",
                v -> config.antiPatternGateBuilder.minBlockedBeforeRollback(Math.max(1, v)));
        applyEnvDouble("ACECLAW_ANTI_PATTERN_GATE_MAX_FALSE_POSITIVE_RATE",
                v -> config.antiPatternGateBuilder.maxFalsePositiveRate(v));
        // -- Skill draft validation env vars (batch 2) -----------------------
        applyEnvBoolean("ACECLAW_SKILL_DRAFT_VALIDATION",
                v -> config.skillDraftValidationBuilder.enabled(v));
        applyEnvBoolean("ACECLAW_SKILL_DRAFT_VALIDATION_STRICT_MODE",
                v -> config.skillDraftValidationBuilder.strictMode(v));
        applyEnvBoolean("ACECLAW_SKILL_DRAFT_VALIDATION_REPLAY_REQUIRED",
                v -> config.skillDraftValidationBuilder.replayRequired(v));
        // ACECLAW_REPLAY_REPORT_PATH is a String — no parse step.
        var envReplayReportPath = System.getenv("ACECLAW_REPLAY_REPORT_PATH");
        if (envReplayReportPath != null && !envReplayReportPath.isBlank()) {
            config.skillDraftValidationBuilder.replayReportPath(envReplayReportPath);
        }
        applyEnvDouble("ACECLAW_SKILL_DRAFT_VALIDATION_MAX_TOKEN_ESTIMATION_ERROR_RATIO",
                v -> config.skillDraftValidationBuilder.maxTokenEstimationErrorRatio(v));
        // -- Skill auto-release env vars -------------------------------------
        // Was 12 nearly-identical try/catch blocks (130 LoC) updating 12
        // individual scalar fields on `config`. Each one now folds into a
        // single builder.xxx(parsed) call — the builder handles null/range
        // validation centrally.
        applyEnvBoolean("ACECLAW_SKILL_AUTO_RELEASE",
                v -> config.skillAutoReleaseBuilder.enabled(v));
        applyEnvDouble("ACECLAW_SKILL_AUTO_RELEASE_MIN_SCORE",
                v -> config.skillAutoReleaseBuilder.minCandidateScore(v));
        applyEnvInt("ACECLAW_SKILL_AUTO_RELEASE_MIN_EVIDENCE",
                v -> config.skillAutoReleaseBuilder.minEvidenceCount(Math.max(1, v)));
        applyEnvInt("ACECLAW_SKILL_AUTO_RELEASE_CANARY_MIN_ATTEMPTS",
                v -> config.skillAutoReleaseBuilder.canaryMinAttempts(Math.max(0, v)));
        applyEnvDouble("ACECLAW_SKILL_AUTO_RELEASE_CANARY_MAX_FAILURE_RATE",
                v -> config.skillAutoReleaseBuilder.canaryMaxFailureRate(v));
        applyEnvDouble("ACECLAW_SKILL_AUTO_RELEASE_CANARY_MAX_TIMEOUT_RATE",
                v -> config.skillAutoReleaseBuilder.canaryMaxTimeoutRate(v));
        applyEnvDouble("ACECLAW_SKILL_AUTO_RELEASE_CANARY_MAX_PERMISSION_BLOCK_RATE",
                v -> config.skillAutoReleaseBuilder.canaryMaxPermissionRate(v));
        // Legacy env alias: ACTIVE_MAX_FAILURE_RATE → rollbackFailure.
        // Preserved for backward compat with pre-#467 docs.
        applyEnvDouble("ACECLAW_SKILL_AUTO_RELEASE_ACTIVE_MAX_FAILURE_RATE",
                v -> config.skillAutoReleaseBuilder.applyLegacyActiveMaxFailureRate(v));
        applyEnvDouble("ACECLAW_SKILL_AUTO_RELEASE_ROLLBACK_MAX_FAILURE_RATE",
                v -> config.skillAutoReleaseBuilder.rollbackMaxFailureRate(v));
        applyEnvDouble("ACECLAW_SKILL_AUTO_RELEASE_ROLLBACK_MAX_TIMEOUT_RATE",
                v -> config.skillAutoReleaseBuilder.rollbackMaxTimeoutRate(v));
        applyEnvDouble("ACECLAW_SKILL_AUTO_RELEASE_ROLLBACK_MAX_PERMISSION_BLOCK_RATE",
                v -> config.skillAutoReleaseBuilder.rollbackMaxPermissionRate(v));
        applyEnvInt("ACECLAW_SKILL_AUTO_RELEASE_HEALTH_LOOKBACK_HOURS",
                v -> config.skillAutoReleaseBuilder.healthLookbackHours(Math.max(1, v)));
        var envSkillAutoReleaseCanaryDwellHours = System.getenv("ACECLAW_SKILL_AUTO_RELEASE_CANARY_DWELL_HOURS");
        if (envSkillAutoReleaseCanaryDwellHours != null && !envSkillAutoReleaseCanaryDwellHours.isBlank()) {
            try {
                config.skillAutoReleaseBuilder.canaryDwellHours(
                        Math.max(0, Integer.parseInt(envSkillAutoReleaseCanaryDwellHours)));
            } catch (NumberFormatException e) {
                log.warn("Invalid ACECLAW_SKILL_AUTO_RELEASE_CANARY_DWELL_HOURS: {}",
                        envSkillAutoReleaseCanaryDwellHours);
            }
        }

        // 5. Provider-specific credential discovery fallback
        if ((config.apiKey == null || config.apiKey.isBlank())
                && "openai-codex".equals(config.provider)) {
            config.loadCodexAuthToken();
        }

        // 6. For Anthropic provider: try Keychain/credential file discovery
        //    ONLY when the profile/env did not supply an apiKey. An explicit
        //    apiKey (OAuth or standard) pins the profile to its own account —
        //    we must never silently replace it with Claude CLI's stored token,
        //    which would cross accounts (e.g., company profile using personal
        //    Max credentials) and write the refreshed token back into the
        //    shared Keychain, corrupting the other account's Claude CLI login.
        if ("anthropic".equals(config.provider)
                && (config.apiKey == null || config.apiKey.isBlank())) {
            config.loadClaudeCliCredentialsWithKeychain();
            config.credentialsFromKeychain =
                    config.apiKey != null && !config.apiKey.isBlank();
        } else if (!"anthropic".equals(config.provider)
                && config.apiKey != null && config.apiKey.startsWith("sk-ant-oat")
                && config.refreshToken == null) {
            // Non-Anthropic provider with OAuth token still needs refresh token
            config.loadClaudeCliCredentials();
        }

        // Loopback gate (#446): the new default-on for {@code webSocket.enabled}
        // is only safe on a loopback bind. Pre-existing configs that set
        // {@code webSocket.host = "0.0.0.0"} but never set
        // {@code webSocket.enabled} would otherwise be silently exposed to
        // their LAN. Apply the gate after all merge/profile/env passes so
        // the final resolved host wins, and only when no file in the chain
        // explicitly opted in.
        if (!config.webSocketEnabledExplicit
                && config.webSocketEnabled
                && !isLoopbackHost(config.webSocketHost)) {
            log.info(
                    "webSocket.host = '{}' is non-loopback; defaulting webSocket.enabled to false. "
                            + "Set webSocket.enabled = true in config.json to opt in explicitly.",
                    config.webSocketHost);
            config.webSocketEnabled = false;
        }

        var ac = config.adaptiveContinuation();
        log.info("Config loaded: provider={}, model={}, maxTokens={}, thinkingBudget={}, maxTurns={}, adaptiveContinuationEnabled={}, adaptiveMaxSegments={}, contextWindow={}, logLevel={}, baseUrl={}, apiKey={}, refreshToken={}",
                config.provider, config.model, config.maxTokens, config.thinkingBudget, config.maxTurns,
                ac.enabled(), ac.maxSegments(),
                config.contextWindowTokens, config.logLevel,
                config.baseUrl != null ? config.baseUrl : "(default)",
                config.apiKey != null ? "(set)" : "(not set)",
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
     * Returns the max ReAct iterations per turn.
     */
    public int maxTurns() {
        return maxTurns;
    }

    /**
     * Returns the adaptive-continuation config — the feature gate plus the
     * 4 budget scalars the agent loop reads. Replaces 5 individual getters
     * as part of batch 2 of the AceClawConfig decomposition.
     */
    public AdaptiveContinuationSettings adaptiveContinuation() {
        return adaptiveContinuationBuilder.build();
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
     * Returns true when the current apiKey/refreshToken were loaded from Claude
     * CLI's shared store (Keychain or ~/.claude/.credentials). False when the
     * credentials came from an explicit profile / env var, in which case the
     * Anthropic client must remain isolated from that shared store.
     */
    public boolean credentialsFromKeychain() {
        return credentialsFromKeychain;
    }

    /** Returns the profile name applied during load, or null if no profile was active. */
    public String activeProfileName() {
        return activeProfileName;
    }

    /**
     * Atomically updates a profile's {@code apiKey} and {@code refreshToken} in the
     * global config file ({@code ~/.aceclaw/config.json}).  Called after a successful
     * isolated OAuth refresh so the rotated tokens survive a daemon restart.
     *
     * <p>No-op (with a warning) if the profile does not exist in the file.
     */
    public static void persistProfileCredentials(String profileName,
                                                  String newAccessToken,
                                                  String newRefreshToken) {
        persistProfileCredentials(profileName, newAccessToken, newRefreshToken,
                GLOBAL_CONFIG_DIR.resolve(CONFIG_FILE_NAME));
    }

    /** Package-private overload that accepts an explicit config file path for testing. */
    static void persistProfileCredentials(String profileName,
                                          String newAccessToken,
                                          String newRefreshToken,
                                          Path configFile) {
        if (profileName == null || profileName.isBlank()) return;
        try {
            var mapper = new ObjectMapper();
            ObjectNode root;
            if (Files.isRegularFile(configFile)) {
                var tree = mapper.readTree(configFile.toFile());
                root = tree instanceof ObjectNode on ? on : mapper.createObjectNode();
            } else {
                log.warn("persistProfileCredentials: config file not found at {}", configFile);
                return;
            }
            var profilesNode = root.path("profiles");
            if (!profilesNode.isObject() || !profilesNode.has(profileName)) {
                log.warn("persistProfileCredentials: profile '{}' not found in {}", profileName, configFile);
                return;
            }
            var profileNode = (ObjectNode) profilesNode.get(profileName);
            profileNode.put("apiKey", newAccessToken);
            if (newRefreshToken != null) {
                profileNode.put("refreshToken", newRefreshToken);
            }
            Path tmp = configFile.resolveSibling(configFile.getFileName() + ".tmp");
            Files.writeString(tmp,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.info("Persisted refreshed credentials for profile '{}' to {}", profileName, configFile);
        } catch (Exception e) {
            log.warn("Failed to persist credentials for profile '{}': {}", profileName, e.getMessage());
        }
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
     * Returns whether the policy's structural sensitive-path denials are
     * enabled. Default {@code false} (opt-in).
     *
     * <p>Wired from {@code security.denySensitivePaths} in
     * {@code ~/.aceclaw/config.json} or {@code {project}/.aceclaw/config.json}.
     * When {@code true}, writes/deletes targeting {@code .env*}, {@code .ssh/*},
     * {@code .git/config}, {@code credentials.json}, anything under
     * {@code /etc/}, etc. are hard-denied regardless of permission mode or
     * prior session approval. See
     * {@link dev.aceclaw.security.DefaultPermissionPolicy} for the full rule
     * set and rationale.
     */
    public boolean denySensitivePaths() {
        return denySensitivePaths;
    }

    /**
     * Persists candidate injection settings to config.json.
     *
     * @param projectPath project root for project-scoped persistence (required when scope=project)
     * @param enabled candidate injection enabled flag to persist
     * @param maxTokens optional token budget to persist (nullable)
     * @param scope "project" or "global" (defaults to project when null/blank)
     * @return path to the config file written
     */
    public static Path persistCandidateInjectionSettings(Path projectPath,
                                                         boolean enabled,
                                                         Integer maxTokens,
                                                         String scope) throws IOException {
        String normalizedScope = (scope == null || scope.isBlank())
                ? "project" : scope.toLowerCase();
        Path configFile;
        if ("global".equals(normalizedScope)) {
            configFile = GLOBAL_CONFIG_DIR.resolve(CONFIG_FILE_NAME);
        } else if ("project".equals(normalizedScope)) {
            if (projectPath == null) {
                throw new IllegalArgumentException("projectPath is required for project scope");
            }
            configFile = projectPath.resolve(".aceclaw").resolve(CONFIG_FILE_NAME);
        } else {
            throw new IllegalArgumentException("Unsupported scope: " + scope);
        }

        Files.createDirectories(configFile.getParent());
        var mapper = new ObjectMapper();
        ObjectNode root;
        if (Files.isRegularFile(configFile)) {
            var tree = mapper.readTree(configFile.toFile());
            root = tree instanceof ObjectNode ? (ObjectNode) tree : mapper.createObjectNode();
        } else {
            root = mapper.createObjectNode();
        }
        root.put("candidateInjectionEnabled", enabled);
        if (maxTokens != null) {
            root.put("candidateInjectionMaxTokens", Math.max(0, maxTokens));
        }

        Path tmp = configFile.resolveSibling(configFile.getFileName() + ".tmp");
        Files.writeString(tmp, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(tmp, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return configFile;
    }

    /**
     * Returns whether BOOT.md execution is enabled at daemon startup.
     * Defaults to true.
     */
    public boolean bootEnabled() {
        return bootEnabled;
    }

    /**
     * Returns the maximum time in seconds for BOOT.md execution.
     * Defaults to 120.
     */
    public int bootTimeoutSeconds() {
        return bootTimeoutSeconds;
    }

    /**
     * Returns whether the cron scheduler is enabled at daemon startup.
     * Defaults to true.
     */
    public boolean schedulerEnabled() {
        return schedulerEnabled;
    }

    /**
     * Returns the cron scheduler tick interval in seconds.
     * Defaults to 60.
     */
    public int schedulerTickSeconds() {
        return schedulerTickSeconds;
    }

    /**
     * Returns whether heartbeat tasks from HEARTBEAT.md are enabled.
     * Defaults to true.
     */
    public boolean heartbeatEnabled() {
        return heartbeatEnabled;
    }

    /**
     * Returns the active hours window for heartbeat tasks in "HH:mm-HH:mm" format.
     * Returns null if heartbeat tasks should always be active.
     */
    public String heartbeatActiveHours() {
        return heartbeatActiveHours;
    }

    /**
     * Returns whether the task planner is enabled.
     * Defaults to true.
     */
    public boolean plannerEnabled() {
        return plannerEnabled;
    }

    /**
     * Returns the complexity score threshold for triggering planning.
     * Defaults to 5.
     */
    public int plannerThreshold() {
        return plannerThreshold;
    }

    /**
     * Returns whether adaptive replanning is enabled.
     * When true, failed plan steps trigger LLM-based replanning instead of immediate failure.
     * Defaults to true.
     */
    public boolean adaptiveReplanEnabled() {
        return adaptiveReplanEnabled;
    }

    /**
     * Returns the candidate-promotion config — the feature gate plus the
     * 3 promotion-threshold scalars (minEvidence, minScore, maxFailureRate)
     * that feed the {@code CandidateStateMachine.Config} constructor.
     * Batch 3 of the AceClawConfig decomposition.
     */
    public CandidatePromotionSettings candidatePromotion() {
        return candidatePromotionBuilder.build();
    }

    /**
     * Returns the candidate-injection config — the feature gate plus the
     * 2 per-prompt budget scalars (maxCount, maxTokens). Batch 3 of the
     * AceClawConfig decomposition.
     */
    public CandidateInjectionSettings candidateInjection() {
        return candidateInjectionBuilder.build();
    }

    /**
     * Returns the anti-pattern-gate config — the 2 rollback-threshold
     * scalars (minBlockedBeforeRollback, maxFalsePositiveRate). No
     * {@code enabled} flag; the gate is always on. Batch 3 of the
     * AceClawConfig decomposition.
     */
    public AntiPatternGateSettings antiPatternGate() {
        return antiPatternGateBuilder.build();
    }

    /**
     * Returns the skill-draft-validation config — the feature gate plus the
     * 4 scalars the {@code ValidationGateEngine} consumes. Replaces 5
     * individual getters as part of batch 2 of the AceClawConfig
     * decomposition.
     */
    public SkillDraftValidationSettings skillDraftValidation() {
        return skillDraftValidationBuilder.build();
    }

    /**
     * Returns the skill auto-release config — the feature gate plus the 11
     * canary / rollback tuning scalars the {@code AutoReleaseController}
     * consumes. Replaces 13 individual getters as the first pilot of the
     * AceClawConfig decomposition: callers that needed all the values
     * (single call site in {@code AceClawDaemon}) now receive one record
     * argument instead of passing 11 scalars by hand.
     */
    public SkillAutoReleaseSettings skillAutoRelease() {
        return skillAutoReleaseBuilder.build();
    }

    /**
     * Returns whether the deferred action scheduler is enabled.
     * Defaults to true.
     */
    public boolean deferredActionEnabled() {
        return deferredActionEnabled;
    }

    /**
     * Returns the deferred action scheduler tick interval in seconds.
     * Defaults to 5.
     */
    public int deferredActionTickSeconds() {
        return deferredActionTickSeconds;
    }

    /**
     * Returns whether the browser-facing WebSocket bridge is enabled.
     * Defaults to {@code true} (since #446) — the daemon serves the bundled
     * dashboard on the same port and same-origin checks block cross-site
     * browsers. Override to {@code false} via {@code webSocket.enabled} in
     * config.json to disable entirely.
     */
    public boolean webSocketEnabled() {
        return webSocketEnabled;
    }

    /**
     * Returns the port the WebSocket bridge binds to. Defaults to 3141.
     */
    public int webSocketPort() {
        return webSocketPort;
    }

    /**
     * Returns the host the WebSocket bridge binds to. Defaults to {@code localhost}
     * for security; never expose without an authentication layer in front.
     */
    public String webSocketHost() {
        return webSocketHost;
    }

    /**
     * Returns the allowlist of browser {@code Origin} headers permitted to open
     * a WebSocket connection. Empty list = no browser may connect; tools with
     * no {@code Origin} header are always allowed.
     */
    public List<String> webSocketAllowedOrigins() {
        return webSocketAllowedOrigins;
    }

    /**
     * Returns extra tool names to auto-approve for sub-agents, configured via
     * {@code subAgentAutoApproveTools} in config.json. These are merged with
     * the built-in read-only tool whitelist.
     */
    public List<String> subAgentAutoApproveTools() {
        return subAgentAutoApproveTools;
    }

    /**
     * Returns the watchdog config — agent + plan turn/wall-time budgets
     * (6 scalars, see {@link WatchdogSettings}). Replaces 6 individual
     * getters as part of batch 4 of the AceClawConfig decomposition.
     */
    public WatchdogSettings watchdog() {
        return watchdogBuilder.build();
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
        this.activeProfileName = profileName;
    }

    /**
     * Returns whether 1M context window beta is enabled.
     */
    public boolean context1m() {
        return context1m;
    }

    /**
     * Returns extra Anthropic beta flags from config.
     */
    public List<String> extraAnthropicBetas() {
        return extraAnthropicBetas;
    }

    /**
     * Returns the retry configuration assembled from config fields.
     */
    public dev.aceclaw.core.agent.RetryConfig retryConfig() {
        return new dev.aceclaw.core.agent.RetryConfig(
                retryMaxRetries, retryInitialBackoffMs, retryMaxBackoffMs, retryJitterFactor);
    }

    /**
     * Loads Claude CLI credentials with Keychain priority (macOS).
     * Falls back to credential files if Keychain is unavailable.
     */
    private void loadClaudeCliCredentialsWithKeychain() {
        var cred = dev.aceclaw.llm.anthropic.KeychainCredentialReader.read();
        if (cred != null) {
            applyKeychainCredential(cred);
            return;
        }
        // Fall back to legacy file-based loading
        if (this.apiKey != null && this.apiKey.startsWith("sk-ant-oat") && this.refreshToken == null) {
            loadClaudeCliCredentials();
        }
    }

    /**
     * Applies a Keychain credential to this config — only when the profile/env
     * did not already supply an apiKey. An explicit apiKey (OAuth or standard)
     * is treated as an authoritative pin to that account and is never
     * overwritten from Claude CLI's shared store.
     *
     * <p>Package-private for testing.
     */
    void applyKeychainCredential(dev.aceclaw.llm.anthropic.KeychainCredentialReader.Credential cred) {
        if (this.apiKey != null && !this.apiKey.isBlank()) {
            // Profile supplied its own apiKey — do not cross-contaminate from
            // Claude CLI's shared credential store. The refresh token also stays
            // as supplied by the profile (possibly null, in which case expired
            // OAuth tokens simply cannot be refreshed).
            return;
        }
        this.apiKey = cred.accessToken();
        if (!cred.isExpired()) {
            log.info("Loaded OAuth access token from Claude CLI credentials (Keychain)");
        } else {
            log.info("Loaded expired OAuth access token from Keychain, will refresh before first request");
        }
        if (cred.refreshToken() != null) {
            this.refreshToken = cred.refreshToken();
            log.info("Loaded OAuth refresh token from Claude CLI credentials");
        }
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

    /**
     * Attempts to load OpenAI Codex access token from Codex CLI credential file.
     * Supports both modern {@code tokens.access_token} and legacy {@code OPENAI_API_KEY}.
     */
    private void loadCodexAuthToken() {
        if (!Files.isRegularFile(CODEX_AUTH_FILE)) {
            return;
        }
        try {
            var mapper = new ObjectMapper();
            var tree = mapper.readTree(CODEX_AUTH_FILE.toFile());

            String token = null;
            var tokens = tree.path("tokens");
            if (tokens.has("access_token")) {
                token = tokens.get("access_token").asText(null);
            }
            if ((token == null || token.isBlank()) && tree.has("OPENAI_API_KEY")) {
                token = tree.get("OPENAI_API_KEY").asText(null);
            }
            if (token != null && !token.isBlank()) {
                this.apiKey = token;
                log.info("Loaded OpenAI Codex access token from {}", CODEX_AUTH_FILE);
            }
        } catch (IOException e) {
            log.debug("Could not read Codex auth file {}: {}", CODEX_AUTH_FILE, e.getMessage());
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
                    var hooksForEvent = hookEntry.getValue();
                    if (hooksForEvent == null || hooksForEvent.isEmpty()) {
                        continue;
                    }
                    this.hooks.computeIfAbsent(hookEntry.getKey(), _ -> new ArrayList<>())
                            .addAll(hooksForEvent);
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
        if (fileConfig.maxTurns > 0) {
            this.maxTurns = fileConfig.maxTurns;
        }
        // -- Adaptive continuation file overrides (batch 2) -----------------
        adaptiveContinuationBuilder
                .enabled(fileConfig.adaptiveContinuationEnabled)
                .maxSegments(fileConfig.adaptiveContinuationMaxSegments)
                .noProgressThreshold(fileConfig.adaptiveContinuationNoProgressThreshold)
                .maxTotalTokens(fileConfig.adaptiveContinuationMaxTotalTokens)
                .maxWallClockSeconds(fileConfig.adaptiveContinuationMaxWallClockSeconds);
        if (fileConfig.contextWindowTokens > 0) {
            this.contextWindowTokens = fileConfig.contextWindowTokens;
        }
        if (fileConfig.logLevel != null && !fileConfig.logLevel.isBlank()) {
            this.logLevel = fileConfig.logLevel;
        }
        if (fileConfig.braveSearchApiKey != null && !fileConfig.braveSearchApiKey.isBlank()) {
            this.braveSearchApiKey = fileConfig.braveSearchApiKey;
        }
        if (fileConfig.security != null && fileConfig.security.denySensitivePaths != null) {
            this.denySensitivePaths = fileConfig.security.denySensitivePaths;
        }
        if (fileConfig.permissionMode != null && !fileConfig.permissionMode.isBlank()) {
            this.permissionMode = fileConfig.permissionMode.toLowerCase();
        }
        if (fileConfig.bootEnabled != null) {
            this.bootEnabled = fileConfig.bootEnabled;
        }
        if (fileConfig.bootTimeoutSeconds > 0) {
            this.bootTimeoutSeconds = fileConfig.bootTimeoutSeconds;
        }
        if (fileConfig.schedulerEnabled != null) {
            this.schedulerEnabled = fileConfig.schedulerEnabled;
        }
        if (fileConfig.schedulerTickSeconds > 0) {
            this.schedulerTickSeconds = fileConfig.schedulerTickSeconds;
        }
        if (fileConfig.heartbeatEnabled != null) {
            this.heartbeatEnabled = fileConfig.heartbeatEnabled;
        }
        if (fileConfig.heartbeatActiveHours != null) {
            // Blank value explicitly clears inherited activeHours (= always active)
            this.heartbeatActiveHours = fileConfig.heartbeatActiveHours.isBlank()
                    ? null : fileConfig.heartbeatActiveHours;
        }
        if (fileConfig.plannerEnabled != null) {
            this.plannerEnabled = fileConfig.plannerEnabled;
        }
        if (fileConfig.plannerThreshold != null) {
            this.plannerThreshold = fileConfig.plannerThreshold;
        }
        if (fileConfig.adaptiveReplanEnabled != null) {
            this.adaptiveReplanEnabled = fileConfig.adaptiveReplanEnabled;
        }
        // -- Candidate file overrides (batch 3) ------------------------------
        candidatePromotionBuilder
                .enabled(fileConfig.candidatePromotionEnabled)
                .minEvidence(fileConfig.candidatePromotionMinEvidence)
                .minScore(fileConfig.candidatePromotionMinScore)
                .maxFailureRate(fileConfig.candidatePromotionMaxFailureRate);
        candidateInjectionBuilder
                .enabled(fileConfig.candidateInjectionEnabled)
                .maxCount(fileConfig.candidateInjectionMaxCount);
        // Token budget priority: explicit maxTokens wins; otherwise fall back
        // to the legacy maxChars (char-to-token approximation 4:1). Preserves
        // the pre-decomp behaviour where setting maxTokens to a valid value
        // takes precedence over a stale maxChars, but a config that ONLY has
        // the older maxChars key still works.
        if (fileConfig.candidateInjectionMaxTokens != null && fileConfig.candidateInjectionMaxTokens >= 0) {
            candidateInjectionBuilder.maxTokens(fileConfig.candidateInjectionMaxTokens);
        } else if (fileConfig.candidateInjectionMaxChars != null && fileConfig.candidateInjectionMaxChars >= 0) {
            candidateInjectionBuilder.maxTokens(Math.max(0, fileConfig.candidateInjectionMaxChars / 4));
        }

        // -- Anti-pattern gate file overrides (batch 3) ----------------------
        antiPatternGateBuilder
                .minBlockedBeforeRollback(fileConfig.antiPatternGateMinBlockedBeforeRollback)
                .maxFalsePositiveRate(fileConfig.antiPatternGateMaxFalsePositiveRate);
        // -- Skill draft validation file overrides (batch 2) -----------------
        skillDraftValidationBuilder
                .enabled(fileConfig.skillDraftValidationEnabled)
                .strictMode(fileConfig.skillDraftValidationStrictMode)
                .replayRequired(fileConfig.skillDraftValidationReplayRequired)
                .replayReportPath(fileConfig.skillDraftValidationReplayReport)
                .maxTokenEstimationErrorRatio(fileConfig.skillDraftValidationMaxTokenEstimationErrorRatio);
        // -- Skill auto-release file overrides -------------------------------
        // Was 13 individual nullable-then-set blocks (~55 LoC) updating 13
        // scalar fields. Now folds into builder setters that each validate
        // null + range centrally. The legacy `activeMaxFailureRate` key is
        // handled by applyLegacyActiveMaxFailureRate (aliases onto
        // rollbackMaxFailureRate, matching the previous behaviour).
        skillAutoReleaseBuilder
                .enabled(fileConfig.skillAutoReleaseEnabled)
                .minCandidateScore(fileConfig.skillAutoReleaseMinCandidateScore)
                .minEvidenceCount(fileConfig.skillAutoReleaseMinEvidence)
                .canaryMinAttempts(fileConfig.skillAutoReleaseCanaryMinAttempts)
                .canaryMaxFailureRate(fileConfig.skillAutoReleaseCanaryMaxFailureRate)
                .canaryMaxTimeoutRate(fileConfig.skillAutoReleaseCanaryMaxTimeoutRate)
                .canaryMaxPermissionRate(fileConfig.skillAutoReleaseCanaryMaxPermissionBlockRate)
                .applyLegacyActiveMaxFailureRate(fileConfig.skillAutoReleaseActiveMaxFailureRate)
                .rollbackMaxFailureRate(fileConfig.skillAutoReleaseRollbackMaxFailureRate)
                .rollbackMaxTimeoutRate(fileConfig.skillAutoReleaseRollbackMaxTimeoutRate)
                .rollbackMaxPermissionRate(fileConfig.skillAutoReleaseRollbackMaxPermissionBlockRate)
                .healthLookbackHours(fileConfig.skillAutoReleaseHealthLookbackHours)
                .canaryDwellHours(fileConfig.skillAutoReleaseCanaryDwellHours);
        // -- Watchdog file overrides (batch 4) -------------------------------
        watchdogBuilder
                .agentTurns(fileConfig.maxAgentTurns)
                .agentWallTimeSec(fileConfig.maxAgentWallTimeSec)
                .agentHardTurns(fileConfig.maxAgentHardTurns)
                .agentHardWallTimeSec(fileConfig.maxAgentHardWallTimeSec)
                .planStepWallTimeSec(fileConfig.maxPlanStepWallTimeSec)
                .planTotalWallTimeSec(fileConfig.maxPlanTotalWallTimeSec);
        if (fileConfig.deferredActionEnabled != null) {
            this.deferredActionEnabled = fileConfig.deferredActionEnabled;
        }
        if (fileConfig.deferredActionTickSeconds > 0) {
            this.deferredActionTickSeconds = fileConfig.deferredActionTickSeconds;
        }
        if (fileConfig.webSocket != null) {
            if (fileConfig.webSocket.enabled != null) {
                this.webSocketEnabled = fileConfig.webSocket.enabled;
                this.webSocketEnabledExplicit = true;
            }
            if (fileConfig.webSocket.port != null) {
                int p = fileConfig.webSocket.port;
                // Reject out-of-range ports here so a misconfiguration surfaces
                // at config-parsing time, not later when the bridge tries to
                // bind. Port 0 ("ephemeral") is intentionally not allowed in
                // user-facing config — production deployments need a stable
                // port; tests construct WebSocketBridge directly with port=0.
                if (p >= 1 && p <= 65_535) {
                    this.webSocketPort = p;
                } else {
                    log.warn("Ignoring invalid webSocket.port {} (must be 1..65535)", p);
                }
            }
            if (fileConfig.webSocket.host != null && !fileConfig.webSocket.host.isBlank()) {
                this.webSocketHost = fileConfig.webSocket.host;
            }
            if (fileConfig.webSocket.allowedOrigins != null) {
                // Trim before the blank check — WebSocketBridge does an exact
                // Origin match, so " http://localhost:5173 " would otherwise
                // survive the merge but never match a real browser header.
                this.webSocketAllowedOrigins = fileConfig.webSocket.allowedOrigins.stream()
                        .filter(o -> o != null)
                        .map(String::trim)
                        .filter(o -> !o.isBlank())
                        .toList();
            }
        }
        if (fileConfig.context1m != null) {
            this.context1m = fileConfig.context1m;
        }
        if (fileConfig.extraAnthropicBetas != null && !fileConfig.extraAnthropicBetas.isEmpty()) {
            this.extraAnthropicBetas = fileConfig.extraAnthropicBetas.stream()
                    .filter(b -> b != null && !b.isBlank())
                    .toList();
        }
        if (fileConfig.retry != null) {
            if (fileConfig.retry.maxRetries != null && fileConfig.retry.maxRetries >= 0) {
                this.retryMaxRetries = fileConfig.retry.maxRetries;
            }
            if (fileConfig.retry.initialBackoffMs != null && fileConfig.retry.initialBackoffMs >= 0) {
                this.retryInitialBackoffMs = fileConfig.retry.initialBackoffMs;
            }
            if (fileConfig.retry.maxBackoffMs != null && fileConfig.retry.maxBackoffMs >= 0) {
                this.retryMaxBackoffMs = fileConfig.retry.maxBackoffMs;
            }
            if (fileConfig.retry.jitterFactor != null
                    && fileConfig.retry.jitterFactor >= 0.0 && fileConfig.retry.jitterFactor <= 1.0) {
                this.retryJitterFactor = fileConfig.retry.jitterFactor;
            }
        }
        if (fileConfig.subAgentAutoApproveTools != null && !fileConfig.subAgentAutoApproveTools.isEmpty()) {
            // Project config appends to global config (not replaces)
            var merged = new ArrayList<>(this.subAgentAutoApproveTools);
            for (String tool : fileConfig.subAgentAutoApproveTools) {
                if (tool != null && !tool.isBlank() && !merged.contains(tool)) {
                    merged.add(tool);
                }
            }
            this.subAgentAutoApproveTools = List.copyOf(merged);
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
        public int maxTurns;
        public Boolean adaptiveContinuationEnabled;
        public Integer adaptiveContinuationMaxSegments;
        public Integer adaptiveContinuationNoProgressThreshold;
        public Integer adaptiveContinuationMaxTotalTokens;
        public Integer adaptiveContinuationMaxWallClockSeconds;
        public int contextWindowTokens;
        public String logLevel;
        public String braveSearchApiKey;
        public String permissionMode;
        public Boolean bootEnabled;
        public int bootTimeoutSeconds;
        public Boolean schedulerEnabled;
        public int schedulerTickSeconds;
        public Boolean heartbeatEnabled;
        public String heartbeatActiveHours;
        public Boolean plannerEnabled;
        public Integer plannerThreshold;
        public Boolean adaptiveReplanEnabled;
        public Boolean candidateInjectionEnabled;
        public Boolean candidatePromotionEnabled;
        public Integer candidatePromotionMinEvidence;
        public Double candidatePromotionMinScore;
        public Double candidatePromotionMaxFailureRate;
        public Integer candidateInjectionMaxCount;
        public Integer candidateInjectionMaxTokens;
        public Integer candidateInjectionMaxChars;
        public Integer antiPatternGateMinBlockedBeforeRollback;
        public Double antiPatternGateMaxFalsePositiveRate;
        public Boolean skillDraftValidationEnabled;
        public Boolean skillDraftValidationStrictMode;
        public Boolean skillDraftValidationReplayRequired;
        public String skillDraftValidationReplayReport;
        public Double skillDraftValidationMaxTokenEstimationErrorRatio;
        public Boolean skillAutoReleaseEnabled;
        public Double skillAutoReleaseMinCandidateScore;
        public Integer skillAutoReleaseMinEvidence;
        public Integer skillAutoReleaseCanaryMinAttempts;
        public Double skillAutoReleaseCanaryMaxFailureRate;
        public Double skillAutoReleaseCanaryMaxTimeoutRate;
        public Double skillAutoReleaseCanaryMaxPermissionBlockRate;
        public Double skillAutoReleaseActiveMaxFailureRate;
        public Double skillAutoReleaseRollbackMaxFailureRate;
        public Double skillAutoReleaseRollbackMaxTimeoutRate;
        public Double skillAutoReleaseRollbackMaxPermissionBlockRate;
        public Integer skillAutoReleaseHealthLookbackHours;
        public Integer skillAutoReleaseCanaryDwellHours;
        public Integer maxAgentTurns;
        public Integer maxAgentWallTimeSec;
        public Integer maxAgentHardTurns;
        public Integer maxAgentHardWallTimeSec;
        public Integer maxPlanStepWallTimeSec;
        public Integer maxPlanTotalWallTimeSec;
        public Boolean deferredActionEnabled;
        public int deferredActionTickSeconds;
        public WebSocketConfigFormat webSocket;
        public List<String> subAgentAutoApproveTools;
        public String defaultProfile;
        public Map<String, ConfigFileFormat> profiles;
        public Map<String, String> providerModels;
        public Map<String, List<HookMatcherFormat>> hooks;
        public Boolean context1m;
        public List<String> extraAnthropicBetas;
        public RetryConfigFormat retry;
        public SecurityConfigFormat security;
    }

    /**
     * JSON structure for the security section.
     * <pre>{
     *   "denySensitivePaths": true
     * }</pre>
     *
     * <p>Nested for forward compatibility — future security knobs (custom
     * sensitive-path patterns per follow-up, sandbox toggles, etc.) live
     * under the same {@code security} object rather than littering the top
     * level.
     */
    public static class SecurityConfigFormat {
        public Boolean denySensitivePaths;
    }

    /**
     * JSON structure for retry configuration.
     * <pre>{ "maxRetries": 5, "initialBackoffMs": 500, "maxBackoffMs": 60000, "jitterFactor": 0.25 }</pre>
     */
    public static class RetryConfigFormat {
        public Integer maxRetries;
        public Long initialBackoffMs;
        public Long maxBackoffMs;
        public Double jitterFactor;
    }

    /**
     * JSON structure for the WebSocket bridge section (issue #431).
     * <pre>{
     *   "enabled": true,
     *   "port": 3141,
     *   "host": "localhost",
     *   "allowedOrigins": ["http://localhost:5173"]
     * }</pre>
     */
    public static class WebSocketConfigFormat {
        public Boolean enabled;
        public Integer port;
        public String host;
        public List<String> allowedOrigins;
    }

    /**
     * JSON structure for a hook matcher entry in config.
     * <pre>{ "matcher": "bash", "hooks": [{ "type": "command", "command": "...", "timeout": 30 }] }</pre>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HookMatcherFormat(String matcher, List<HookConfigFormat> hooks) {}

    /**
     * JSON structure for a single hook config entry.
     * <pre>{ "type": "command", "command": "echo ok", "timeout": 60 }</pre>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HookConfigFormat(String type, String command, int timeout) {}

    private static double clampRate(double value) {
        return Math.min(1.0, Math.max(0.0, value));
    }

    // -- Env-var loading helpers --------------------------------------------
    //
    // Added during the AceClawConfig decomposition (batch 1, skillAutoRelease)
    // to collapse the previously-inlined "var x = getenv; if (x != null && !
    // blank) { try { parse → set } catch { warn }" pattern that appeared 50+
    // times in load(). Each helper takes the env var name + a consumer of
    // the parsed value; on parse failure the helper logs a warning and
    // leaves the receiver field untouched. Keeps the call site to a single
    // expression and centralizes the empty/blank + parse-error handling.

    private static void applyEnvBoolean(String name, java.util.function.Consumer<Boolean> sink) {
        var raw = System.getenv(name);
        if (raw == null || raw.isBlank()) return;
        sink.accept(Boolean.parseBoolean(raw));
    }

    private static void applyEnvInt(String name, java.util.function.IntConsumer sink) {
        var raw = System.getenv(name);
        if (raw == null || raw.isBlank()) return;
        try {
            sink.accept(Integer.parseInt(raw));
        } catch (NumberFormatException e) {
            log.warn("Invalid {}: {}", name, raw);
        }
    }

    private static void applyEnvDouble(String name, java.util.function.DoubleConsumer sink) {
        var raw = System.getenv(name);
        if (raw == null || raw.isBlank()) return;
        try {
            sink.accept(Double.parseDouble(raw));
        } catch (NumberFormatException e) {
            log.warn("Invalid {}: {}", name, raw);
        }
    }
}

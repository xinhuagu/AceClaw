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
    private static final int DEFAULT_MAX_TURNS = AgentLoopConfig.DEFAULT_MAX_ITERATIONS;
    private static final boolean DEFAULT_ADAPTIVE_CONTINUATION_ENABLED = true;
    private static final int DEFAULT_ADAPTIVE_CONTINUATION_MAX_SEGMENTS = 3;
    private static final int DEFAULT_ADAPTIVE_CONTINUATION_NO_PROGRESS_THRESHOLD = 2;
    private static final int DEFAULT_ADAPTIVE_CONTINUATION_MAX_TOTAL_TOKENS = 0;
    private static final int DEFAULT_ADAPTIVE_CONTINUATION_MAX_WALL_CLOCK_SECONDS = 0;
    private static final int DEFAULT_CONTEXT_WINDOW = 0;
    private static final String DEFAULT_LOG_LEVEL = "INFO";
    private static final boolean DEFAULT_BOOT_ENABLED = true;
    private static final int DEFAULT_BOOT_TIMEOUT_SECONDS = 120;
    private static final boolean DEFAULT_SCHEDULER_ENABLED = true;
    private static final int DEFAULT_SCHEDULER_TICK_SECONDS = 60;
    private static final boolean DEFAULT_HEARTBEAT_ENABLED = true;
    private static final boolean DEFAULT_PLANNER_ENABLED = true;
    private static final int DEFAULT_PLANNER_THRESHOLD = 5;
    private static final boolean DEFAULT_ADAPTIVE_REPLAN_ENABLED = true;
    private static final boolean DEFAULT_CANDIDATE_INJECTION_ENABLED = true;
    private static final boolean DEFAULT_CANDIDATE_PROMOTION_ENABLED = true;
    private static final int DEFAULT_CANDIDATE_PROMOTION_MIN_EVIDENCE = 2;
    private static final double DEFAULT_CANDIDATE_PROMOTION_MIN_SCORE = 0.75;
    private static final double DEFAULT_CANDIDATE_PROMOTION_MAX_FAILURE_RATE = 0.2;
    private static final int DEFAULT_CANDIDATE_INJECTION_MAX_COUNT = 10;
    private static final int DEFAULT_CANDIDATE_INJECTION_MAX_TOKENS = 1200;
    private static final int DEFAULT_ANTI_PATTERN_GATE_MIN_BLOCKED_BEFORE_ROLLBACK = 2;
    private static final double DEFAULT_ANTI_PATTERN_GATE_MAX_FALSE_POSITIVE_RATE = 0.50;
    private static final boolean DEFAULT_SKILL_DRAFT_VALIDATION_ENABLED = true;
    private static final boolean DEFAULT_SKILL_DRAFT_VALIDATION_STRICT_MODE = false;
    private static final boolean DEFAULT_SKILL_DRAFT_VALIDATION_REPLAY_REQUIRED = true;
    private static final String DEFAULT_SKILL_DRAFT_VALIDATION_REPLAY_REPORT =
            ".aceclaw/metrics/continuous-learning/replay-latest.json";
    private static final double DEFAULT_SKILL_DRAFT_VALIDATION_MAX_TOKEN_ESTIMATION_ERROR_RATIO = 0.65;
    private static final boolean DEFAULT_SKILL_AUTO_RELEASE_ENABLED = true;
    private static final double DEFAULT_SKILL_AUTO_RELEASE_MIN_CANDIDATE_SCORE = 0.80;
    private static final int DEFAULT_SKILL_AUTO_RELEASE_MIN_EVIDENCE = 3;
    private static final int DEFAULT_SKILL_AUTO_RELEASE_CANARY_MIN_ATTEMPTS = 5;
    private static final double DEFAULT_SKILL_AUTO_RELEASE_CANARY_MAX_FAILURE_RATE = 0.35;
    private static final double DEFAULT_SKILL_AUTO_RELEASE_CANARY_MAX_TIMEOUT_RATE = 0.20;
    private static final double DEFAULT_SKILL_AUTO_RELEASE_CANARY_MAX_PERMISSION_BLOCK_RATE = 0.20;
    private static final double DEFAULT_SKILL_AUTO_RELEASE_ACTIVE_MAX_FAILURE_RATE = 0.45; // legacy alias
    private static final double DEFAULT_SKILL_AUTO_RELEASE_ROLLBACK_MAX_FAILURE_RATE = 0.45;
    private static final double DEFAULT_SKILL_AUTO_RELEASE_ROLLBACK_MAX_TIMEOUT_RATE = 0.20;
    private static final double DEFAULT_SKILL_AUTO_RELEASE_ROLLBACK_MAX_PERMISSION_BLOCK_RATE = 0.20;
    private static final int DEFAULT_SKILL_AUTO_RELEASE_HEALTH_LOOKBACK_HOURS = 168;
    private static final int DEFAULT_MAX_AGENT_TURNS = 50;
    private static final int DEFAULT_MAX_AGENT_WALL_TIME_SEC = 600;
    private static final boolean DEFAULT_DEFERRED_ACTION_ENABLED = true;
    private static final int DEFAULT_DEFERRED_ACTION_TICK_SECONDS = 5;

    /** Claude CLI credentials directory. */
    private static final Path CLAUDE_CLI_DIR = Path.of(System.getProperty("user.home"), ".claude");
    /** Codex CLI credentials file for OpenAI Codex OAuth. */
    private static final Path CODEX_AUTH_FILE = Path.of(System.getProperty("user.home"), ".codex", "auth.json");

    private String provider;
    private String baseUrl;
    private String apiKey;
    private String refreshToken;
    private String model;
    private int maxTokens;
    private int thinkingBudget;
    private int maxTurns;
    private boolean adaptiveContinuationEnabled;
    private int adaptiveContinuationMaxSegments;
    private int adaptiveContinuationNoProgressThreshold;
    private int adaptiveContinuationMaxTotalTokens;
    private int adaptiveContinuationMaxWallClockSeconds;
    private int contextWindowTokens;
    private String logLevel;
    private String braveSearchApiKey;
    private String permissionMode;
    private boolean bootEnabled;
    private int bootTimeoutSeconds;
    private boolean schedulerEnabled;
    private int schedulerTickSeconds;
    private boolean heartbeatEnabled;
    private String heartbeatActiveHours;
    private String defaultProfile;
    private Map<String, ConfigFileFormat> profiles;
    private Map<String, String> providerModels;
    private boolean plannerEnabled;
    private int plannerThreshold;
    private boolean adaptiveReplanEnabled;
    private boolean candidateInjectionEnabled;
    private boolean candidatePromotionEnabled;
    private int candidatePromotionMinEvidence;
    private double candidatePromotionMinScore;
    private double candidatePromotionMaxFailureRate;
    private int candidateInjectionMaxCount;
    private int candidateInjectionMaxTokens;
    private int antiPatternGateMinBlockedBeforeRollback;
    private double antiPatternGateMaxFalsePositiveRate;
    private boolean skillDraftValidationEnabled;
    private boolean skillDraftValidationStrictMode;
    private boolean skillDraftValidationReplayRequired;
    private String skillDraftValidationReplayReport;
    private double skillDraftValidationMaxTokenEstimationErrorRatio;
    private boolean skillAutoReleaseEnabled;
    private double skillAutoReleaseMinCandidateScore;
    private int skillAutoReleaseMinEvidence;
    private int skillAutoReleaseCanaryMinAttempts;
    private double skillAutoReleaseCanaryMaxFailureRate;
    private double skillAutoReleaseCanaryMaxTimeoutRate;
    private double skillAutoReleaseCanaryMaxPermissionBlockRate;
    private double skillAutoReleaseActiveMaxFailureRate;
    private double skillAutoReleaseRollbackMaxFailureRate;
    private double skillAutoReleaseRollbackMaxTimeoutRate;
    private double skillAutoReleaseRollbackMaxPermissionBlockRate;
    private int skillAutoReleaseHealthLookbackHours;
    private int maxAgentTurns;
    private int maxAgentWallTimeSec;
    private boolean deferredActionEnabled;
    private int deferredActionTickSeconds;
    private Map<String, List<HookMatcherFormat>> hooks;

    private AceClawConfig() {
        this.provider = "anthropic";
        this.model = null; // resolved dynamically via providerModels or LlmClientFactory defaults
        this.maxTokens = DEFAULT_MAX_TOKENS;
        this.thinkingBudget = DEFAULT_THINKING_BUDGET;
        this.maxTurns = DEFAULT_MAX_TURNS;
        this.adaptiveContinuationEnabled = DEFAULT_ADAPTIVE_CONTINUATION_ENABLED;
        this.adaptiveContinuationMaxSegments = DEFAULT_ADAPTIVE_CONTINUATION_MAX_SEGMENTS;
        this.adaptiveContinuationNoProgressThreshold = DEFAULT_ADAPTIVE_CONTINUATION_NO_PROGRESS_THRESHOLD;
        this.adaptiveContinuationMaxTotalTokens = DEFAULT_ADAPTIVE_CONTINUATION_MAX_TOTAL_TOKENS;
        this.adaptiveContinuationMaxWallClockSeconds = DEFAULT_ADAPTIVE_CONTINUATION_MAX_WALL_CLOCK_SECONDS;
        this.contextWindowTokens = DEFAULT_CONTEXT_WINDOW;
        this.logLevel = DEFAULT_LOG_LEVEL;
        this.permissionMode = "normal";
        this.bootEnabled = DEFAULT_BOOT_ENABLED;
        this.bootTimeoutSeconds = DEFAULT_BOOT_TIMEOUT_SECONDS;
        this.schedulerEnabled = DEFAULT_SCHEDULER_ENABLED;
        this.schedulerTickSeconds = DEFAULT_SCHEDULER_TICK_SECONDS;
        this.heartbeatEnabled = DEFAULT_HEARTBEAT_ENABLED;
        this.plannerEnabled = DEFAULT_PLANNER_ENABLED;
        this.plannerThreshold = DEFAULT_PLANNER_THRESHOLD;
        this.adaptiveReplanEnabled = DEFAULT_ADAPTIVE_REPLAN_ENABLED;
        this.candidateInjectionEnabled = DEFAULT_CANDIDATE_INJECTION_ENABLED;
        this.candidatePromotionEnabled = DEFAULT_CANDIDATE_PROMOTION_ENABLED;
        this.candidatePromotionMinEvidence = DEFAULT_CANDIDATE_PROMOTION_MIN_EVIDENCE;
        this.candidatePromotionMinScore = DEFAULT_CANDIDATE_PROMOTION_MIN_SCORE;
        this.candidatePromotionMaxFailureRate = DEFAULT_CANDIDATE_PROMOTION_MAX_FAILURE_RATE;
        this.candidateInjectionMaxCount = DEFAULT_CANDIDATE_INJECTION_MAX_COUNT;
        this.candidateInjectionMaxTokens = DEFAULT_CANDIDATE_INJECTION_MAX_TOKENS;
        this.antiPatternGateMinBlockedBeforeRollback = DEFAULT_ANTI_PATTERN_GATE_MIN_BLOCKED_BEFORE_ROLLBACK;
        this.antiPatternGateMaxFalsePositiveRate = DEFAULT_ANTI_PATTERN_GATE_MAX_FALSE_POSITIVE_RATE;
        this.skillDraftValidationEnabled = DEFAULT_SKILL_DRAFT_VALIDATION_ENABLED;
        this.skillDraftValidationStrictMode = DEFAULT_SKILL_DRAFT_VALIDATION_STRICT_MODE;
        this.skillDraftValidationReplayRequired = DEFAULT_SKILL_DRAFT_VALIDATION_REPLAY_REQUIRED;
        this.skillDraftValidationReplayReport = DEFAULT_SKILL_DRAFT_VALIDATION_REPLAY_REPORT;
        this.skillDraftValidationMaxTokenEstimationErrorRatio =
                DEFAULT_SKILL_DRAFT_VALIDATION_MAX_TOKEN_ESTIMATION_ERROR_RATIO;
        this.skillAutoReleaseEnabled = DEFAULT_SKILL_AUTO_RELEASE_ENABLED;
        this.skillAutoReleaseMinCandidateScore = DEFAULT_SKILL_AUTO_RELEASE_MIN_CANDIDATE_SCORE;
        this.skillAutoReleaseMinEvidence = DEFAULT_SKILL_AUTO_RELEASE_MIN_EVIDENCE;
        this.skillAutoReleaseCanaryMinAttempts = DEFAULT_SKILL_AUTO_RELEASE_CANARY_MIN_ATTEMPTS;
        this.skillAutoReleaseCanaryMaxFailureRate = DEFAULT_SKILL_AUTO_RELEASE_CANARY_MAX_FAILURE_RATE;
        this.skillAutoReleaseCanaryMaxTimeoutRate = DEFAULT_SKILL_AUTO_RELEASE_CANARY_MAX_TIMEOUT_RATE;
        this.skillAutoReleaseCanaryMaxPermissionBlockRate = DEFAULT_SKILL_AUTO_RELEASE_CANARY_MAX_PERMISSION_BLOCK_RATE;
        this.skillAutoReleaseActiveMaxFailureRate = DEFAULT_SKILL_AUTO_RELEASE_ACTIVE_MAX_FAILURE_RATE;
        this.skillAutoReleaseRollbackMaxFailureRate = DEFAULT_SKILL_AUTO_RELEASE_ROLLBACK_MAX_FAILURE_RATE;
        this.skillAutoReleaseRollbackMaxTimeoutRate = DEFAULT_SKILL_AUTO_RELEASE_ROLLBACK_MAX_TIMEOUT_RATE;
        this.skillAutoReleaseRollbackMaxPermissionBlockRate =
                DEFAULT_SKILL_AUTO_RELEASE_ROLLBACK_MAX_PERMISSION_BLOCK_RATE;
        this.skillAutoReleaseHealthLookbackHours = DEFAULT_SKILL_AUTO_RELEASE_HEALTH_LOOKBACK_HOURS;
        this.maxAgentTurns = DEFAULT_MAX_AGENT_TURNS;
        this.maxAgentWallTimeSec = DEFAULT_MAX_AGENT_WALL_TIME_SEC;
        this.deferredActionEnabled = DEFAULT_DEFERRED_ACTION_ENABLED;
        this.deferredActionTickSeconds = DEFAULT_DEFERRED_ACTION_TICK_SECONDS;
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
        var envMaxAgentTurns = System.getenv("ACECLAW_MAX_AGENT_TURNS");
        if (envMaxAgentTurns != null && !envMaxAgentTurns.isBlank()) {
            try {
                config.maxAgentTurns = Math.max(0, Integer.parseInt(envMaxAgentTurns));
            } catch (NumberFormatException e) {
                log.warn("Invalid ACECLAW_MAX_AGENT_TURNS: {}", envMaxAgentTurns);
            }
        }
        var envMaxAgentWallTime = System.getenv("ACECLAW_MAX_AGENT_WALL_TIME_SEC");
        if (envMaxAgentWallTime != null && !envMaxAgentWallTime.isBlank()) {
            try {
                config.maxAgentWallTimeSec = Math.max(0, Integer.parseInt(envMaxAgentWallTime));
            } catch (NumberFormatException e) {
                log.warn("Invalid ACECLAW_MAX_AGENT_WALL_TIME_SEC: {}", envMaxAgentWallTime);
            }
        }
        var envAdaptiveContinuation = System.getenv("ACECLAW_ADAPTIVE_CONTINUATION");
        if (envAdaptiveContinuation != null && !envAdaptiveContinuation.isBlank()) {
            config.adaptiveContinuationEnabled = Boolean.parseBoolean(envAdaptiveContinuation);
        }
        var envAdaptiveSegments = System.getenv("ACECLAW_ADAPTIVE_CONTINUATION_MAX_SEGMENTS");
        if (envAdaptiveSegments != null && !envAdaptiveSegments.isBlank()) {
            try {
                config.adaptiveContinuationMaxSegments = Math.max(1, Integer.parseInt(envAdaptiveSegments));
            } catch (NumberFormatException e) {
                log.warn("Invalid ACECLAW_ADAPTIVE_CONTINUATION_MAX_SEGMENTS: {}", envAdaptiveSegments);
            }
        }
        var envAdaptiveNoProgress = System.getenv("ACECLAW_ADAPTIVE_CONTINUATION_NO_PROGRESS_THRESHOLD");
        if (envAdaptiveNoProgress != null && !envAdaptiveNoProgress.isBlank()) {
            try {
                config.adaptiveContinuationNoProgressThreshold = Math.max(1, Integer.parseInt(envAdaptiveNoProgress));
            } catch (NumberFormatException e) {
                log.warn("Invalid ACECLAW_ADAPTIVE_CONTINUATION_NO_PROGRESS_THRESHOLD: {}", envAdaptiveNoProgress);
            }
        }
        var envAdaptiveMaxTokens = System.getenv("ACECLAW_ADAPTIVE_CONTINUATION_MAX_TOTAL_TOKENS");
        if (envAdaptiveMaxTokens != null && !envAdaptiveMaxTokens.isBlank()) {
            try {
                config.adaptiveContinuationMaxTotalTokens = Math.max(0, Integer.parseInt(envAdaptiveMaxTokens));
            } catch (NumberFormatException e) {
                log.warn("Invalid ACECLAW_ADAPTIVE_CONTINUATION_MAX_TOTAL_TOKENS: {}", envAdaptiveMaxTokens);
            }
        }
        var envAdaptiveMaxWallClock = System.getenv("ACECLAW_ADAPTIVE_CONTINUATION_MAX_WALL_CLOCK_SECONDS");
        if (envAdaptiveMaxWallClock != null && !envAdaptiveMaxWallClock.isBlank()) {
            try {
                config.adaptiveContinuationMaxWallClockSeconds = Math.max(0, Integer.parseInt(envAdaptiveMaxWallClock));
            } catch (NumberFormatException e) {
                log.warn("Invalid ACECLAW_ADAPTIVE_CONTINUATION_MAX_WALL_CLOCK_SECONDS: {}", envAdaptiveMaxWallClock);
            }
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
        var envCandidateInjection = System.getenv("ACECLAW_CANDIDATE_INJECTION");
        if (envCandidateInjection != null && !envCandidateInjection.isBlank()) {
            config.candidateInjectionEnabled = Boolean.parseBoolean(envCandidateInjection);
        }
        var envCandidatePromotion = System.getenv("ACECLAW_CANDIDATE_PROMOTION");
        if (envCandidatePromotion != null && !envCandidatePromotion.isBlank()) {
            config.candidatePromotionEnabled = Boolean.parseBoolean(envCandidatePromotion);
        }
        var envCandidateInjectionMaxTokens = System.getenv("ACECLAW_CANDIDATE_INJECTION_MAX_TOKENS");
        if (envCandidateInjectionMaxTokens != null && !envCandidateInjectionMaxTokens.isBlank()) {
            try {
                config.candidateInjectionMaxTokens = Math.max(0, Integer.parseInt(envCandidateInjectionMaxTokens));
            } catch (NumberFormatException e) {
                log.warn("Invalid ACECLAW_CANDIDATE_INJECTION_MAX_TOKENS: {}", envCandidateInjectionMaxTokens);
            }
        }
        var envAntiPatternMinBlocked = System.getenv("ACECLAW_ANTI_PATTERN_GATE_MIN_BLOCKED_BEFORE_ROLLBACK");
        if (envAntiPatternMinBlocked != null && !envAntiPatternMinBlocked.isBlank()) {
            try {
                config.antiPatternGateMinBlockedBeforeRollback = Math.max(1, Integer.parseInt(envAntiPatternMinBlocked));
            } catch (NumberFormatException e) {
                log.warn("Invalid ACECLAW_ANTI_PATTERN_GATE_MIN_BLOCKED_BEFORE_ROLLBACK: {}", envAntiPatternMinBlocked);
            }
        }
        var envAntiPatternMaxFpRate = System.getenv("ACECLAW_ANTI_PATTERN_GATE_MAX_FALSE_POSITIVE_RATE");
        if (envAntiPatternMaxFpRate != null && !envAntiPatternMaxFpRate.isBlank()) {
            try {
                config.antiPatternGateMaxFalsePositiveRate = clampRate(Double.parseDouble(envAntiPatternMaxFpRate));
            } catch (NumberFormatException e) {
                log.warn("Invalid ACECLAW_ANTI_PATTERN_GATE_MAX_FALSE_POSITIVE_RATE: {}", envAntiPatternMaxFpRate);
            }
        }
        var envSkillDraftValidation = System.getenv("ACECLAW_SKILL_DRAFT_VALIDATION");
        if (envSkillDraftValidation != null && !envSkillDraftValidation.isBlank()) {
            config.skillDraftValidationEnabled = Boolean.parseBoolean(envSkillDraftValidation);
        }
        var envSkillDraftValidationStrict = System.getenv("ACECLAW_SKILL_DRAFT_VALIDATION_STRICT_MODE");
        if (envSkillDraftValidationStrict != null && !envSkillDraftValidationStrict.isBlank()) {
            config.skillDraftValidationStrictMode = Boolean.parseBoolean(envSkillDraftValidationStrict);
        }
        var envSkillDraftValidationReplayRequired =
                System.getenv("ACECLAW_SKILL_DRAFT_VALIDATION_REPLAY_REQUIRED");
        if (envSkillDraftValidationReplayRequired != null && !envSkillDraftValidationReplayRequired.isBlank()) {
            config.skillDraftValidationReplayRequired = Boolean.parseBoolean(envSkillDraftValidationReplayRequired);
        }
        var envReplayReportPath = System.getenv("ACECLAW_REPLAY_REPORT_PATH");
        if (envReplayReportPath != null && !envReplayReportPath.isBlank()) {
            config.skillDraftValidationReplayReport = envReplayReportPath;
        }
        var envSkillDraftValidationMaxTokenError =
                System.getenv("ACECLAW_SKILL_DRAFT_VALIDATION_MAX_TOKEN_ESTIMATION_ERROR_RATIO");
        if (envSkillDraftValidationMaxTokenError != null && !envSkillDraftValidationMaxTokenError.isBlank()) {
            try {
                config.skillDraftValidationMaxTokenEstimationErrorRatio =
                        Double.parseDouble(envSkillDraftValidationMaxTokenError);
            } catch (NumberFormatException e) {
                log.warn("Invalid ACECLAW_SKILL_DRAFT_VALIDATION_MAX_TOKEN_ESTIMATION_ERROR_RATIO: {}",
                        envSkillDraftValidationMaxTokenError);
            }
        }
        var envSkillAutoReleaseEnabled = System.getenv("ACECLAW_SKILL_AUTO_RELEASE");
        if (envSkillAutoReleaseEnabled != null && !envSkillAutoReleaseEnabled.isBlank()) {
            config.skillAutoReleaseEnabled = Boolean.parseBoolean(envSkillAutoReleaseEnabled);
        }
        var envSkillAutoReleaseMinScore = System.getenv("ACECLAW_SKILL_AUTO_RELEASE_MIN_SCORE");
        if (envSkillAutoReleaseMinScore != null && !envSkillAutoReleaseMinScore.isBlank()) {
            try {
                config.skillAutoReleaseMinCandidateScore =
                        clampRate(Double.parseDouble(envSkillAutoReleaseMinScore));
            } catch (NumberFormatException e) {
                log.warn("Invalid ACECLAW_SKILL_AUTO_RELEASE_MIN_SCORE: {}", envSkillAutoReleaseMinScore);
            }
        }
        var envSkillAutoReleaseMinEvidence = System.getenv("ACECLAW_SKILL_AUTO_RELEASE_MIN_EVIDENCE");
        if (envSkillAutoReleaseMinEvidence != null && !envSkillAutoReleaseMinEvidence.isBlank()) {
            try {
                config.skillAutoReleaseMinEvidence = Math.max(0, Integer.parseInt(envSkillAutoReleaseMinEvidence));
            } catch (NumberFormatException e) {
                log.warn("Invalid ACECLAW_SKILL_AUTO_RELEASE_MIN_EVIDENCE: {}", envSkillAutoReleaseMinEvidence);
            }
        }
        var envSkillAutoReleaseCanaryMinAttempts =
                System.getenv("ACECLAW_SKILL_AUTO_RELEASE_CANARY_MIN_ATTEMPTS");
        if (envSkillAutoReleaseCanaryMinAttempts != null && !envSkillAutoReleaseCanaryMinAttempts.isBlank()) {
            try {
                config.skillAutoReleaseCanaryMinAttempts =
                        Math.max(0, Integer.parseInt(envSkillAutoReleaseCanaryMinAttempts));
            } catch (NumberFormatException e) {
                log.warn("Invalid ACECLAW_SKILL_AUTO_RELEASE_CANARY_MIN_ATTEMPTS: {}",
                        envSkillAutoReleaseCanaryMinAttempts);
            }
        }
        var envSkillAutoReleaseCanaryMaxFailureRate =
                System.getenv("ACECLAW_SKILL_AUTO_RELEASE_CANARY_MAX_FAILURE_RATE");
        if (envSkillAutoReleaseCanaryMaxFailureRate != null && !envSkillAutoReleaseCanaryMaxFailureRate.isBlank()) {
            try {
                config.skillAutoReleaseCanaryMaxFailureRate =
                        clampRate(Double.parseDouble(envSkillAutoReleaseCanaryMaxFailureRate));
            } catch (NumberFormatException e) {
                log.warn("Invalid ACECLAW_SKILL_AUTO_RELEASE_CANARY_MAX_FAILURE_RATE: {}",
                        envSkillAutoReleaseCanaryMaxFailureRate);
            }
        }
        var envSkillAutoReleaseCanaryMaxTimeoutRate =
                System.getenv("ACECLAW_SKILL_AUTO_RELEASE_CANARY_MAX_TIMEOUT_RATE");
        if (envSkillAutoReleaseCanaryMaxTimeoutRate != null && !envSkillAutoReleaseCanaryMaxTimeoutRate.isBlank()) {
            try {
                config.skillAutoReleaseCanaryMaxTimeoutRate =
                        clampRate(Double.parseDouble(envSkillAutoReleaseCanaryMaxTimeoutRate));
            } catch (NumberFormatException e) {
                log.warn("Invalid ACECLAW_SKILL_AUTO_RELEASE_CANARY_MAX_TIMEOUT_RATE: {}",
                        envSkillAutoReleaseCanaryMaxTimeoutRate);
            }
        }
        var envSkillAutoReleaseCanaryMaxPermissionRate =
                System.getenv("ACECLAW_SKILL_AUTO_RELEASE_CANARY_MAX_PERMISSION_BLOCK_RATE");
        if (envSkillAutoReleaseCanaryMaxPermissionRate != null
                && !envSkillAutoReleaseCanaryMaxPermissionRate.isBlank()) {
            try {
                config.skillAutoReleaseCanaryMaxPermissionBlockRate =
                        clampRate(Double.parseDouble(envSkillAutoReleaseCanaryMaxPermissionRate));
            } catch (NumberFormatException e) {
                log.warn("Invalid ACECLAW_SKILL_AUTO_RELEASE_CANARY_MAX_PERMISSION_BLOCK_RATE: {}",
                        envSkillAutoReleaseCanaryMaxPermissionRate);
            }
        }
        var envSkillAutoReleaseActiveMaxFailureRate =
                System.getenv("ACECLAW_SKILL_AUTO_RELEASE_ACTIVE_MAX_FAILURE_RATE");
        if (envSkillAutoReleaseActiveMaxFailureRate != null && !envSkillAutoReleaseActiveMaxFailureRate.isBlank()) {
            try {
                config.skillAutoReleaseActiveMaxFailureRate =
                        clampRate(Double.parseDouble(envSkillAutoReleaseActiveMaxFailureRate));
                // Legacy env alias maps to rollback failure threshold.
                config.skillAutoReleaseRollbackMaxFailureRate = config.skillAutoReleaseActiveMaxFailureRate;
            } catch (NumberFormatException e) {
                log.warn("Invalid ACECLAW_SKILL_AUTO_RELEASE_ACTIVE_MAX_FAILURE_RATE: {}",
                        envSkillAutoReleaseActiveMaxFailureRate);
            }
        }
        var envSkillAutoReleaseRollbackMaxFailureRate =
                System.getenv("ACECLAW_SKILL_AUTO_RELEASE_ROLLBACK_MAX_FAILURE_RATE");
        if (envSkillAutoReleaseRollbackMaxFailureRate != null && !envSkillAutoReleaseRollbackMaxFailureRate.isBlank()) {
            try {
                config.skillAutoReleaseRollbackMaxFailureRate =
                        clampRate(Double.parseDouble(envSkillAutoReleaseRollbackMaxFailureRate));
            } catch (NumberFormatException e) {
                log.warn("Invalid ACECLAW_SKILL_AUTO_RELEASE_ROLLBACK_MAX_FAILURE_RATE: {}",
                        envSkillAutoReleaseRollbackMaxFailureRate);
            }
        }
        var envSkillAutoReleaseRollbackMaxTimeoutRate =
                System.getenv("ACECLAW_SKILL_AUTO_RELEASE_ROLLBACK_MAX_TIMEOUT_RATE");
        if (envSkillAutoReleaseRollbackMaxTimeoutRate != null && !envSkillAutoReleaseRollbackMaxTimeoutRate.isBlank()) {
            try {
                config.skillAutoReleaseRollbackMaxTimeoutRate =
                        clampRate(Double.parseDouble(envSkillAutoReleaseRollbackMaxTimeoutRate));
            } catch (NumberFormatException e) {
                log.warn("Invalid ACECLAW_SKILL_AUTO_RELEASE_ROLLBACK_MAX_TIMEOUT_RATE: {}",
                        envSkillAutoReleaseRollbackMaxTimeoutRate);
            }
        }
        var envSkillAutoReleaseRollbackMaxPermissionRate =
                System.getenv("ACECLAW_SKILL_AUTO_RELEASE_ROLLBACK_MAX_PERMISSION_BLOCK_RATE");
        if (envSkillAutoReleaseRollbackMaxPermissionRate != null
                && !envSkillAutoReleaseRollbackMaxPermissionRate.isBlank()) {
            try {
                config.skillAutoReleaseRollbackMaxPermissionBlockRate =
                        clampRate(Double.parseDouble(envSkillAutoReleaseRollbackMaxPermissionRate));
            } catch (NumberFormatException e) {
                log.warn("Invalid ACECLAW_SKILL_AUTO_RELEASE_ROLLBACK_MAX_PERMISSION_BLOCK_RATE: {}",
                        envSkillAutoReleaseRollbackMaxPermissionRate);
            }
        }
        var envSkillAutoReleaseLookbackHours = System.getenv("ACECLAW_SKILL_AUTO_RELEASE_HEALTH_LOOKBACK_HOURS");
        if (envSkillAutoReleaseLookbackHours != null && !envSkillAutoReleaseLookbackHours.isBlank()) {
            try {
                config.skillAutoReleaseHealthLookbackHours =
                        Math.max(1, Integer.parseInt(envSkillAutoReleaseLookbackHours));
            } catch (NumberFormatException e) {
                log.warn("Invalid ACECLAW_SKILL_AUTO_RELEASE_HEALTH_LOOKBACK_HOURS: {}",
                        envSkillAutoReleaseLookbackHours);
            }
        }

        // 5. Provider-specific credential discovery fallback
        if ((config.apiKey == null || config.apiKey.isBlank())
                && "openai-codex".equals(config.provider)) {
            config.loadCodexAuthToken();
        }

        // 6. If apiKey is an OAuth token and no refresh token configured,
        //    try to load the refresh token from Claude CLI credentials
        if (config.apiKey != null && config.apiKey.startsWith("sk-ant-oat")
                && config.refreshToken == null) {
            config.loadClaudeCliCredentials();
        }

        log.info("Config loaded: provider={}, model={}, maxTokens={}, thinkingBudget={}, maxTurns={}, adaptiveContinuationEnabled={}, adaptiveMaxSegments={}, contextWindow={}, logLevel={}, baseUrl={}, apiKey={}, refreshToken={}",
                config.provider, config.model, config.maxTokens, config.thinkingBudget, config.maxTurns,
                config.adaptiveContinuationEnabled, config.adaptiveContinuationMaxSegments,
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

    public boolean adaptiveContinuationEnabled() {
        return adaptiveContinuationEnabled;
    }

    public int adaptiveContinuationMaxSegments() {
        return adaptiveContinuationMaxSegments;
    }

    public int adaptiveContinuationNoProgressThreshold() {
        return adaptiveContinuationNoProgressThreshold;
    }

    public int adaptiveContinuationMaxTotalTokens() {
        return adaptiveContinuationMaxTotalTokens;
    }

    public int adaptiveContinuationMaxWallClockSeconds() {
        return adaptiveContinuationMaxWallClockSeconds;
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
     * Returns whether candidate injection into the system prompt is enabled.
     * Defaults to true.
     */
    public boolean candidateInjectionEnabled() {
        return candidateInjectionEnabled;
    }

    /**
     * Returns whether automatic candidate state transitions (promotion/demotion) are enabled.
     * Defaults to true.
     */
    public boolean candidatePromotionEnabled() {
        return candidatePromotionEnabled;
    }

    /**
     * Returns the minimum evidence count for candidate promotion.
     * Defaults to 3.
     */
    public int candidatePromotionMinEvidence() {
        return candidatePromotionMinEvidence;
    }

    /**
     * Returns the minimum score for candidate promotion.
     * Defaults to 0.75.
     */
    public double candidatePromotionMinScore() {
        return candidatePromotionMinScore;
    }

    /**
     * Returns the maximum failure rate for candidate promotion.
     * Defaults to 0.2.
     */
    public double candidatePromotionMaxFailureRate() {
        return candidatePromotionMaxFailureRate;
    }

    /**
     * Returns the maximum number of candidates to inject into the system prompt.
     * Defaults to 10.
     */
    public int candidateInjectionMaxCount() {
        return candidateInjectionMaxCount;
    }

    /**
     * Returns the maximum token budget for candidate injection.
     * Defaults to 1200.
     */
    public int candidateInjectionMaxTokens() {
        return candidateInjectionMaxTokens;
    }

    public int antiPatternGateMinBlockedBeforeRollback() {
        return antiPatternGateMinBlockedBeforeRollback;
    }

    public double antiPatternGateMaxFalsePositiveRate() {
        return antiPatternGateMaxFalsePositiveRate;
    }

    /**
     * Returns whether autonomous skill draft validation is enabled.
     */
    public boolean skillDraftValidationEnabled() {
        return skillDraftValidationEnabled;
    }

    /**
     * Returns whether validation gate strict mode is enabled.
     */
    public boolean skillDraftValidationStrictMode() {
        return skillDraftValidationStrictMode;
    }

    /**
     * Returns whether replay checks are required for draft validation.
     */
    public boolean skillDraftValidationReplayRequired() {
        return skillDraftValidationReplayRequired;
    }

    /**
     * Returns replay report path for draft validation.
     */
    public String skillDraftValidationReplayReport() {
        return skillDraftValidationReplayReport;
    }

    /**
     * Returns max token-estimation error ratio accepted by draft validation replay gate.
     */
    public double skillDraftValidationMaxTokenEstimationErrorRatio() {
        return skillDraftValidationMaxTokenEstimationErrorRatio;
    }

    public boolean skillAutoReleaseEnabled() {
        return skillAutoReleaseEnabled;
    }

    public double skillAutoReleaseMinCandidateScore() {
        return skillAutoReleaseMinCandidateScore;
    }

    public int skillAutoReleaseMinEvidence() {
        return skillAutoReleaseMinEvidence;
    }

    public int skillAutoReleaseCanaryMinAttempts() {
        return skillAutoReleaseCanaryMinAttempts;
    }

    public double skillAutoReleaseCanaryMaxFailureRate() {
        return skillAutoReleaseCanaryMaxFailureRate;
    }

    public double skillAutoReleaseCanaryMaxTimeoutRate() {
        return skillAutoReleaseCanaryMaxTimeoutRate;
    }

    public double skillAutoReleaseCanaryMaxPermissionBlockRate() {
        return skillAutoReleaseCanaryMaxPermissionBlockRate;
    }

    public double skillAutoReleaseActiveMaxFailureRate() {
        return skillAutoReleaseActiveMaxFailureRate;
    }

    public double skillAutoReleaseRollbackMaxFailureRate() {
        return skillAutoReleaseRollbackMaxFailureRate;
    }

    public double skillAutoReleaseRollbackMaxTimeoutRate() {
        return skillAutoReleaseRollbackMaxTimeoutRate;
    }

    public double skillAutoReleaseRollbackMaxPermissionBlockRate() {
        return skillAutoReleaseRollbackMaxPermissionBlockRate;
    }

    public int skillAutoReleaseHealthLookbackHours() {
        return skillAutoReleaseHealthLookbackHours;
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
     * Returns the maximum number of agent ReAct iterations per request.
     * Enforced by the watchdog timer. 0 = disabled (uses existing maxTurns only).
     * Defaults to 50.
     */
    public int maxAgentTurns() {
        return maxAgentTurns;
    }

    /**
     * Returns the maximum wall-clock time in seconds per agent request.
     * Enforced by the watchdog timer. 0 = disabled.
     * Defaults to 600 (10 minutes).
     */
    public int maxAgentWallTimeSec() {
        return maxAgentWallTimeSec;
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
        if (fileConfig.adaptiveContinuationEnabled != null) {
            this.adaptiveContinuationEnabled = fileConfig.adaptiveContinuationEnabled;
        }
        if (fileConfig.adaptiveContinuationMaxSegments != null && fileConfig.adaptiveContinuationMaxSegments > 0) {
            this.adaptiveContinuationMaxSegments = fileConfig.adaptiveContinuationMaxSegments;
        }
        if (fileConfig.adaptiveContinuationNoProgressThreshold != null
                && fileConfig.adaptiveContinuationNoProgressThreshold > 0) {
            this.adaptiveContinuationNoProgressThreshold = fileConfig.adaptiveContinuationNoProgressThreshold;
        }
        if (fileConfig.adaptiveContinuationMaxTotalTokens != null
                && fileConfig.adaptiveContinuationMaxTotalTokens >= 0) {
            this.adaptiveContinuationMaxTotalTokens = fileConfig.adaptiveContinuationMaxTotalTokens;
        }
        if (fileConfig.adaptiveContinuationMaxWallClockSeconds != null
                && fileConfig.adaptiveContinuationMaxWallClockSeconds >= 0) {
            this.adaptiveContinuationMaxWallClockSeconds = fileConfig.adaptiveContinuationMaxWallClockSeconds;
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
        if (fileConfig.candidateInjectionEnabled != null) {
            this.candidateInjectionEnabled = fileConfig.candidateInjectionEnabled;
        }
        if (fileConfig.candidatePromotionEnabled != null) {
            this.candidatePromotionEnabled = fileConfig.candidatePromotionEnabled;
        }
        if (fileConfig.candidatePromotionMinEvidence != null && fileConfig.candidatePromotionMinEvidence > 0) {
            this.candidatePromotionMinEvidence = fileConfig.candidatePromotionMinEvidence;
        }
        if (fileConfig.candidatePromotionMinScore != null && fileConfig.candidatePromotionMinScore >= 0) {
            this.candidatePromotionMinScore = fileConfig.candidatePromotionMinScore;
        }
        if (fileConfig.candidatePromotionMaxFailureRate != null && fileConfig.candidatePromotionMaxFailureRate >= 0) {
            this.candidatePromotionMaxFailureRate = fileConfig.candidatePromotionMaxFailureRate;
        }
        if (fileConfig.candidateInjectionMaxCount != null && fileConfig.candidateInjectionMaxCount >= 0) {
            this.candidateInjectionMaxCount = fileConfig.candidateInjectionMaxCount;
        }
        if (fileConfig.candidateInjectionMaxTokens != null && fileConfig.candidateInjectionMaxTokens >= 0) {
            this.candidateInjectionMaxTokens = fileConfig.candidateInjectionMaxTokens;
        } else if (fileConfig.candidateInjectionMaxChars != null && fileConfig.candidateInjectionMaxChars >= 0) {
            // Backward compatibility: old char-based setting converts to approximate token budget.
            this.candidateInjectionMaxTokens = Math.max(0, fileConfig.candidateInjectionMaxChars / 4);
        }
        if (fileConfig.antiPatternGateMinBlockedBeforeRollback != null
                && fileConfig.antiPatternGateMinBlockedBeforeRollback > 0) {
            this.antiPatternGateMinBlockedBeforeRollback = fileConfig.antiPatternGateMinBlockedBeforeRollback;
        }
        if (fileConfig.antiPatternGateMaxFalsePositiveRate != null
                && fileConfig.antiPatternGateMaxFalsePositiveRate >= 0) {
            this.antiPatternGateMaxFalsePositiveRate = clampRate(fileConfig.antiPatternGateMaxFalsePositiveRate);
        }
        if (fileConfig.skillDraftValidationEnabled != null) {
            this.skillDraftValidationEnabled = fileConfig.skillDraftValidationEnabled;
        }
        if (fileConfig.skillDraftValidationStrictMode != null) {
            this.skillDraftValidationStrictMode = fileConfig.skillDraftValidationStrictMode;
        }
        if (fileConfig.skillDraftValidationReplayRequired != null) {
            this.skillDraftValidationReplayRequired = fileConfig.skillDraftValidationReplayRequired;
        }
        if (fileConfig.skillDraftValidationReplayReport != null
                && !fileConfig.skillDraftValidationReplayReport.isBlank()) {
            this.skillDraftValidationReplayReport = fileConfig.skillDraftValidationReplayReport;
        }
        if (fileConfig.skillDraftValidationMaxTokenEstimationErrorRatio != null
                && fileConfig.skillDraftValidationMaxTokenEstimationErrorRatio >= 0) {
            this.skillDraftValidationMaxTokenEstimationErrorRatio =
                    fileConfig.skillDraftValidationMaxTokenEstimationErrorRatio;
        }
        if (fileConfig.skillAutoReleaseEnabled != null) {
            this.skillAutoReleaseEnabled = fileConfig.skillAutoReleaseEnabled;
        }
        if (fileConfig.skillAutoReleaseMinCandidateScore != null
                && fileConfig.skillAutoReleaseMinCandidateScore >= 0) {
            this.skillAutoReleaseMinCandidateScore = clampRate(fileConfig.skillAutoReleaseMinCandidateScore);
        }
        if (fileConfig.skillAutoReleaseMinEvidence != null && fileConfig.skillAutoReleaseMinEvidence > 0) {
            this.skillAutoReleaseMinEvidence = fileConfig.skillAutoReleaseMinEvidence;
        }
        if (fileConfig.skillAutoReleaseCanaryMinAttempts != null
                && fileConfig.skillAutoReleaseCanaryMinAttempts >= 0) {
            this.skillAutoReleaseCanaryMinAttempts = fileConfig.skillAutoReleaseCanaryMinAttempts;
        }
        if (fileConfig.skillAutoReleaseCanaryMaxFailureRate != null
                && fileConfig.skillAutoReleaseCanaryMaxFailureRate >= 0) {
            this.skillAutoReleaseCanaryMaxFailureRate = clampRate(fileConfig.skillAutoReleaseCanaryMaxFailureRate);
        }
        if (fileConfig.skillAutoReleaseCanaryMaxTimeoutRate != null
                && fileConfig.skillAutoReleaseCanaryMaxTimeoutRate >= 0) {
            this.skillAutoReleaseCanaryMaxTimeoutRate = clampRate(fileConfig.skillAutoReleaseCanaryMaxTimeoutRate);
        }
        if (fileConfig.skillAutoReleaseCanaryMaxPermissionBlockRate != null
                && fileConfig.skillAutoReleaseCanaryMaxPermissionBlockRate >= 0) {
            this.skillAutoReleaseCanaryMaxPermissionBlockRate =
                    clampRate(fileConfig.skillAutoReleaseCanaryMaxPermissionBlockRate);
        }
        if (fileConfig.skillAutoReleaseActiveMaxFailureRate != null
                && fileConfig.skillAutoReleaseActiveMaxFailureRate >= 0) {
            this.skillAutoReleaseActiveMaxFailureRate = clampRate(fileConfig.skillAutoReleaseActiveMaxFailureRate);
            // Legacy config key maps to rollback failure threshold.
            this.skillAutoReleaseRollbackMaxFailureRate = this.skillAutoReleaseActiveMaxFailureRate;
        }
        if (fileConfig.skillAutoReleaseRollbackMaxFailureRate != null
                && fileConfig.skillAutoReleaseRollbackMaxFailureRate >= 0) {
            this.skillAutoReleaseRollbackMaxFailureRate = clampRate(fileConfig.skillAutoReleaseRollbackMaxFailureRate);
        }
        if (fileConfig.skillAutoReleaseRollbackMaxTimeoutRate != null
                && fileConfig.skillAutoReleaseRollbackMaxTimeoutRate >= 0) {
            this.skillAutoReleaseRollbackMaxTimeoutRate = clampRate(fileConfig.skillAutoReleaseRollbackMaxTimeoutRate);
        }
        if (fileConfig.skillAutoReleaseRollbackMaxPermissionBlockRate != null
                && fileConfig.skillAutoReleaseRollbackMaxPermissionBlockRate >= 0) {
            this.skillAutoReleaseRollbackMaxPermissionBlockRate =
                    clampRate(fileConfig.skillAutoReleaseRollbackMaxPermissionBlockRate);
        }
        if (fileConfig.skillAutoReleaseHealthLookbackHours != null
                && fileConfig.skillAutoReleaseHealthLookbackHours > 0) {
            this.skillAutoReleaseHealthLookbackHours = fileConfig.skillAutoReleaseHealthLookbackHours;
        }
        if (fileConfig.maxAgentTurns != null && fileConfig.maxAgentTurns >= 0) {
            this.maxAgentTurns = fileConfig.maxAgentTurns;
        }
        if (fileConfig.maxAgentWallTimeSec != null && fileConfig.maxAgentWallTimeSec >= 0) {
            this.maxAgentWallTimeSec = fileConfig.maxAgentWallTimeSec;
        }
        if (fileConfig.deferredActionEnabled != null) {
            this.deferredActionEnabled = fileConfig.deferredActionEnabled;
        }
        if (fileConfig.deferredActionTickSeconds > 0) {
            this.deferredActionTickSeconds = fileConfig.deferredActionTickSeconds;
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
        public Integer maxAgentTurns;
        public Integer maxAgentWallTimeSec;
        public Boolean deferredActionEnabled;
        public int deferredActionTickSeconds;
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
}

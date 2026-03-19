package dev.aceclaw.llm.anthropic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves the {@code anthropic-beta} header value based on authentication mode,
 * model capabilities, and user-configured extra betas.
 *
 * <p>Inspired by OpenClaw's {@code createAnthropicBetaHeadersWrapper} pattern:
 * composable beta sets with OAuth-aware filtering and deduplication.
 */
public final class AnthropicBetaResolver {

    private static final Logger log = LoggerFactory.getLogger(AnthropicBetaResolver.class);

    /** Betas always included for both API key and OAuth modes. */
    private static final List<String> BASE_BETAS = List.of(
            "fine-grained-tool-streaming-2025-05-14"
    );

    /** Interleaved thinking beta — only needed for pre-4.6 models. */
    private static final String INTERLEAVED_THINKING_BETA = "interleaved-thinking-2025-05-14";

    /** Model prefixes that support adaptive thinking (no interleaved-thinking beta needed). */
    private static final List<String> ADAPTIVE_THINKING_MODEL_PREFIXES = List.of(
            "claude-opus-4-6", "claude-sonnet-4-6"
    );

    /** Additional betas required for OAuth token authentication. */
    private static final List<String> OAUTH_BETAS = List.of(
            "claude-code-20250219",
            "oauth-2025-04-20"
    );

    /** Beta flag for 1M context window support. */
    static final String CONTEXT_1M_BETA = "context-1m-2025-08-07";

    /** Model prefixes that support 1M context. */
    private static final List<String> CONTEXT_1M_MODEL_PREFIXES = List.of(
            "claude-opus-4", "claude-sonnet-4"
    );

    private AnthropicBetaResolver() {}

    /**
     * Resolves the complete beta header value for a request.
     *
     * @param isOAuth    whether the current auth mode is OAuth
     * @param modelId    the model being used (for context-1m eligibility check)
     * @param context1m  whether to request 1M context window
     * @param extraBetas additional user-configured betas (may be null or empty)
     * @return comma-separated beta header value
     */
    public static String resolve(boolean isOAuth, String modelId, boolean context1m, List<String> extraBetas) {
        Set<String> betas = new LinkedHashSet<>();

        // 1. OAuth-specific betas first (order: OAuth betas, then base betas)
        if (isOAuth) {
            betas.addAll(OAUTH_BETAS);
        }

        // 2. Base betas (always included)
        betas.addAll(BASE_BETAS);

        // 3. Interleaved thinking beta — skip for adaptive thinking models (Opus 4.6, Sonnet 4.6)
        if (!isAdaptiveThinkingModel(modelId)) {
            betas.add(INTERLEAVED_THINKING_BETA);
        }

        // 4. Context-1M beta (conditional)
        if (context1m) {
            if (isOAuth) {
                log.warn("Ignoring context-1m beta for OAuth token auth on model {}; "
                        + "Anthropic rejects context-1m beta with OAuth auth", modelId);
            } else if (!isContext1mEligible(modelId)) {
                log.warn("Ignoring context-1m for non-opus/sonnet model: {}", modelId);
            } else {
                betas.add(CONTEXT_1M_BETA);
            }
        }

        // 5. User-configured extra betas (with OAuth filtering)
        if (extraBetas != null) {
            for (String beta : extraBetas) {
                if (beta == null || beta.isBlank()) {
                    continue;
                }
                String trimmed = beta.trim();
                // Filter out context-1m if OAuth (Anthropic returns 400)
                if (isOAuth && CONTEXT_1M_BETA.equals(trimmed)) {
                    log.warn("Filtering out context-1m from extra betas in OAuth mode");
                    continue;
                }
                betas.add(trimmed);
            }
        }

        return String.join(",", betas);
    }

    /**
     * Resolves betas with no extra configuration (backward compatible).
     */
    public static String resolve(boolean isOAuth) {
        return resolve(isOAuth, null, false, null);
    }

    /**
     * Checks whether a model uses adaptive thinking (Opus 4.6, Sonnet 4.6).
     * These models use {@code {"type":"adaptive"}} instead of budget-based thinking,
     * and do not need the interleaved-thinking beta.
     */
    public static boolean isAdaptiveThinkingModel(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return false;
        }
        String normalized = modelId.trim().toLowerCase();
        return ADAPTIVE_THINKING_MODEL_PREFIXES.stream().anyMatch(normalized::startsWith);
    }

    /**
     * Checks whether a model supports 1M context window.
     */
    static boolean isContext1mEligible(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return false;
        }
        String normalized = modelId.trim().toLowerCase();
        return CONTEXT_1M_MODEL_PREFIXES.stream().anyMatch(normalized::startsWith);
    }
}

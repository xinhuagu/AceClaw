package dev.aceclaw.daemon;

import java.util.Locale;
import java.util.Map;

/**
 * Normalized cost multipliers for GitHub Copilot premium requests, keyed by model.
 *
 * <p>Copilot charges differ per underlying model — e.g. Claude Opus runs at 3x while
 * GPT-4o runs at 0x on paid plans — so raw request counts across models aren't
 * directly comparable. Exporting a multiplier alongside the per-source attribution
 * lets offline analysis translate request counts into normalized cost units for
 * apples-to-apples comparison.
 *
 * <p>Keys follow the Copilot-native identifier form (dot notation for Claude, as-is
 * for others). AceClaw's {@code LlmClientFactory} routes Anthropic-style hyphenated
 * names through a translator before they reach Copilot, so by the time a model id
 * arrives here via {@code LlmClient.defaultModel()} or the {@code /model} override,
 * it is already in Copilot form. Hyphenated aliases for the same Claude models are
 * included defensively for the edge case where a caller passes the pre-translation
 * form.
 *
 * <p>Values reflect the GitHub Copilot premium-request rate card current as of
 * {@value #RATE_CARD_DATE}. Rates change as GitHub tunes pricing; the table is
 * intentionally small and named-literal rather than scraped — update via PR when
 * the rate card shifts.
 *
 * <p>Unknown Copilot model → {@link #DEFAULT_COPILOT_MULTIPLIER} (the common-case
 * rate). Non-Copilot provider → {@code null} (the multiplier concept doesn't apply
 * to token-priced providers like direct Anthropic or OpenAI).
 */
final class CopilotRequestMultipliers {

    static final String RATE_CARD_DATE = "2026-04";
    static final double DEFAULT_COPILOT_MULTIPLIER = 1.0;

    /**
     * Copilot-canonical model id → premium-request multiplier.
     * Dot-notation Claude ids match the form produced by
     * {@code LlmClientFactory#ANTHROPIC_TO_COPILOT_MODEL}; non-Claude models are used as-is.
     */
    private static final Map<String, Double> COPILOT = Map.ofEntries(
            // Anthropic via Copilot (Copilot form: dot notation)
            Map.entry("claude-sonnet-4.5", 1.0),
            Map.entry("claude-sonnet-4.6", 1.0),
            Map.entry("claude-opus-4.5", 3.0),
            Map.entry("claude-opus-4.6", 3.0),
            Map.entry("claude-haiku-4.5", 0.33),
            Map.entry("claude-3-5-sonnet", 1.0),
            // OpenAI via Copilot
            Map.entry("gpt-4o", 0.0),
            Map.entry("gpt-4.1", 0.0),
            Map.entry("gpt-5", 1.0),
            Map.entry("gpt-5.4-mini", 0.33),
            Map.entry("o1", 10.0),
            Map.entry("o1-preview", 10.0),
            Map.entry("o3-mini", 0.33),
            Map.entry("raptor-mini", 0.0),
            // Google via Copilot
            Map.entry("gemini-2.0-flash", 0.25),
            Map.entry("gemini-2.5-pro", 1.0),
            // xAI via Copilot
            Map.entry("grok-code-fast-1", 0.25)
    );

    /**
     * Hyphenated Anthropic-direct ids → their Copilot dot-notation equivalent. Covers the
     * case where the pre-translation form leaks through (e.g. a caller reading the daemon
     * config instead of the resolved Copilot model).
     */
    private static final Map<String, String> HYPHEN_TO_DOT_CLAUDE = Map.ofEntries(
            Map.entry("claude-sonnet-4-5", "claude-sonnet-4.5"),
            Map.entry("claude-sonnet-4-6", "claude-sonnet-4.6"),
            Map.entry("claude-opus-4-5", "claude-opus-4.5"),
            Map.entry("claude-opus-4-6", "claude-opus-4.6"),
            Map.entry("claude-haiku-4-5", "claude-haiku-4.5")
    );

    private CopilotRequestMultipliers() {}

    /**
     * Returns the normalized premium-request multiplier for a given provider + model,
     * or {@code null} if the provider is not Copilot.
     *
     * <p>Normalization handles:
     * <ol>
     *   <li>Case: lowercases before lookup.</li>
     *   <li>Date suffix: strips a trailing 8-digit suffix
     *       ({@code claude-opus-4.6-20250929} → {@code claude-opus-4.6}).</li>
     *   <li>Anthropic-style hyphens: maps {@code claude-opus-4-6} →
     *       {@code claude-opus-4.6} before table lookup.</li>
     * </ol>
     */
    static Double forProviderAndModel(String provider, String model) {
        if (provider == null || !"copilot".equalsIgnoreCase(provider)) {
            return null;
        }
        if (model == null || model.isBlank()) {
            return DEFAULT_COPILOT_MULTIPLIER;
        }
        String normalized = normalizeModelId(model);
        return COPILOT.getOrDefault(normalized, DEFAULT_COPILOT_MULTIPLIER);
    }

    /** Visible for test. */
    static String normalizeModelId(String model) {
        String normalized = model.toLowerCase(Locale.ROOT).trim();
        // Strip trailing 8-digit date suffix: "claude-opus-4.6-20250929" -> "claude-opus-4.6"
        int dashDate = normalized.lastIndexOf('-');
        if (dashDate > 0 && dashDate < normalized.length() - 1) {
            String tail = normalized.substring(dashDate + 1);
            if (tail.length() == 8 && tail.chars().allMatch(Character::isDigit)) {
                normalized = normalized.substring(0, dashDate);
            }
        }
        // Anthropic hyphen form → Copilot dot form. Applied after date-suffix stripping so
        // "claude-opus-4-6-20250929" first becomes "claude-opus-4-6" then "claude-opus-4.6".
        String dotted = HYPHEN_TO_DOT_CLAUDE.get(normalized);
        return dotted != null ? dotted : normalized;
    }
}

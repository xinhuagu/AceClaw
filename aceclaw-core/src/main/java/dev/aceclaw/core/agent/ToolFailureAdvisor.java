package dev.aceclaw.core.agent;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Detects repeated tool failures within a single turn and emits generic fallback advice.
 *
 * <p>This is intentionally tool-agnostic: it classifies failure text into broad categories
 * (permissions, timeout, capability mismatch, etc.) and advises a strategy pivot when the
 * same category repeats for the same tool.
 */
final class ToolFailureAdvisor {

    private static final int REPEAT_THRESHOLD = 2;
    private static final int BLOCK_THRESHOLD = 3;

    private final Map<Key, Integer> counts = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, BlockedToolState> blockedTools = new java.util.concurrent.ConcurrentHashMap<>();

    String maybeAdvice(String toolName, String errorText) {
        return onFailure(toolName, errorText).advice();
    }

    FailureDecision onFailure(String toolName, String errorText) {
        Objects.requireNonNull(toolName, "toolName");
        var category = classify(errorText);
        var key = new Key(toolName, category);
        int count = counts.merge(key, 1, Integer::sum);
        String advice = null;
        if (count >= REPEAT_THRESHOLD) {
            advice = buildAdvice(toolName, category, count);
        }
        boolean block = count >= BLOCK_THRESHOLD && isBlockEligible(category);
        if (block) {
            blockedTools.put(toolName, new BlockedToolState(category, count, advice != null ? advice : ""));
        }
        return new FailureDecision(category, count, advice, block);
    }

    String preflightBlockMessage(String toolName) {
        var blocked = blockedTools.get(toolName);
        if (blocked == null) {
            return null;
        }
        String summary = ("Tool circuit-breaker active: tool=%s class=%s failures=%d. " +
                "This path is temporarily blocked for the current turn to avoid repeated non-progressing retries.")
                .formatted(toolName, blocked.category().name().toLowerCase(Locale.ROOT), blocked.count());
        if (blocked.advice() == null || blocked.advice().isBlank()) {
            return summary;
        }
        return summary + " " + blocked.advice();
    }

    static FailureCategory classify(String errorText) {
        if (errorText == null || errorText.isBlank()) {
            return FailureCategory.UNKNOWN;
        }
        String text = errorText.toLowerCase(Locale.ROOT);
        if (containsAny(text, "permission denied", "not permitted", "access denied", "forbidden")) {
            return FailureCategory.PERMISSION;
        }
        if (containsAny(text, "timed out", "timeout", "deadline exceeded")) {
            return FailureCategory.TIMEOUT;
        }
        if (containsAny(text, "module not found", "nomodule", "command not found",
                "cannot import", "no module named", "not installed")) {
            return FailureCategory.DEPENDENCY_OR_ENV;
        }
        if (containsAny(text, "no such file", "does not exist", "cannot find", "file not found", "path not found")) {
            return FailureCategory.PATH;
        }
        if (containsAny(text, "unsupported", "not supported", "unknown format", "invalid format",
                "not a zip file", "encrypted", "cannot parse", "parse error")) {
            return FailureCategory.CAPABILITY_MISMATCH;
        }
        if (containsWholeWord(text, "ole") || containsWholeWord(text, "irm")
                || containsAny(text, "ole format", "ole2", "irm protection")) {
            return FailureCategory.CAPABILITY_MISMATCH;
        }
        if (containsAny(text, "connection refused", "network is unreachable", "dns", "ssl",
                "certificate", "connection reset")) {
            return FailureCategory.NETWORK;
        }
        if (containsAny(text, "no match found", "no lines matched", "files differ", "exit code: 1 —")) {
            return FailureCategory.NO_MATCH;
        }
        return FailureCategory.UNKNOWN;
    }

    private static String buildAdvice(String toolName, FailureCategory category, int count) {
        String classHint = category.name().toLowerCase(Locale.ROOT);
        String strategy = switch (category) {
            case PERMISSION -> "Request/adjust permission scope, or switch to a read-only/approved path.";
            case TIMEOUT -> "Narrow the operation scope, split into smaller steps, or adjust timeout safely.";
            case PATH -> "Resolve/verify paths first (list/glob) before retrying the same command.";
            case DEPENDENCY_OR_ENV ->
                    "Use the correct runtime/environment, install missing dependency, or switch tools/channels.";
            case CAPABILITY_MISMATCH ->
                    "Inspect input capabilities/format first, then switch to a tool that natively supports it.";
            case NETWORK -> "Retry with backoff, reduce remote dependency, or pivot to local/offline approach.";
            case NO_MATCH -> "No results found is normal; try different search terms or broaden the pattern.";
            case UNKNOWN -> "Avoid blind retry; inspect exact error and pivot to an alternative approach.";
        };
        return ("Repeated non-progressing failures detected: tool=%s class=%s count=%d. " +
                "Stop retrying the same path. %s").formatted(toolName, classHint, count, strategy);
    }

    private static boolean containsAny(String text, String... needles) {
        for (var needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsWholeWord(String text, String word) {
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(text).find();
    }

    private static boolean isBlockEligible(FailureCategory category) {
        return switch (category) {
            case DEPENDENCY_OR_ENV, CAPABILITY_MISMATCH, PATH, UNKNOWN -> true;
            case PERMISSION, TIMEOUT, NETWORK, NO_MATCH -> false;
        };
    }

    enum FailureCategory {
        PERMISSION,
        TIMEOUT,
        PATH,
        DEPENDENCY_OR_ENV,
        CAPABILITY_MISMATCH,
        NETWORK,
        NO_MATCH,
        UNKNOWN
    }

    private record Key(String toolName, FailureCategory category) {
        Key {
            Objects.requireNonNull(toolName, "toolName");
            Objects.requireNonNull(category, "category");
        }
    }

    record FailureDecision(FailureCategory category, int count, String advice, boolean blockTool) {
        FailureDecision {
            Objects.requireNonNull(category, "category");
        }
    }

    private record BlockedToolState(FailureCategory category, int count, String advice) {
        BlockedToolState {
            Objects.requireNonNull(category, "category");
        }
    }
}

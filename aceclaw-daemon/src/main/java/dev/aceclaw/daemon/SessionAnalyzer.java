package dev.aceclaw.daemon;

import dev.aceclaw.core.agent.ToolMetrics;
import dev.aceclaw.memory.MemoryEntry;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Retrospective session analysis over the full conversation history.
 *
 * <p>This complements {@link SessionEndExtractor}: it focuses on end-to-end
 * session shape, backtracking, file/command extraction, and a compact summary
 * suitable for journaling.
 */
public final class SessionAnalyzer {

    private static final Pattern FILE_PATH_PATTERN =
            Pattern.compile("(?:^|\\s|[\"'`])((?:/|\\./|\\.\\./|[\\w.-]+/)[\\w./-]+(?:\\.[a-zA-Z]{1,10})?)");
    private static final Pattern SHELL_COMMAND_PATTERN = Pattern.compile(
            "^(?:\\$\\s*)?(?:bash|sh|zsh|git|./gradlew|gradle|mvn|npm|pnpm|yarn|python|python3|pytest|cargo|java|node|docker|kubectl|curl|sed|awk|rg|cat|ls|cp|mv)\\b.*");
    private static final List<Pattern> ERROR_PATTERNS = List.of(
            Pattern.compile("(?i)\\berror\\b"),
            Pattern.compile("(?i)\\bfailed\\b"),
            Pattern.compile("(?i)\\bexception\\b"),
            Pattern.compile("(?i)\\bnot found\\b"),
            Pattern.compile("(?i)\\bdenied\\b"),
            Pattern.compile("(?i)\\btimeout\\b"),
            Pattern.compile("(?i)\\btraceback\\b"));
    private static final List<Pattern> BACKTRACKING_PATTERNS = List.of(
            Pattern.compile("(?i)\\btry again\\b"),
            Pattern.compile("(?i)\\bdifferent path\\b"),
            Pattern.compile("(?i)\\binstead\\b"),
            Pattern.compile("(?i)\\bundo\\b"),
            Pattern.compile("(?i)\\brevert\\b"),
            Pattern.compile("(?i)\\brollback\\b"),
            Pattern.compile("(?i)^wrong\\b"),
            Pattern.compile("(?i)^no[,\\.]?\\s"));

    public record SessionInsight(
            MemoryEntry.Category category,
            String content,
            List<String> tags
    ) {
        public SessionInsight {
            tags = tags != null ? List.copyOf(tags) : List.of();
        }
    }

    public record SessionLearnings(
            List<SessionInsight> insights,
            List<String> extractedFilePaths,
            List<String> executedCommands,
            List<String> errorsEncountered,
            String sessionSummary,
            Map<String, ToolMetrics> toolMetrics,
            boolean backtrackingDetected,
            String endToEndStrategy
    ) {
        public SessionLearnings {
            insights = insights != null ? List.copyOf(insights) : List.of();
            extractedFilePaths = extractedFilePaths != null ? List.copyOf(extractedFilePaths) : List.of();
            executedCommands = executedCommands != null ? List.copyOf(executedCommands) : List.of();
            errorsEncountered = errorsEncountered != null ? List.copyOf(errorsEncountered) : List.of();
            sessionSummary = sessionSummary != null ? sessionSummary : "";
            toolMetrics = toolMetrics != null ? Map.copyOf(toolMetrics) : Map.of();
            endToEndStrategy = endToEndStrategy != null ? endToEndStrategy : "";
        }
    }

    public SessionLearnings analyze(
            List<AgentSession.ConversationMessage> fullHistory,
            Map<String, ToolMetrics> toolMetrics) {

        if (fullHistory == null || fullHistory.isEmpty()) {
            return new SessionLearnings(
                    List.of(), List.of(), List.of(), List.of(), "", Map.of(), false, "");
        }

        var history = fullHistory.stream()
                .filter(Objects::nonNull)
                .toList();
        if (history.isEmpty()) {
            return new SessionLearnings(
                    List.of(), List.of(), List.of(), List.of(), "", Map.of(), false, "");
        }

        var files = extractFilePaths(history);
        var commands = extractCommands(history);
        var errors = extractErrors(history);
        boolean backtracking = detectBacktracking(history, files, errors);
        String strategy = buildStrategy(files, commands, errors, backtracking);
        String summary = buildSummary(history, files, commands, errors, backtracking, strategy);
        var insights = buildInsights(files, commands, errors, backtracking, strategy, summary);

        return new SessionLearnings(
                insights,
                files,
                commands,
                errors,
                summary,
                toolMetrics != null ? Map.copyOf(toolMetrics) : Map.of(),
                backtracking,
                strategy);
    }

    private static List<String> extractFilePaths(List<AgentSession.ConversationMessage> history) {
        var files = new LinkedHashSet<String>();
        for (var message : history) {
            String content = contentOf(message);
            if (content == null || content.isBlank()) {
                continue;
            }
            var matcher = FILE_PATH_PATTERN.matcher(content);
            while (matcher.find()) {
                files.add(matcher.group(1));
            }
        }
        return files.stream().limit(8).toList();
    }

    private static List<String> extractCommands(List<AgentSession.ConversationMessage> history) {
        var commands = new LinkedHashSet<String>();
        for (var message : history) {
            String content = contentOf(message);
            if (content == null || content.isBlank()) {
                continue;
            }
            for (var line : content.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (SHELL_COMMAND_PATTERN.matcher(trimmed).matches()) {
                    commands.add(trimmed.startsWith("$ ") ? trimmed.substring(2).trim() : trimmed);
                }
            }
        }
        return commands.stream().limit(6).toList();
    }

    private static List<String> extractErrors(List<AgentSession.ConversationMessage> history) {
        var errors = new LinkedHashSet<String>();
        for (var message : history) {
            String content = contentOf(message);
            if (content == null || content.isBlank()) {
                continue;
            }
            for (var line : content.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (matchesAny(trimmed, ERROR_PATTERNS)) {
                    errors.add(truncate(trimmed, 160));
                }
            }
        }
        return errors.stream().limit(6).toList();
    }

    private static boolean detectBacktracking(
            List<AgentSession.ConversationMessage> history,
            List<String> files,
            List<String> errors) {

        int correctionLikeMessages = 0;
        boolean explicitBacktracking = false;
        for (var message : history) {
            String content = contentOf(message);
            if (content == null || content.isBlank()) {
                continue;
            }
            if (message instanceof AgentSession.ConversationMessage.User
                    && SessionEndExtractor.looksLikeCorrection(content)) {
                correctionLikeMessages++;
            }
            if (matchesAny(content, BACKTRACKING_PATTERNS)) {
                explicitBacktracking = true;
            }
        }

        boolean repeatedFileTouch = files.size() >= 1 && history.stream()
                .map(SessionAnalyzer::contentOf)
                .filter(Objects::nonNull)
                .anyMatch(content -> files.stream().anyMatch(file -> countOccurrences(content, file) > 1));

        return explicitBacktracking
                || (correctionLikeMessages > 0 && !errors.isEmpty())
                || (correctionLikeMessages >= 2)
                || (repeatedFileTouch && !errors.isEmpty());
    }

    private static String buildStrategy(
            List<String> files,
            List<String> commands,
            List<String> errors,
            boolean backtracking) {

        var parts = new ArrayList<String>();
        if (!files.isEmpty()) {
            parts.add("inspect files like " + joinPreview(files, 2));
        }
        if (!commands.isEmpty()) {
            parts.add("run commands such as " + joinPreview(commands, 2));
        }
        if (!errors.isEmpty()) {
            parts.add("address errors before the final pass");
        }
        if (backtracking) {
            parts.add("adjust course after corrections");
        }
        if (parts.isEmpty()) {
            return "The session focused on progressive reasoning and direct iteration with the user.";
        }
        return "The end-to-end strategy was to " + String.join(", then ", parts) + ".";
    }

    private static String buildSummary(
            List<AgentSession.ConversationMessage> history,
            List<String> files,
            List<String> commands,
            List<String> errors,
            boolean backtracking,
            String strategy) {

        int userMessages = 0;
        int assistantMessages = 0;
        for (var message : history) {
            if (message instanceof AgentSession.ConversationMessage.User) {
                userMessages++;
            } else if (message instanceof AgentSession.ConversationMessage.Assistant) {
                assistantMessages++;
            }
        }

        var sentences = new ArrayList<String>();
        sentences.add("Session retrospective: " + history.size() + " messages (" + userMessages
                + " user, " + assistantMessages + " assistant), " + files.size()
                + " files touched, and " + commands.size() + " commands surfaced.");
        sentences.add(strategy);
        if (backtracking || !errors.isEmpty()) {
            sentences.add("The session hit " + errors.size()
                    + " error signals and required backtracking before converging.");
        } else {
            sentences.add("The session moved forward without major backtracking.");
        }
        return String.join(" ", sentences);
    }

    private static List<SessionInsight> buildInsights(
            List<String> files,
            List<String> commands,
            List<String> errors,
            boolean backtracking,
            String strategy,
            String summary) {

        var insights = new ArrayList<SessionInsight>();
        if (files.size() >= 2) {
            insights.add(new SessionInsight(
                    MemoryEntry.Category.CODEBASE_INSIGHT,
                    truncate("Session touched files including " + joinPreview(files, 4), 200),
                    List.of("session-analysis", "files")));
        }
        if (!commands.isEmpty()) {
            insights.add(new SessionInsight(
                    MemoryEntry.Category.TOOL_USAGE,
                    "Session relied on shell commands during investigation and execution.",
                    List.of("session-analysis", "commands")));
        }
        if (!errors.isEmpty()) {
            insights.add(new SessionInsight(
                    MemoryEntry.Category.ERROR_RECOVERY,
                    "Session encountered runtime errors and iterated to recover before completion.",
                    List.of("session-analysis", "errors")));
        }
        if (backtracking) {
            insights.add(new SessionInsight(
                    MemoryEntry.Category.ANTI_PATTERN,
                    "Session needed backtracking after errors or user corrections before reaching a stable result.",
                    List.of("session-analysis", "backtracking")));
        }
        if (!strategy.isBlank()) {
            insights.add(new SessionInsight(
                    MemoryEntry.Category.SUCCESSFUL_STRATEGY,
                    truncate(strategy, 200),
                    List.of("session-analysis", "strategy")));
        }
        return List.copyOf(insights);
    }

    private static String contentOf(AgentSession.ConversationMessage message) {
        return switch (message) {
            case AgentSession.ConversationMessage.User user -> user.content();
            case AgentSession.ConversationMessage.Assistant assistant -> assistant.content();
            case AgentSession.ConversationMessage.System system -> system.content();
        };
    }

    private static boolean matchesAny(String text, List<Pattern> patterns) {
        for (var pattern : patterns) {
            if (pattern.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int idx = text.indexOf(needle, from);
            if (idx < 0) {
                return count;
            }
            count++;
            from = idx + needle.length();
        }
    }

    private static String joinPreview(List<String> values, int limit) {
        return values.stream().limit(limit).reduce((a, b) -> a + ", " + b).orElse("");
    }

    private static String truncate(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, Math.max(0, max - 3)) + "...";
    }
}

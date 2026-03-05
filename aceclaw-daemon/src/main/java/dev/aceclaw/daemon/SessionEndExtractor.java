package dev.aceclaw.daemon;

import dev.aceclaw.memory.MemoryEntry;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Extracts learnings from conversation history when a session ends.
 *
 * <p>Uses conservative heuristics (no LLM calls) to identify user corrections,
 * explicit preferences, and modified file summaries. False negatives are preferred
 * over false positives to avoid memory pollution.
 */
public final class SessionEndExtractor {

    private SessionEndExtractor() {}

    /**
     * A memory extracted from conversation history.
     *
     * @param category the memory category
     * @param content  the extracted insight (max 200 chars)
     * @param tags     searchable tags
     */
    public record ExtractedMemory(
            MemoryEntry.Category category,
            String content,
            List<String> tags
    ) {}

    // Correction indicators (case-insensitive, matched at word boundaries or start of message)
    private static final List<Pattern> CORRECTION_PATTERNS = List.of(
            Pattern.compile("(?i)^no[,.]\\s"),
            Pattern.compile("(?i)^wrong[,.]?\\s"),
            Pattern.compile("(?i)^actually[,.]\\s"),
            Pattern.compile("(?i)\\bshould be\\b"),
            Pattern.compile("(?i)\\binstead of\\b"),
            Pattern.compile("(?i)\\bnot\\s+\\w+[,.]?\\s+use\\b"),
            Pattern.compile("(?i)^please use\\b"),
            Pattern.compile("(?i)\\bdon'?t use\\b"),
            Pattern.compile("(?i)^stop\\b"),
            Pattern.compile("(?i)^not that\\b"),
            Pattern.compile("(?i)\\bshould(n'?t| not)\\b"),
            Pattern.compile("(?i)\\bdo not use\\b"),
            Pattern.compile("(?i)^use\\s+\\w"),
            Pattern.compile("(?i)\\bi (said|told you|already said)\\b"),
            Pattern.compile("(?i)\\bthat('s| is) not\\b"),
            Pattern.compile("(?i)^why did you\\b")
    );

    // Preference indicators (case-insensitive)
    private static final List<Pattern> PREFERENCE_PATTERNS = List.of(
            Pattern.compile("(?i)\\balways\\b"),
            Pattern.compile("(?i)\\bnever\\b"),
            Pattern.compile("(?i)\\bprefer\\b"),
            Pattern.compile("(?i)\\bi like\\b"),
            Pattern.compile("(?i)\\bi want\\b"),
            Pattern.compile("(?i)\\bfrom now on\\b"),
            Pattern.compile("(?i)\\bdon'?t ever\\b")
    );

    // File modification indicators in assistant messages
    private static final List<Pattern> FILE_MOD_PATTERNS = List.of(
            Pattern.compile("(?i)\\bwrite_file\\b"),
            Pattern.compile("(?i)\\bedit_file\\b"),
            Pattern.compile("(?i)\\bFile written\\b"),
            Pattern.compile("(?i)\\bFile edited\\b")
    );

    // Error recovery indicators in assistant messages
    private static final List<Pattern> ERROR_RECOVERY_PATTERNS = List.of(
            Pattern.compile("(?i)\\bfixed by\\b"),
            Pattern.compile("(?i)\\bresolved by\\b"),
            Pattern.compile("(?i)\\bthe issue was\\b"),
            Pattern.compile("(?i)\\bthe fix was\\b"),
            Pattern.compile("(?i)\\bthe problem was\\b"),
            Pattern.compile("(?i)\\bworkaround[: ]\\b")
    );

    // Successful strategy indicators in assistant messages
    private static final List<Pattern> SUCCESS_PATTERNS = List.of(
            Pattern.compile("(?i)\\bthat worked\\b"),
            Pattern.compile("(?i)\\bsuccessfully\\b"),
            Pattern.compile("(?i)\\bproblem solved\\b"),
            Pattern.compile("(?i)\\bbuild succeeded\\b"),
            Pattern.compile("(?i)\\ball tests pass\\b")
    );

    // User feedback indicators
    private static final List<Pattern> POSITIVE_FEEDBACK_PATTERNS = List.of(
            Pattern.compile("(?i)^(great|good|perfect|excellent|nice|awesome|thanks|thank you)\\b"),
            Pattern.compile("(?i)\\bthat'?s (right|correct|exactly|perfect)\\b")
    );

    private static final List<Pattern> NEGATIVE_FEEDBACK_PATTERNS = List.of(
            Pattern.compile("(?i)^(that'?s wrong|that'?s not right|that'?s incorrect)\\b"),
            Pattern.compile("(?i)\\bnot what i (wanted|asked|meant)\\b"),
            Pattern.compile("(?i)\\byou (broke|messed up|ruined)\\b")
    );

    // Pattern to extract file paths from assistant messages
    private static final Pattern FILE_PATH_PATTERN =
            Pattern.compile("(?:^|\\s|[\"'`])((?:/|\\./|\\.\\./|[\\w.-]+/)[\\w./-]+(?:\\.[a-zA-Z]{1,10})?)");

    private static final int MAX_CONTENT_LENGTH = 200;

    /**
     * Extracts memories from a conversation history.
     *
     * @param messages the conversation messages (may be null or empty)
     * @return extracted memories (never null, may be empty)
     */
    public static List<ExtractedMemory> extract(List<AgentSession.ConversationMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        var results = new ArrayList<ExtractedMemory>();
        var seenContent = new HashSet<String>();

        // Pass 1: Scan for corrections (user response after assistant)
        for (int i = 1; i < messages.size(); i++) {
            if (messages.get(i) instanceof AgentSession.ConversationMessage.User user
                    && messages.get(i - 1) instanceof AgentSession.ConversationMessage.Assistant) {

                String text = user.content();
                if (text == null || text.isBlank()) continue;

                if (matchesAny(text, CORRECTION_PATTERNS)) {
                    String content = truncate(text);
                    if (seenContent.add(content)) {
                        results.add(new ExtractedMemory(
                                MemoryEntry.Category.CORRECTION, content,
                                List.of("user-correction")));
                    }
                }
            }
        }

        // Pass 2: Scan all user messages for explicit preferences
        for (var msg : messages) {
            if (msg instanceof AgentSession.ConversationMessage.User user) {
                String text = user.content();
                if (text == null || text.isBlank()) continue;

                if (matchesAny(text, PREFERENCE_PATTERNS)) {
                    String content = truncate(text);
                    if (seenContent.add(content)) {
                        results.add(new ExtractedMemory(
                                MemoryEntry.Category.PREFERENCE, content,
                                List.of("user-preference")));
                    }
                }
            }
        }

        // Pass 3: Collect modified files from assistant messages
        var modifiedFiles = new LinkedHashSet<String>();
        for (var msg : messages) {
            if (msg instanceof AgentSession.ConversationMessage.Assistant assistant) {
                String text = assistant.content();
                if (text == null || text.isBlank()) continue;

                if (matchesAny(text, FILE_MOD_PATTERNS)) {
                    var matcher = FILE_PATH_PATTERN.matcher(text);
                    while (matcher.find()) {
                        modifiedFiles.add(matcher.group(1));
                    }
                }
            }
        }

        if (modifiedFiles.size() >= 3) {
            var paths = modifiedFiles.stream().limit(5).toList();
            String content = "Session modified " + modifiedFiles.size() +
                    " files including: " + String.join(", ", paths);
            content = truncate(content);
            if (seenContent.add(content)) {
                results.add(new ExtractedMemory(
                        MemoryEntry.Category.CODEBASE_INSIGHT, content,
                        List.of("modified-files")));
            }
        }

        // Pass 4: Scan assistant messages for error recovery patterns
        for (var msg : messages) {
            if (msg instanceof AgentSession.ConversationMessage.Assistant assistant) {
                String text = assistant.content();
                if (text == null || text.isBlank()) continue;

                if (matchesAny(text, ERROR_RECOVERY_PATTERNS)) {
                    String content = truncate(text);
                    if (seenContent.add(content)) {
                        results.add(new ExtractedMemory(
                                MemoryEntry.Category.ERROR_RECOVERY, content,
                                List.of("error-recovery")));
                    }
                }

                if (matchesAny(text, SUCCESS_PATTERNS)) {
                    String content = truncate(text);
                    if (seenContent.add(content)) {
                        results.add(new ExtractedMemory(
                                MemoryEntry.Category.SUCCESSFUL_STRATEGY, content,
                                List.of("successful-strategy")));
                    }
                }
            }
        }

        // Pass 5: Scan user messages for explicit feedback
        for (var msg : messages) {
            if (msg instanceof AgentSession.ConversationMessage.User user) {
                String text = user.content();
                if (text == null || text.isBlank()) continue;

                if (matchesAny(text, POSITIVE_FEEDBACK_PATTERNS)) {
                    String content = truncate(text);
                    if (seenContent.add(content)) {
                        results.add(new ExtractedMemory(
                                MemoryEntry.Category.USER_FEEDBACK, content,
                                List.of("positive-feedback")));
                    }
                } else if (matchesAny(text, NEGATIVE_FEEDBACK_PATTERNS)) {
                    String content = truncate(text);
                    if (seenContent.add(content)) {
                        results.add(new ExtractedMemory(
                                MemoryEntry.Category.USER_FEEDBACK, content,
                                List.of("negative-feedback")));
                    }
                }
            }
        }

        return List.copyOf(results);
    }

    private static boolean matchesAny(String text, List<Pattern> patterns) {
        for (var pattern : patterns) {
            if (pattern.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private static String truncate(String text) {
        if (text.length() <= MAX_CONTENT_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_CONTENT_LENGTH - 3) + "...";
    }
}

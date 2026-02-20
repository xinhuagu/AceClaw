package dev.aceclaw.memory;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Classification of tool errors by type. Each class has regex patterns for
 * auto-classification and a default recovery suggestion.
 */
public enum ErrorClass {
    ENCODING(
        List.of(
            Pattern.compile("(?i)(malformed|invalid).*(byte|input|encoding|charset|utf)"),
            Pattern.compile("(?i)unmappable character")
        ),
        "Detect file encoding and retry with correct charset"
    ),
    PATH_NOT_FOUND(
        List.of(
            Pattern.compile("(?i)(file|path|directory).*(not found|does not exist|no such)"),
            Pattern.compile("(?i)no such file")
        ),
        "Verify path exists using glob search"
    ),
    PERMISSION(
        List.of(
            Pattern.compile("(?i)permission denied"),
            Pattern.compile("(?i)access.*(denied|forbidden)"),
            Pattern.compile("(?i)not readable")
        ),
        "Check file permissions or try alternative path"
    ),
    TIMEOUT(
        List.of(
            Pattern.compile("(?i)(timed? ?out|timeout)"),
            Pattern.compile("(?i)command.*killed")
        ),
        "Increase timeout or split command into smaller operations"
    ),
    SIZE_LIMIT(
        List.of(
            Pattern.compile("(?i)(too (large|big)|exceeds.*limit|truncat)"),
            Pattern.compile("(?i)file size.*(exceed|limit)")
        ),
        "Use offset/limit parameters to read file in chunks"
    ),
    SYNTAX(
        List.of(
            Pattern.compile("(?i)(syntax|parse|invalid).*error"),
            Pattern.compile("(?i)(unexpected token|invalid regex|pattern syntax)")
        ),
        "Fix syntax in the command or pattern"
    ),
    UNKNOWN(
        List.of(),
        "Review error message and try alternative approach"
    );

    private final List<Pattern> patterns;
    private final String defaultRecovery;

    ErrorClass(List<Pattern> patterns, String defaultRecovery) {
        this.patterns = patterns;
        this.defaultRecovery = defaultRecovery;
    }

    /** Returns the default recovery suggestion for this error class. */
    public String defaultRecovery() {
        return defaultRecovery;
    }

    /**
     * Classifies an error message into an ErrorClass by matching against
     * the regex patterns of each class. Returns UNKNOWN if no patterns match.
     *
     * @param errorMessage the error message to classify
     * @return the matching ErrorClass, or UNKNOWN if no match
     */
    public static ErrorClass classify(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return UNKNOWN;
        }
        for (var ec : values()) {
            if (ec == UNKNOWN) continue;
            for (var pattern : ec.patterns) {
                if (pattern.matcher(errorMessage).find()) {
                    return ec;
                }
            }
        }
        return UNKNOWN;
    }
}

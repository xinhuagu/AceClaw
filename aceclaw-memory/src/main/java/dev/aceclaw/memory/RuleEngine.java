package dev.aceclaw.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Loads rules from {@code {project}/.aceclaw/rules/*.md} and matches them
 * against file paths for contextual rule injection.
 *
 * <p>Rule file format:
 * <pre>
 * ---
 * paths:
 *   - "*.test.java"
 *   - "**&#47;*Test.java"
 * ---
 *
 * # Test conventions
 *
 * - Always use JUnit 5 @Test annotation
 * - ...
 * </pre>
 *
 * <p>Uses a simple YAML frontmatter parser (no external YAML dependency) that
 * extracts {@code paths:} entries between {@code ---} delimiters.
 */
public final class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);
    private static final String RULES_DIR = ".aceclaw/rules";

    private final List<PathBasedRule> rules;
    /** Cached PathMatcher instances keyed by glob pattern string. */
    private final Map<String, PathMatcher> matcherCache;

    private RuleEngine(List<PathBasedRule> rules) {
        this.rules = List.copyOf(rules);
        this.matcherCache = new HashMap<>();
        for (var rule : this.rules) {
            for (var pattern : rule.patterns()) {
                matcherCache.computeIfAbsent(pattern,
                        p -> FileSystems.getDefault().getPathMatcher("glob:" + p));
            }
        }
    }

    /**
     * Loads rules from the project's {@code .aceclaw/rules/} directory.
     *
     * @param projectPath the project root directory
     * @return the rule engine with all loaded rules
     */
    public static RuleEngine loadRules(Path projectPath) {
        Path rulesDir = projectPath.resolve(RULES_DIR);
        if (!Files.isDirectory(rulesDir)) {
            return new RuleEngine(List.of());
        }

        var rules = new ArrayList<PathBasedRule>();
        try (Stream<Path> stream = Files.list(rulesDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(Files::isRegularFile)
                    .forEach(file -> {
                        try {
                            var rule = parseRuleFile(file);
                            if (rule != null) {
                                rules.add(rule);
                            }
                        } catch (Exception e) {
                            log.warn("Skipping malformed rule file {}: {}",
                                    file.getFileName(), e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("Failed to scan rules directory: {}", e.getMessage());
        }

        log.debug("Loaded {} path-based rules from {}", rules.size(), rulesDir);
        return new RuleEngine(rules);
    }

    /**
     * Returns all rules whose glob patterns match the given file path.
     *
     * @param filePath the file path to match against
     * @return matching rules (may be empty)
     */
    public List<PathBasedRule> matchRules(String filePath) {
        if (filePath == null || filePath.isBlank()) return List.of();

        var matched = new ArrayList<PathBasedRule>();
        for (var rule : rules) {
            if (matchesAnyPattern(filePath, rule.patterns())) {
                matched.add(rule);
            }
        }
        return matched;
    }

    /**
     * Formats matching rules for system prompt injection based on the given file paths.
     *
     * @param filePaths the file paths to match against
     * @return formatted rules section, or empty string if no matches
     */
    public String formatForPrompt(List<String> filePaths) {
        if (filePaths == null || filePaths.isEmpty() || rules.isEmpty()) return "";

        var matchedRules = new ArrayList<PathBasedRule>();
        var seen = new java.util.HashSet<String>();
        for (var path : filePaths) {
            for (var rule : matchRules(path)) {
                if (seen.add(rule.name())) {
                    matchedRules.add(rule);
                }
            }
        }

        if (matchedRules.isEmpty()) return "";

        var sb = new StringBuilder();
        sb.append("\n\n# Path-Based Rules\n\n");
        sb.append("The following rules apply to files being worked on:\n\n");

        for (var rule : matchedRules) {
            sb.append("## ").append(rule.name()).append("\n");
            sb.append("Applies to: ").append(String.join(", ", rule.patterns())).append("\n\n");
            sb.append(rule.content().strip()).append("\n\n");
        }

        return sb.toString();
    }

    /**
     * Returns all loaded rules.
     */
    public List<PathBasedRule> rules() {
        return rules;
    }

    // -- Internal parsing -------------------------------------------------

    /**
     * Parses a rule file with YAML frontmatter.
     * Returns null if the file has no valid frontmatter or no patterns.
     */
    static PathBasedRule parseRuleFile(Path file) throws IOException {
        String content = Files.readString(file);
        String[] lines = content.split("\n");

        // Find frontmatter boundaries (--- ... ---)
        int firstDelim = -1;
        int secondDelim = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().equals("---")) {
                if (firstDelim == -1) {
                    firstDelim = i;
                } else {
                    secondDelim = i;
                    break;
                }
            }
        }

        if (firstDelim == -1 || secondDelim == -1) {
            log.debug("No frontmatter found in {}", file.getFileName());
            return null;
        }

        // Parse paths from frontmatter (simple line-by-line YAML parsing)
        var patterns = new ArrayList<String>();
        boolean inPaths = false;
        for (int i = firstDelim + 1; i < secondDelim; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.startsWith("paths:")) {
                inPaths = true;
                // Check for inline value (paths: ["a", "b"])
                String value = trimmed.substring("paths:".length()).trim();
                if (!value.isEmpty()) {
                    parseInlineList(value, patterns);
                    inPaths = false;
                }
                continue;
            }

            if (inPaths) {
                if (trimmed.startsWith("- ")) {
                    String pattern = trimmed.substring(2).trim();
                    // Remove surrounding quotes if present
                    pattern = stripQuotes(pattern);
                    if (!pattern.isEmpty()) {
                        patterns.add(pattern);
                    }
                } else if (!trimmed.isEmpty()) {
                    // End of paths list
                    inPaths = false;
                }
            }
        }

        if (patterns.isEmpty()) {
            log.debug("No patterns found in {}", file.getFileName());
            return null;
        }

        // Extract body content (everything after second ---)
        var bodyBuilder = new StringBuilder();
        for (int i = secondDelim + 1; i < lines.length; i++) {
            bodyBuilder.append(lines[i]).append("\n");
        }
        String body = bodyBuilder.toString().strip();

        String name = file.getFileName().toString().replaceAll("\\.md$", "");
        return new PathBasedRule(name, List.copyOf(patterns), body);
    }

    private boolean matchesAnyPattern(String filePath, List<String> patterns) {
        Path path = Path.of(filePath);
        for (var pattern : patterns) {
            PathMatcher matcher = matcherCache.get(pattern);
            if (matcher != null && matcher.matches(path)) {
                return true;
            }
        }
        return false;
    }

    private static void parseInlineList(String value, List<String> patterns) {
        // Handle ["pattern1", "pattern2"] format
        if (value.startsWith("[") && value.endsWith("]")) {
            String inner = value.substring(1, value.length() - 1);
            for (String part : inner.split(",")) {
                String pattern = stripQuotes(part.trim());
                if (!pattern.isEmpty()) {
                    patterns.add(pattern);
                }
            }
        }
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2) {
            if ((s.startsWith("\"") && s.endsWith("\"")) ||
                    (s.startsWith("'") && s.endsWith("'"))) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }
}

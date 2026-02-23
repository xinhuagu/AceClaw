package dev.aceclaw.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Registry of available skills discovered from project and user directories.
 *
 * <p>Skills are loaded from two locations (project skills override user skills by name):
 * <ol>
 *   <li>Project: .aceclaw/skills/name/SKILL.md (project-scoped)</li>
 *   <li>User: ~/.aceclaw/skills/name/SKILL.md (user-scoped)</li>
 * </ol>
 *
 * <p>Each skill is a directory containing at minimum a {@code SKILL.md} file with
 * YAML frontmatter (description required) and a markdown body.
 */
public final class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    private static final String SKILLS_DIR = ".aceclaw/skills";
    private static final String SKILL_FILE = "SKILL.md";

    private final Map<String, SkillConfig> registry;

    private SkillRegistry(Map<String, SkillConfig> registry) {
        this.registry = Collections.unmodifiableMap(new LinkedHashMap<>(registry));
    }

    /**
     * Discovers and loads skills from project and user directories.
     * Project skills take precedence over user skills with the same name.
     *
     * @param projectPath the project root directory
     * @return a registry with all discovered skills
     */
    public static SkillRegistry load(Path projectPath) {
        var map = new LinkedHashMap<String, SkillConfig>();

        // 1. Load user-scoped skills first (lower priority)
        userSkillsDir().ifPresentOrElse(
                skillsDir -> loadSkillsFromDir(skillsDir, map),
                () -> log.debug("Skipping user-scoped skills: system property 'user.home' is not set"));

        // 2. Load project-scoped skills (override user skills by name)
        Path projectSkillsDir = projectPath.resolve(SKILLS_DIR);
        loadSkillsFromDir(projectSkillsDir, map);

        if (!map.isEmpty()) {
            log.info("Skill registry loaded: {}", map.keySet());
        } else {
            log.debug("No skills found in project or user directories");
        }

        return new SkillRegistry(map);
    }

    private static Optional<Path> userSkillsDir() {
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Path.of(userHome, SKILLS_DIR));
    }

    /**
     * Creates an empty registry (no skills available).
     */
    public static SkillRegistry empty() {
        return new SkillRegistry(Map.of());
    }

    /**
     * Looks up a skill by name.
     */
    public Optional<SkillConfig> get(String name) {
        return Optional.ofNullable(registry.get(name));
    }

    /**
     * Returns all registered skill names (ordered: user skills first, then project overrides).
     */
    public List<String> names() {
        return List.copyOf(registry.keySet());
    }

    /**
     * Returns all registered skill configurations.
     */
    public List<SkillConfig> all() {
        return List.copyOf(registry.values());
    }

    /**
     * Returns true if the registry has no skills.
     */
    public boolean isEmpty() {
        return registry.isEmpty();
    }

    /**
     * Formats a compact description of all available skills for system prompt injection.
     * Only includes skills where {@code disableModelInvocation} is false.
     *
     * @return formatted skill descriptions, or empty string if no skills
     */
    public String formatDescriptions() {
        var modelVisible = registry.values().stream()
                .filter(s -> !s.disableModelInvocation())
                .toList();

        if (modelVisible.isEmpty()) {
            return "";
        }

        var sb = new StringBuilder();
        sb.append("# Available Skills\n\n");
        sb.append("You can invoke these skills using the `skill` tool. ");
        sb.append("Each skill provides specialized instructions and context for a specific task type.\n\n");

        for (var skill : modelVisible) {
            sb.append("- **").append(skill.name()).append("**");
            if (!skill.description().isEmpty()) {
                sb.append(": ").append(skill.description());
            }
            if (skill.argumentHint() != null && !skill.argumentHint().isEmpty()) {
                sb.append(" (args: `").append(skill.argumentHint()).append("`)");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    // -- Skill discovery and parsing ------------------------------------------

    private static void loadSkillsFromDir(Path skillsDir, Map<String, SkillConfig> map) {
        if (!Files.isDirectory(skillsDir)) {
            return;
        }

        try (Stream<Path> stream = Files.list(skillsDir)) {
            stream.filter(Files::isDirectory)
                    .forEach(dir -> {
                        Path skillFile = dir.resolve(SKILL_FILE);
                        if (!Files.isRegularFile(skillFile)) {
                            log.debug("Skipping skill directory without SKILL.md: {}", dir.getFileName());
                            return;
                        }
                        try {
                            var config = parseSkillFile(skillFile, dir);
                            if (config != null) {
                                if (map.containsKey(config.name())) {
                                    log.info("Skill '{}' overridden by {}", config.name(), skillsDir);
                                }
                                map.put(config.name(), config);
                            }
                        } catch (Exception e) {
                            log.warn("Skipping malformed skill {}: {}",
                                    dir.getFileName(), e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("Failed to scan skills directory {}: {}", skillsDir, e.getMessage());
        }
    }

    /**
     * Parses a SKILL.md file with YAML frontmatter.
     * Returns null if the file has no valid frontmatter or no description.
     */
    static SkillConfig parseSkillFile(Path file, Path skillDir) throws IOException {
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
            log.debug("No frontmatter found in {}", file);
            return null;
        }

        // Parse frontmatter fields
        String name = null;
        String description = null;
        String argumentHint = null;
        String contextStr = null;
        String model = null;
        var allowedTools = new ArrayList<String>();
        int maxTurns = SkillConfig.DEFAULT_MAX_TURNS;
        boolean userInvocable = true;
        boolean disableModelInvocation = false;

        String currentList = null;
        for (int i = firstDelim + 1; i < secondDelim; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.startsWith("name:")) {
                currentList = null;
                name = stripQuotes(trimmed.substring("name:".length()).trim());
            } else if (trimmed.startsWith("description:")) {
                currentList = null;
                description = stripQuotes(trimmed.substring("description:".length()).trim());
            } else if (trimmed.startsWith("argument-hint:")) {
                currentList = null;
                argumentHint = stripQuotes(trimmed.substring("argument-hint:".length()).trim());
            } else if (trimmed.startsWith("context:")) {
                currentList = null;
                contextStr = stripQuotes(trimmed.substring("context:".length()).trim());
            } else if (trimmed.startsWith("model:")) {
                currentList = null;
                model = stripQuotes(trimmed.substring("model:".length()).trim());
                if (model.isEmpty()) model = null;
            } else if (trimmed.startsWith("max-turns:")) {
                currentList = null;
                try {
                    maxTurns = Integer.parseInt(trimmed.substring("max-turns:".length()).trim());
                } catch (NumberFormatException e) {
                    // keep default
                }
            } else if (trimmed.startsWith("user-invocable:")) {
                currentList = null;
                userInvocable = parseBoolean(trimmed.substring("user-invocable:".length()).trim(), true);
            } else if (trimmed.startsWith("disable-model-invocation:")) {
                currentList = null;
                disableModelInvocation = parseBoolean(
                        trimmed.substring("disable-model-invocation:".length()).trim(), false);
            } else if (trimmed.startsWith("allowed-tools:")) {
                currentList = "allowed";
                String value = trimmed.substring("allowed-tools:".length()).trim();
                if (!value.isEmpty()) {
                    parseInlineList(value, allowedTools);
                    currentList = null;
                }
            } else if (currentList != null && trimmed.startsWith("- ")) {
                String item = stripQuotes(trimmed.substring(2).trim());
                if (!item.isEmpty()) {
                    allowedTools.add(item);
                }
            } else if (!trimmed.isEmpty() && currentList != null) {
                currentList = null;
            }
        }

        // Derive name from directory name if not explicitly set
        if (name == null || name.isEmpty()) {
            name = skillDir.getFileName().toString();
        }

        // Description is required
        if (description == null || description.isEmpty()) {
            log.warn("Skill {} has no description, skipping", name);
            return null;
        }

        // Parse execution context
        SkillConfig.ExecutionContext context = SkillConfig.ExecutionContext.INLINE;
        if (contextStr != null && !contextStr.isEmpty()) {
            try {
                context = SkillConfig.ExecutionContext.valueOf(contextStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("Invalid context '{}' for skill {}, defaulting to INLINE", contextStr, name);
            }
        }

        // Extract body (everything after second ---)
        var bodyBuilder = new StringBuilder();
        for (int i = secondDelim + 1; i < lines.length; i++) {
            bodyBuilder.append(lines[i]).append("\n");
        }
        String body = bodyBuilder.toString().strip();

        return new SkillConfig(name, description, argumentHint, context, model,
                allowedTools, maxTurns, userInvocable, disableModelInvocation, body, skillDir);
    }

    private static void parseInlineList(String value, List<String> target) {
        if (value.startsWith("[") && value.endsWith("]")) {
            String inner = value.substring(1, value.length() - 1);
            for (String part : inner.split(",")) {
                String item = stripQuotes(part.trim());
                if (!item.isEmpty()) {
                    target.add(item);
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

    private static boolean parseBoolean(String value, boolean defaultValue) {
        String v = stripQuotes(value).toLowerCase();
        if ("true".equals(v) || "yes".equals(v)) return true;
        if ("false".equals(v) || "no".equals(v)) return false;
        return defaultValue;
    }
}

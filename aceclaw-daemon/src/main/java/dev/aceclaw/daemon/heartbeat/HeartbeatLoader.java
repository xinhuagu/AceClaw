package dev.aceclaw.daemon.heartbeat;

import dev.aceclaw.daemon.cron.CronExpression;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses HEARTBEAT.md files into {@link HeartbeatTask} objects.
 *
 * <p>Discovery order (same as {@code BootExecutor}):
 * <ol>
 *   <li>Project-level: {@code {workingDir}/.aceclaw/HEARTBEAT.md}</li>
 *   <li>Global: {@code {homeDir}/HEARTBEAT.md}</li>
 * </ol>
 *
 * <p>Format:
 * <pre>
 * ## Task Name
 * - schedule: 0 9 * * 1-5
 * - timeout: 120
 * - tools: bash, read_file
 *
 * Natural-language prompt for the agent.
 * </pre>
 */
public final class HeartbeatLoader {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatLoader.class);

    private static final Pattern HEADING_PATTERN = Pattern.compile("^##\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern SCHEDULE_PATTERN = Pattern.compile("^-\\s*schedule:\\s*(.+)$", Pattern.MULTILINE);
    private static final Pattern TIMEOUT_PATTERN = Pattern.compile("^-\\s*timeout:\\s*(\\d+)\\s*$", Pattern.MULTILINE);
    private static final Pattern TOOLS_PATTERN = Pattern.compile("^-\\s*tools:\\s*(.+)$", Pattern.MULTILINE);
    private static final Pattern METADATA_LINE = Pattern.compile("^-\\s*(schedule|timeout|tools):\\s*.+$", Pattern.MULTILINE);

    private HeartbeatLoader() {}

    /**
     * Discovers HEARTBEAT.md files in priority order (project first, then global).
     *
     * @param homeDir    daemon home directory (~/.aceclaw)
     * @param workingDir project working directory (may be null)
     * @return immutable list of discovered file paths
     */
    public static List<Path> discoverFiles(Path homeDir, Path workingDir) {
        var found = new ArrayList<Path>();

        // Project-level: {workingDir}/.aceclaw/HEARTBEAT.md
        if (workingDir != null) {
            Path projectFile = workingDir.resolve(".aceclaw").resolve("HEARTBEAT.md");
            if (Files.isRegularFile(projectFile)) {
                found.add(projectFile);
            }
        }

        // Global: {homeDir}/HEARTBEAT.md
        if (homeDir != null) {
            Path globalFile = homeDir.resolve("HEARTBEAT.md");
            if (Files.isRegularFile(globalFile)) {
                found.add(globalFile);
            }
        }

        return List.copyOf(found);
    }

    /**
     * Parses HEARTBEAT.md content into a list of tasks.
     *
     * <p>Tasks with missing or invalid cron expressions are skipped with a warning.
     * Tasks with empty prompts are also skipped.
     *
     * @param content the HEARTBEAT.md file content
     * @return immutable list of valid tasks
     */
    public static List<HeartbeatTask> parse(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        var tasks = new ArrayList<HeartbeatTask>();
        var sections = splitSections(content);

        for (var section : sections) {
            try {
                var task = parseSection(section);
                if (task != null) {
                    tasks.add(task);
                }
            } catch (Exception e) {
                log.warn("Failed to parse heartbeat section: {}", e.getMessage());
            }
        }

        return List.copyOf(tasks);
    }

    /**
     * Discovers and parses all HEARTBEAT.md files.
     *
     * @param homeDir    daemon home directory (~/.aceclaw)
     * @param workingDir project working directory (may be null)
     * @return immutable list of all valid tasks from all files
     */
    public static List<HeartbeatTask> load(Path homeDir, Path workingDir) {
        var files = discoverFiles(homeDir, workingDir);
        if (files.isEmpty()) {
            return List.of();
        }

        var allTasks = new ArrayList<HeartbeatTask>();
        for (Path file : files) {
            try {
                String content = Files.readString(file);
                var tasks = parse(content);
                allTasks.addAll(tasks);
                log.info("Loaded {} heartbeat task(s) from {}", tasks.size(), file);
            } catch (IOException e) {
                log.warn("Failed to read HEARTBEAT.md at {}: {}", file, e.getMessage());
            }
        }

        return List.copyOf(allTasks);
    }

    /**
     * Splits content into sections by {@code ## heading}.
     * Each returned string starts with the heading line.
     */
    static List<String> splitSections(String content) {
        var sections = new ArrayList<String>();
        Matcher matcher = HEADING_PATTERN.matcher(content);

        int lastStart = -1;
        while (matcher.find()) {
            if (lastStart >= 0) {
                sections.add(content.substring(lastStart, matcher.start()).trim());
            }
            lastStart = matcher.start();
        }
        if (lastStart >= 0) {
            sections.add(content.substring(lastStart).trim());
        }

        return sections;
    }

    /**
     * Parses a single section (starting with ## heading) into a HeartbeatTask.
     * Returns null if the section is invalid (missing schedule, empty prompt, etc.).
     */
    private static HeartbeatTask parseSection(String section) {
        // Extract task name from heading
        Matcher headingMatcher = HEADING_PATTERN.matcher(section);
        if (!headingMatcher.find()) {
            return null;
        }
        String name = headingMatcher.group(1).trim();

        // Extract schedule (required)
        Matcher scheduleMatcher = SCHEDULE_PATTERN.matcher(section);
        if (!scheduleMatcher.find()) {
            log.warn("Heartbeat task '{}' has no schedule, skipping", name);
            return null;
        }
        String schedule = scheduleMatcher.group(1).trim();

        // Validate cron expression
        try {
            CronExpression.parse(schedule);
        } catch (IllegalArgumentException e) {
            log.warn("Heartbeat task '{}' has invalid cron expression '{}': {}, skipping",
                    name, schedule, e.getMessage());
            return null;
        }

        // Extract optional timeout
        int timeout = HeartbeatTask.DEFAULT_TIMEOUT_SECONDS;
        Matcher timeoutMatcher = TIMEOUT_PATTERN.matcher(section);
        if (timeoutMatcher.find()) {
            timeout = Integer.parseInt(timeoutMatcher.group(1));
        }

        // Extract optional tools
        Set<String> tools = Set.of();
        Matcher toolsMatcher = TOOLS_PATTERN.matcher(section);
        if (toolsMatcher.find()) {
            String toolsCsv = toolsMatcher.group(1).trim();
            var toolSet = new LinkedHashSet<String>();
            for (String tool : toolsCsv.split(",")) {
                String trimmed = tool.trim();
                if (!trimmed.isEmpty()) {
                    toolSet.add(trimmed);
                }
            }
            tools = Set.copyOf(toolSet);
        }

        // Extract prompt: everything after the heading and metadata lines
        String prompt = extractPrompt(section);
        if (prompt.isBlank()) {
            log.warn("Heartbeat task '{}' has empty prompt, skipping", name);
            return null;
        }

        return new HeartbeatTask(name, schedule, timeout, tools, prompt);
    }

    /**
     * Extracts the prompt text from a section by removing the heading and metadata lines.
     */
    private static String extractPrompt(String section) {
        // Remove the heading line
        String afterHeading = HEADING_PATTERN.matcher(section).replaceFirst("").trim();
        // Remove all metadata lines (- schedule: ..., - timeout: ..., - tools: ...)
        String prompt = METADATA_LINE.matcher(afterHeading).replaceAll("").trim();
        return prompt;
    }
}

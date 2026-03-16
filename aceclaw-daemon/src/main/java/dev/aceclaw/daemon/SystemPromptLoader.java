package dev.aceclaw.daemon;

import dev.aceclaw.memory.AutoMemoryStore;
import dev.aceclaw.memory.CandidatePromptAssembler;
import dev.aceclaw.memory.CandidateStore;
import dev.aceclaw.memory.DailyJournal;
import dev.aceclaw.memory.MarkdownMemoryStore;
import dev.aceclaw.memory.MemoryTierLoader;
import dev.aceclaw.memory.RuleEngine;
import dev.aceclaw.core.agent.ContextEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Loads and assembles the system prompt for the agent.
 *
 * <p>The system prompt is composed of:
 * <ol>
 *   <li>A built-in base prompt describing the agent's capabilities and behavior</li>
 *   <li>Environment context (working dir, platform, git status)</li>
 *   <li>8-tier memory hierarchy via {@link MemoryTierLoader}</li>
 * </ol>
 */
public final class SystemPromptLoader {

    private static final Logger log = LoggerFactory.getLogger(SystemPromptLoader.class);

    private static final String BASE_PROMPT_RESOURCE = "/system-prompt.md";

    private SystemPromptLoader() {}

    /** Global aceclaw config directory. */
    private static final Path GLOBAL_CONFIG_DIR = Path.of(
            System.getProperty("user.home"), ".aceclaw");
    private static final String DYNAMIC_TOOL_GUIDANCE_PLACEHOLDER =
            "<!-- Dynamic tool guidance (priority, tool-specific guidelines, fallback chain) injected by ToolGuidanceGenerator -->";

    /**
     * Loads the full system prompt for the given project directory.
     */
    public static String load(Path projectPath) {
        return load(projectPath, null, null, null, null);
    }

    /**
     * Loads the full system prompt with auto-memory injection.
     */
    public static String load(Path projectPath, AutoMemoryStore memoryStore) {
        return load(projectPath, memoryStore, null, null, null);
    }

    /**
     * Loads the full system prompt with auto-memory injection and model identity.
     */
    public static String load(Path projectPath, AutoMemoryStore memoryStore,
                              String model, String provider) {
        return load(projectPath, memoryStore, null, null, model, provider);
    }

    /**
     * Loads the full system prompt with 6-tier memory, daily journal, and model identity.
     *
     * @param projectPath the project working directory
     * @param memoryStore optional auto-memory store (may be null)
     * @param journal     optional daily journal (may be null)
     * @param model       the LLM model name (may be null)
     * @param provider    the LLM provider name (may be null)
     * @return the assembled system prompt
     */
    public static String load(Path projectPath, AutoMemoryStore memoryStore,
                              DailyJournal journal, String model, String provider) {
        return load(projectPath, memoryStore, journal, null, model, provider);
    }

    /**
     * Loads the full system prompt with 8-tier memory hierarchy.
     *
     * @param projectPath   the project working directory
     * @param memoryStore   optional auto-memory store (may be null)
     * @param journal       optional daily journal (may be null)
     * @param markdownStore optional markdown memory store (may be null)
     * @param model         the LLM model name (may be null)
     * @param provider      the LLM provider name (may be null)
     * @return the assembled system prompt
     */
    public static String load(Path projectPath, AutoMemoryStore memoryStore,
                              DailyJournal journal, MarkdownMemoryStore markdownStore,
                              String model, String provider) {
        return load(projectPath, memoryStore, journal, markdownStore, model, provider,
                SystemPromptBudget.DEFAULT);
    }

    /**
     * Loads the full system prompt with 8-tier memory hierarchy and size budget.
     *
     * @param projectPath   the project working directory
     * @param memoryStore   optional auto-memory store (may be null)
     * @param journal       optional daily journal (may be null)
     * @param markdownStore optional markdown memory store (may be null)
     * @param model         the LLM model name (may be null)
     * @param provider      the LLM provider name (may be null)
     * @param budget        the system prompt size budget
     * @return the assembled system prompt
     */
    /**
     * Loads the full system prompt with dynamic tool guidance.
     *
     * <p>This overload generates tool-specific guidance sections based on which
     * tools are actually registered, avoiding references to unavailable tools.
     *
     * @param projectPath        the project working directory
     * @param memoryStore        optional auto-memory store (may be null)
     * @param journal            optional daily journal (may be null)
     * @param markdownStore      optional markdown memory store (may be null)
     * @param model              the LLM model name (may be null)
     * @param provider           the LLM provider name (may be null)
     * @param budget             the system prompt size budget
     * @param registeredToolNames the set of tool names currently registered (may be null)
     * @param hasBraveApiKey     whether a Brave Search API key is configured
     * @return the assembled system prompt
     */
    public static String load(Path projectPath, AutoMemoryStore memoryStore,
                              DailyJournal journal, MarkdownMemoryStore markdownStore,
                              String model, String provider, SystemPromptBudget budget,
                              Set<String> registeredToolNames, boolean hasBraveApiKey) {
        return load(projectPath, memoryStore, journal, markdownStore, model, provider, budget,
                registeredToolNames, hasBraveApiKey, null, null);
    }

    /**
     * Loads the full system prompt with dynamic tool guidance and candidate injection.
     *
     * @param projectPath        the project working directory
     * @param memoryStore        optional auto-memory store (may be null)
     * @param journal            optional daily journal (may be null)
     * @param markdownStore      optional markdown memory store (may be null)
     * @param model              the LLM model name (may be null)
     * @param provider           the LLM provider name (may be null)
     * @param budget             the system prompt size budget
     * @param registeredToolNames the set of tool names currently registered (may be null)
     * @param hasBraveApiKey     whether a Brave Search API key is configured
     * @param candidateStore     optional candidate store for promoted candidate injection (may be null)
     * @param candidateConfig    optional candidate injection config (may be null)
     * @return the assembled system prompt
     */
    public static String load(Path projectPath, AutoMemoryStore memoryStore,
                              DailyJournal journal, MarkdownMemoryStore markdownStore,
                              String model, String provider, SystemPromptBudget budget,
                              Set<String> registeredToolNames, boolean hasBraveApiKey,
                              CandidateStore candidateStore,
                              CandidatePromptAssembler.Config candidateConfig) {
        String prompt = load(projectPath, memoryStore, journal, markdownStore, model, provider, budget);

        // Inject dynamic tool guidance if tool names are provided
        if (registeredToolNames != null && !registeredToolNames.isEmpty()) {
            String guidance = ToolGuidanceGenerator.generate(registeredToolNames, hasBraveApiKey);
            // Insert guidance at the placeholder comment location
            String placeholder = "<!-- Dynamic tool guidance (priority, tool-specific guidelines, fallback chain) injected by ToolGuidanceGenerator -->";
            if (prompt.contains(placeholder)) {
                prompt = prompt.replace(placeholder, guidance);
            } else {
                // Fallback: append after base prompt section
                prompt = prompt + guidance;
            }
        }

        // Inject promoted learning candidates into system prompt
        if (candidateStore != null && candidateConfig != null && candidateConfig.enabled()) {
            String candidateSection = CandidatePromptAssembler.assemble(candidateStore, candidateConfig);
            if (!candidateSection.isEmpty()) {
                prompt = prompt + candidateSection;
                log.info("Injected promoted candidates into system prompt ({} chars)",
                        candidateSection.length());
            }
        }

        return prompt;
    }

    /**
     * Assembles a request-aware system prompt with global budget enforcement.
     *
     * <p>Unlike {@link #load(Path, AutoMemoryStore, DailyJournal, MarkdownMemoryStore, String, String,
     * SystemPromptBudget, Set, boolean, CandidateStore, CandidatePromptAssembler.Config)},
     * this path uses the live user request to rank auto-memory, selectively inject rules,
     * and keep all sections under a single budget plan.
     */
    public static RequestAssembly assembleRequest(
            Path projectPath,
            AutoMemoryStore memoryStore,
            DailyJournal journal,
            MarkdownMemoryStore markdownStore,
            String model,
            String provider,
            SystemPromptBudget budget,
            Set<String> registeredToolNames,
            boolean hasBraveApiKey,
            CandidateStore candidateStore,
            CandidatePromptAssembler.Config candidateConfig,
            String skillDescriptions,
            String queryHint,
            List<String> activeFilePaths) {
        return inspectRequest(
                projectPath,
                memoryStore,
                journal,
                markdownStore,
                model,
                provider,
                budget,
                registeredToolNames,
                hasBraveApiKey,
                candidateStore,
                candidateConfig,
                skillDescriptions,
                queryHint,
                activeFilePaths).asRequestAssembly();
    }

    public static ContextInspection inspectRequest(
            Path projectPath,
            AutoMemoryStore memoryStore,
            DailyJournal journal,
            MarkdownMemoryStore markdownStore,
            String model,
            String provider,
            SystemPromptBudget budget,
            Set<String> registeredToolNames,
            boolean hasBraveApiKey,
            CandidateStore candidateStore,
            CandidatePromptAssembler.Config candidateConfig,
            String skillDescriptions,
            String queryHint,
            List<String> activeFilePaths) {
        Objects.requireNonNull(projectPath, "projectPath");
        Objects.requireNonNull(budget, "budget");
        var tierResult = MemoryTierLoader.loadAll(
                GLOBAL_CONFIG_DIR, projectPath, memoryStore, journal, markdownStore);
        Path markdownMemoryDir = markdownStore != null ? markdownStore.memoryDir() : null;
        var tierSections = MemoryTierLoader.formatTierSections(
                tierResult, memoryStore, projectPath, 50, markdownMemoryDir, queryHint);

        var plan = new ContextAssemblyPlan();
        for (var section : tierSections) {
            boolean highPriority = section.tier().priority() >= 60;
            if (highPriority) {
                plan.addSection("memory:" + section.tier().displayName(), section.content(),
                        section.tier().priority(), section.tier().priority() >= 90);
            }
        }

        plan.addSection("base", basePrompt().replace(DYNAMIC_TOOL_GUIDANCE_PLACEHOLDER, ""), 95, true);

        String rulesSection = RuleEngine.loadRules(projectPath).formatForPrompt(
                activeFilePaths != null ? activeFilePaths : List.of());
        plan.addSection("rules", rulesSection, 88, false);

        if (registeredToolNames != null && !registeredToolNames.isEmpty()) {
            plan.addSection("tool-guidance",
                    ToolGuidanceGenerator.generate(registeredToolNames, hasBraveApiKey),
                    82, false);
        }

        plan.addSection("environment", buildEnvironmentContext(projectPath, model, provider), 72, false);
        plan.addSection("git", buildGitContext(projectPath), 40, false);

        for (var section : tierSections) {
            boolean lowPriority = section.tier().priority() < 60;
            if (lowPriority) {
                plan.addSection("memory:" + section.tier().displayName(), section.content(),
                        section.tier().priority(), false);
            }
        }

        plan.addSection("skills", normalizeSection(skillDescriptions), 58, false);

        List<String> injectedCandidateIds = List.of();
        if (candidateStore != null && candidateConfig != null && candidateConfig.enabled()) {
            var candidateAssembly = CandidatePromptAssembler.assembleWithMetadata(
                    candidateStore, candidateConfig, queryHint,
                    activeFilePaths != null ? activeFilePaths : List.of());
            plan.addSection("candidates", candidateAssembly.section(), 54, false);
            injectedCandidateIds = candidateAssembly.candidateIds();
        }

        var result = plan.build(budget);
        if (result.truncatedSectionKeys().contains("candidates")) {
            injectedCandidateIds = List.of();
        }
        var sections = result.sections().stream()
                .map(section -> new ContextSection(
                        section.key(),
                        classifySectionSource(section.key()),
                        section.priority(),
                        section.protectedSection(),
                        section.originalChars(),
                        section.finalChars(),
                        ContextEstimator.estimateTokens(section.content()),
                        section.included(),
                        section.truncated(),
                        section.content()))
                .toList();
        return new ContextInspection(
                result.prompt(),
                injectedCandidateIds,
                activeFilePaths != null ? List.copyOf(activeFilePaths) : List.of(),
                result.truncatedSectionKeys(),
                sections,
                result.prompt().length(),
                ContextEstimator.estimateTokens(result.prompt()),
                budget);
    }

    public static String load(Path projectPath, AutoMemoryStore memoryStore,
                              DailyJournal journal, MarkdownMemoryStore markdownStore,
                              String model, String provider, SystemPromptBudget budget) {
        var sb = new StringBuilder();

        // 8-tier memory hierarchy via MemoryTierLoader with budget enforcement
        // Placed BEFORE base prompt to exploit "primacy bias" — models attend most
        // to early content. This ensures memory (bookmarks, learned patterns, user
        // instructions) isn't buried after a 20K char base prompt where small models
        // lose attention ("lost in the middle" problem).
        var tierResult = MemoryTierLoader.loadAll(
                GLOBAL_CONFIG_DIR, projectPath, memoryStore, journal, markdownStore);
        Path markdownMemoryDir = markdownStore != null ? markdownStore.memoryDir() : null;
        var tierSections = MemoryTierLoader.formatTierSections(
                tierResult, memoryStore, projectPath, 50, markdownMemoryDir);

        // Apply budget constraints (per-tier + total caps, 70/20/10 truncation)
        var budgetedSections = TierTruncator.applyBudget(tierSections, budget);

        // Split tiers: high-priority (Soul, Policy, Workspace, User, Local, Auto)
        // go BEFORE base prompt; low-priority (Markdown, Journal) go AFTER.
        var highPriorityTiers = budgetedSections.stream()
                .filter(s -> s.tier().priority() >= 60) // AutoMemory(70) and above
                .toList();
        var lowPriorityTiers = budgetedSections.stream()
                .filter(s -> s.tier().priority() < 60) // MarkdownMemory(55), Journal(50)
                .toList();

        // High-priority memory FIRST (user context, instructions, learned insights)
        String highContent = MemoryTierLoader.joinTierSections(highPriorityTiers);
        if (!highContent.isEmpty()) {
            sb.append(highContent);
        }

        // Base prompt (agent capabilities, tool usage rules)
        sb.append(basePrompt());

        // Environment context
        appendEnvironmentContext(sb, projectPath, model, provider);

        // Git context
        appendGitContext(sb, projectPath);

        // Low-priority memory AFTER base prompt (journal, markdown notes)
        String lowContent = MemoryTierLoader.joinTierSections(lowPriorityTiers);
        if (!lowContent.isEmpty()) {
            sb.append(lowContent);
        }

        String tierContent = MemoryTierLoader.joinTierSections(budgetedSections);
        if (!tierContent.isEmpty()) {
            // Detect and report truncated tiers so the agent is aware
            var truncatedTiers = budgetedSections.stream()
                    .filter(s -> s.content() != null && s.content().contains("[TRUNCATED]"))
                    .map(s -> s.tier().displayName())
                    .toList();
            if (!truncatedTiers.isEmpty()) {
                sb.append("\n\n<!-- Budget warning: the following memory tiers were truncated to fit the ")
                        .append(budget.maxTotalChars()).append(" char system prompt budget: ")
                        .append(String.join(", ", truncatedTiers))
                        .append(". If the user asks about missing context, inform them that some memory ")
                        .append("tiers were truncated. -->\n");
                log.warn("Memory tiers truncated by budget: {}", truncatedTiers);
            }

            log.info("Injected {} memory tiers into system prompt (budget: {}/{} chars used)",
                    tierResult.tiersLoaded(), tierContent.length(), budget.maxTotalChars());
        }

        // Path-based rules from {project}/.aceclaw/rules/*.md
        var ruleEngine = RuleEngine.loadRules(projectPath);
        if (!ruleEngine.rules().isEmpty()) {
            log.info("Loaded {} path-based rules", ruleEngine.rules().size());
            sb.append("\n\n# Path-Based Rules\n\n");
            sb.append("The following rules apply when you work on files matching their glob patterns. ");
            sb.append("Follow them strictly for matching files.\n");
            for (var rule : ruleEngine.rules()) {
                sb.append("\n## ").append(rule.name()).append("\n");
                sb.append("Applies to: ").append(String.join(", ", rule.patterns())).append("\n\n");
                sb.append(rule.content().strip()).append("\n");
            }
        }

        return sb.toString();
    }

    public record RequestAssembly(
            String prompt,
            List<String> injectedCandidateIds,
            List<String> activeFilePaths,
            List<String> truncatedSectionKeys) {
        public RequestAssembly {
            prompt = prompt != null ? prompt : "";
            injectedCandidateIds = injectedCandidateIds != null ? List.copyOf(injectedCandidateIds) : List.of();
            activeFilePaths = activeFilePaths != null ? List.copyOf(activeFilePaths) : List.of();
            truncatedSectionKeys = truncatedSectionKeys != null ? List.copyOf(truncatedSectionKeys) : List.of();
        }
    }

    public record ContextInspection(
            String prompt,
            List<String> injectedCandidateIds,
            List<String> activeFilePaths,
            List<String> truncatedSectionKeys,
            List<ContextSection> sections,
            int totalChars,
            int estimatedTokens,
            SystemPromptBudget budget
    ) {
        public ContextInspection {
            prompt = prompt != null ? prompt : "";
            injectedCandidateIds = injectedCandidateIds != null ? List.copyOf(injectedCandidateIds) : List.of();
            activeFilePaths = activeFilePaths != null ? List.copyOf(activeFilePaths) : List.of();
            truncatedSectionKeys = truncatedSectionKeys != null ? List.copyOf(truncatedSectionKeys) : List.of();
            sections = sections != null ? List.copyOf(sections) : List.of();
            budget = budget != null ? budget : SystemPromptBudget.DEFAULT;
        }

        public RequestAssembly asRequestAssembly() {
            return new RequestAssembly(prompt, injectedCandidateIds, activeFilePaths, truncatedSectionKeys);
        }
    }

    public record ContextSection(
            String key,
            String sourceType,
            int priority,
            boolean protectedSection,
            int originalChars,
            int finalChars,
            int estimatedTokens,
            boolean included,
            boolean truncated,
            String content
    ) {
        public ContextSection {
            key = key != null ? key : "unknown";
            sourceType = sourceType != null ? sourceType : "unknown";
            content = content != null ? content : "";
        }
    }

    private static String classifySectionSource(String key) {
        if (key == null || key.isBlank()) {
            return "unknown";
        }
        if (key.startsWith("memory:")) {
            return "memory:Auto-Memory".equals(key) ? "learned-signals" : "memory";
        }
        return switch (key) {
            case "base" -> "base";
            case "rules" -> "rules";
            case "tool-guidance" -> "tool-guidance";
            case "environment" -> "environment";
            case "git" -> "git";
            case "skills" -> "skills";
            case "candidates" -> "candidates";
            default -> "other";
        };
    }

    /**
     * Appends environment context (working dir, platform, Java version, date, model).
     */
    private static void appendEnvironmentContext(StringBuilder sb, Path projectPath,
                                                  String model, String provider) {
        sb.append(buildEnvironmentContext(projectPath, model, provider));
    }

    private static String buildEnvironmentContext(Path projectPath, String model, String provider) {
        var sb = new StringBuilder();
        sb.append("\n\n# Environment\n\n");
        sb.append("- Working directory: ").append(projectPath.toAbsolutePath().normalize()).append("\n");
        sb.append("- All relative file paths resolve against this directory.\n");
        sb.append("- Platform: ").append(System.getProperty("os.name")).append("\n");
        sb.append("- Java: ").append(System.getProperty("java.version")).append("\n");
        sb.append("- The current date is: ").append(java.time.LocalDate.now()).append("\n");
        if (model != null && !model.isBlank()) {
            sb.append("- You are powered by the model: ").append(model).append("\n");
        }
        if (provider != null && !provider.isBlank()) {
            sb.append("- Provider: ").append(provider).append("\n");
        }
        return sb.toString();
    }

    /**
     * Appends git repository context (branch, status, recent commits) if available.
     */
    private static void appendGitContext(StringBuilder sb, Path projectPath) {
        sb.append(buildGitContext(projectPath));
    }

    private static String buildGitContext(Path projectPath) {
        var sb = new StringBuilder();
        try {
            var gitDir = projectPath.resolve(".git");
            if (!Files.exists(gitDir)) return "";

            sb.append("\n# Git Context\n\n");

            String branch = runGitCommand(projectPath, "git", "rev-parse", "--abbrev-ref", "HEAD");
            if (branch != null) {
                sb.append("- Current branch: ").append(branch).append("\n");
            }

            String status = runGitCommand(projectPath, "git", "status", "--short");
            if (status != null && !status.isBlank()) {
                sb.append("- Status:\n```\n").append(status).append("\n```\n");
            } else {
                sb.append("- Status: clean\n");
            }

            String recentLog = runGitCommand(projectPath,
                    "git", "log", "--oneline", "-5", "--no-decorate");
            if (recentLog != null && !recentLog.isBlank()) {
                sb.append("- Recent commits:\n```\n").append(recentLog).append("\n```\n");
            }

        } catch (Exception e) {
            log.debug("Failed to gather git context: {}", e.getMessage());
        }
        return sb.toString();
    }

    /**
     * Runs a git command and returns the trimmed stdout, or null on failure.
     * Ensures the process is always destroyed and has a timeout to prevent hangs.
     */
    private static String runGitCommand(Path workingDir, String... command) {
        Process process = null;
        try {
            var pb = new ProcessBuilder(command);
            pb.directory(workingDir.toFile());
            pb.redirectErrorStream(true);
            process = pb.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            boolean finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                log.debug("Git command timed out: {}", String.join(" ", command));
                return null;
            }
            return process.exitValue() == 0 ? output : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * Extracts project rules from CLAUDE.md and ACECLAW.md in the project directory.
     *
     * <p>These rules are injected into sub-agent system prompts so they follow
     * the same conventions as the parent agent. Returns empty string if neither
     * file exists.
     *
     * @param projectPath the project root directory
     * @return concatenated project rules, or empty string if none found
     */
    public static String extractProjectRules(Path projectPath) {
        var sb = new StringBuilder();

        // CLAUDE.md (standard convention)
        var claudeMd = projectPath.resolve("CLAUDE.md");
        if (Files.isReadable(claudeMd)) {
            try {
                String content = Files.readString(claudeMd, StandardCharsets.UTF_8);
                if (!content.isBlank()) {
                    sb.append("## CLAUDE.md\n\n").append(content.strip()).append("\n\n");
                }
            } catch (IOException e) {
                log.debug("Failed to read CLAUDE.md: {}", e.getMessage());
            }
        }

        // .aceclaw/ACECLAW.md (project-specific config)
        var aceClawMd = projectPath.resolve(".aceclaw").resolve("ACECLAW.md");
        if (Files.isReadable(aceClawMd)) {
            try {
                String content = Files.readString(aceClawMd, StandardCharsets.UTF_8);
                if (!content.isBlank()) {
                    sb.append("## ACECLAW.md\n\n").append(content.strip()).append("\n\n");
                }
            } catch (IOException e) {
                log.debug("Failed to read ACECLAW.md: {}", e.getMessage());
            }
        }

        return sb.toString();
    }

    /**
     * Returns the built-in base system prompt from the classpath resource.
     * Falls back to a minimal prompt if the resource cannot be loaded.
     */
    private static String basePrompt() {
        try (InputStream is = SystemPromptLoader.class.getResourceAsStream(BASE_PROMPT_RESOURCE)) {
            if (is != null) {
                String prompt = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                log.debug("Loaded base prompt from resource ({} chars)", prompt.length());
                return prompt;
            }
        } catch (IOException e) {
            log.warn("Failed to load base prompt resource: {}", e.getMessage());
        }
        log.warn("Base prompt resource not found, using fallback");
        return "You are AceClaw, an AI coding assistant. Use the available tools to help the user.\n";
    }

    private static String normalizeSection(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        return "\n\n" + content.strip() + "\n";
    }
}

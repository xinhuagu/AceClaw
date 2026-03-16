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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

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
    private static final Pattern CAMEL_OR_PASCAL_SYMBOL = Pattern.compile(
            "\\b(?:[A-Z][A-Za-z0-9_]{2,}|[a-z]+[A-Z][A-Za-z0-9_]*)\\b(?:\\(\\))?");
    private static final Pattern BACKTICK_CODE = Pattern.compile("`([^`]+)`");

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
        var requestFocus = analyzeRequestFocus(queryHint, activeFilePaths);
        var rankedTierSections = tierSections.stream()
                .map(section -> new RankedSection(
                        "memory:" + section.tier().displayName(),
                        section.content(),
                        applyRequestFocusPriority(
                                "memory:" + section.tier().displayName(),
                                section.tier().priority(),
                                section.content(),
                                requestFocus),
                        section.tier().priority() >= 90))
                .toList();
        String taskFocusSection = formatTaskFocusSection(requestFocus);

        var plan = new ContextAssemblyPlan();
        for (var section : rankedTierSections) {
            if (section.priority() >= 60) {
                plan.addSection(section.key(), section.content(), section.priority(), section.protectedSection());
            }
        }

        plan.addSection("base", basePrompt().replace(DYNAMIC_TOOL_GUIDANCE_PLACEHOLDER, ""), 95, true);
        plan.addSection("task-focus", taskFocusSection,
                applyRequestFocusPriority("task-focus", 89, taskFocusSection, requestFocus), false);

        String rulesSection = RuleEngine.loadRules(projectPath).formatForPrompt(
                activeFilePaths != null ? activeFilePaths : List.of());
        plan.addSection("rules", rulesSection,
                applyRequestFocusPriority("rules", 88, rulesSection, requestFocus), false);

        if (registeredToolNames != null && !registeredToolNames.isEmpty()) {
            plan.addSection("tool-guidance",
                    ToolGuidanceGenerator.generate(registeredToolNames, hasBraveApiKey),
                    applyRequestFocusPriority("tool-guidance", 82, "", requestFocus), false);
        }

        String environmentSection = buildEnvironmentContext(projectPath, model, provider);
        plan.addSection("environment", environmentSection,
                applyRequestFocusPriority("environment", 72, environmentSection, requestFocus), false);
        String gitSection = buildGitContext(projectPath);
        plan.addSection("git", gitSection,
                applyRequestFocusPriority("git", 40, gitSection, requestFocus), false);

        for (var section : rankedTierSections) {
            if (section.priority() < 60) {
                plan.addSection(section.key(), section.content(), section.priority(), false);
            }
        }

        String skillsSection = normalizeSection(skillDescriptions);
        plan.addSection("skills", skillsSection,
                applyRequestFocusPriority("skills", 58, skillsSection, requestFocus), false);

        List<String> injectedCandidateIds = List.of();
        if (candidateStore != null && candidateConfig != null && candidateConfig.enabled()) {
            var candidateAssembly = CandidatePromptAssembler.assembleWithMetadata(
                    candidateStore, candidateConfig, queryHint,
                    activeFilePaths != null ? activeFilePaths : List.of());
            plan.addSection("candidates", candidateAssembly.section(),
                    applyRequestFocusPriority("candidates", 54, candidateAssembly.section(), requestFocus), false);
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
                        classifySectionScope(section.key()),
                        describeInclusionReason(section.key(), section.content(), requestFocus),
                        collectSectionEvidence(section.key(), requestFocus),
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
                requestFocus,
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
            RequestFocus requestFocus,
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
            requestFocus = requestFocus != null ? requestFocus : RequestFocus.empty();
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
            String scopeType,
            String inclusionReason,
            List<String> evidence,
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
            scopeType = scopeType != null ? scopeType : "always-on";
            inclusionReason = inclusionReason != null ? inclusionReason : "";
            evidence = evidence != null ? List.copyOf(evidence) : List.of();
            content = content != null ? content : "";
        }
    }

    public record RequestFocus(
            String querySummary,
            List<String> activeFilePaths,
            List<String> activeSymbols,
            List<String> planSignals
    ) {
        public RequestFocus {
            querySummary = querySummary != null ? querySummary : "";
            activeFilePaths = activeFilePaths != null ? List.copyOf(activeFilePaths) : List.of();
            activeSymbols = activeSymbols != null ? List.copyOf(activeSymbols) : List.of();
            planSignals = planSignals != null ? List.copyOf(planSignals) : List.of();
        }

        public static RequestFocus empty() {
            return new RequestFocus("", List.of(), List.of(), List.of());
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
            case "task-focus" -> "task-focus";
            case "rules" -> "rules";
            case "tool-guidance" -> "tool-guidance";
            case "environment" -> "environment";
            case "git" -> "git";
            case "skills" -> "skills";
            case "candidates" -> "candidates";
            default -> "other";
        };
    }

    private static String classifySectionScope(String key) {
        if (key == null || key.isBlank()) {
            return "always-on";
        }
        if ("memory:Auto-Memory".equals(key)) {
            return "task-local";
        }
        if (key.startsWith("memory:")) {
            return "always-on";
        }
        return switch (key) {
            case "task-focus", "rules", "candidates", "git" -> "task-local";
            default -> "always-on";
        };
    }

    private static String describeInclusionReason(String key, String content, RequestFocus focus) {
        if (key == null || key.isBlank()) {
            return "";
        }
        if (key.startsWith("memory:")) {
            if (contentReferencesActiveSymbols(content, focus)) {
                return "Memory tier priority was boosted because it referenced active request symbols.";
            }
            if ("memory:Auto-Memory".equals(key) && !focus.querySummary().isBlank()) {
                return "Learned signals were ranked against the current request hint.";
            }
            if ("memory:Daily Journal".equals(key) && !focus.planSignals().isEmpty()) {
                return "Recent journal context was promoted because the plan signals suggest ongoing execution.";
            }
            return "Persistent memory tier kept in the system prompt hierarchy.";
        }
        return switch (key) {
            case "base" -> "Core operating policy is always included.";
            case "task-focus" -> "Current request focus was derived from the query, active files, and symbols.";
            case "rules" -> focus.activeFilePaths().isEmpty()
                    ? "Path-based rules are available for the current workspace."
                    : "Path-based rules matched the files currently in focus.";
            case "tool-guidance" -> "Tool guidance is included because tools are registered for this session.";
            case "environment" -> "Runtime environment grounding is always included.";
            case "git" -> "Repository state is included as task-local working context.";
            case "skills" -> "Available skills are exposed so the agent can choose reusable workflows.";
            case "candidates" -> "Promoted candidates were injected because they matched the current request.";
            default -> "Included by the request-aware system prompt assembly.";
        };
    }

    private static List<String> collectSectionEvidence(String key, RequestFocus focus) {
        var evidence = new ArrayList<String>();
        if (!focus.activeFilePaths().isEmpty()
                && ("task-focus".equals(key) || "rules".equals(key) || "candidates".equals(key)
                || "memory:Markdown Memory".equals(key))) {
            evidence.add("files=" + String.join(", ", focus.activeFilePaths().stream().limit(3).toList()));
        }
        if (!focus.activeSymbols().isEmpty()
                && ("task-focus".equals(key) || "skills".equals(key) || "candidates".equals(key)
                || key.startsWith("memory:"))) {
            evidence.add("symbols=" + String.join(", ", focus.activeSymbols().stream().limit(4).toList()));
        }
        if (!focus.planSignals().isEmpty()
                && ("task-focus".equals(key) || "git".equals(key) || "candidates".equals(key)
                || "memory:Daily Journal".equals(key))) {
            evidence.add("plan=" + String.join(", ", focus.planSignals()));
        }
        if (!focus.querySummary().isBlank()
                && ("task-focus".equals(key) || "candidates".equals(key) || "memory:Auto-Memory".equals(key))) {
            evidence.add("query=" + focus.querySummary());
        }
        return List.copyOf(evidence);
    }

    static RequestFocus analyzeRequestFocus(String queryHint, List<String> activeFilePaths) {
        String normalizedQuery = queryHint != null ? normalizeQuerySummary(queryHint) : "";
        return new RequestFocus(
                normalizedQuery,
                activeFilePaths != null ? List.copyOf(activeFilePaths.stream().limit(6).toList()) : List.of(),
                extractActiveSymbols(queryHint),
                inferPlanSignals(queryHint));
    }

    /** Maximum priority boost from request focus signals, preventing tier inversions. */
    private static final int MAX_FOCUS_BOOST = 12;

    private static int applyRequestFocusPriority(String key, int basePriority, String content, RequestFocus focus) {
        if (focus == null) {
            return basePriority;
        }
        int boost = Math.min(MAX_FOCUS_BOOST, requestFocusBoost(key, content, focus));
        return Math.max(0, Math.min(100, basePriority + boost));
    }

    private static int requestFocusBoost(String key, String content, RequestFocus focus) {
        int boost = 0;
        boolean symbolMatch = contentReferencesActiveSymbols(content, focus);
        boolean hasCodeChange = focus.planSignals().contains("code change requested");
        boolean hasVerification = focus.planSignals().contains("verification requested");
        boolean hasContinue = focus.planSignals().contains("continue current execution");
        boolean hasPlanning = focus.planSignals().contains("planning context");

        if (key.startsWith("memory:")) {
            if (symbolMatch) {
                boost += 16;
            }
            if ("memory:Auto-Memory".equals(key) && !focus.querySummary().isBlank()) {
                boost += 8;
            }
            if ("memory:Markdown Memory".equals(key) && !focus.activeFilePaths().isEmpty()) {
                boost += 10;
            }
            if ("memory:Daily Journal".equals(key) && (hasContinue || hasVerification)) {
                boost += 12;
            }
            return boost;
        }

        return switch (key) {
            case "task-focus" -> (!focus.activeFilePaths().isEmpty() ? 2 : 0)
                    + (!focus.activeSymbols().isEmpty() ? 4 : 0)
                    + (!focus.planSignals().isEmpty() ? 4 : 0);
            case "rules" -> (!focus.activeFilePaths().isEmpty() ? 4 : 0)
                    + (hasCodeChange ? 3 : 0)
                    + (hasVerification ? 2 : 0);
            case "skills" -> (!focus.activeSymbols().isEmpty() ? 5 : 0)
                    + (hasPlanning ? 5 : 0)
                    + (hasCodeChange ? 2 : 0);
            case "candidates" -> (!focus.activeSymbols().isEmpty() ? 6 : 0)
                    + (!focus.activeFilePaths().isEmpty() ? 3 : 0)
                    + (!focus.planSignals().isEmpty() ? 3 : 0);
            case "git" -> (hasContinue ? 6 : 0) + (hasCodeChange ? 2 : 0);
            case "tool-guidance" -> hasPlanning ? 2 : 0;
            case "environment" -> (hasContinue && focus.activeFilePaths().isEmpty()) ? 2 : 0;
            default -> 0;
        };
    }

    private static boolean contentReferencesActiveSymbols(String content, RequestFocus focus) {
        if (content == null || content.isBlank() || focus == null || focus.activeSymbols().isEmpty()) {
            return false;
        }
        String normalizedContent = content.toLowerCase();
        for (String symbol : focus.activeSymbols()) {
            if (symbol != null && !symbol.isBlank() && normalizedContent.contains(symbol.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static String formatTaskFocusSection(RequestFocus focus) {
        if (focus == null) {
            return "";
        }
        var lines = new ArrayList<String>();
        if (!focus.querySummary().isBlank()) {
            lines.add("- Query intent: " + focus.querySummary());
        }
        if (!focus.activeFilePaths().isEmpty()) {
            lines.add("- Active files: " + String.join(", ", focus.activeFilePaths()));
        }
        if (!focus.activeSymbols().isEmpty()) {
            lines.add("- Active symbols: " + String.join(", ", focus.activeSymbols()));
        }
        if (!focus.planSignals().isEmpty()) {
            lines.add("- Plan signals: " + String.join(", ", focus.planSignals()));
        }
        if (lines.isEmpty()) {
            return "";
        }
        return "\n\n# Task Focus\n\n"
                + "Treat the following request-local signals as the primary focus for this task:\n\n"
                + String.join("\n", lines)
                + "\n";
    }

    private static String normalizeQuerySummary(String queryHint) {
        if (queryHint == null || queryHint.isBlank()) {
            return "";
        }
        String normalized = queryHint.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 160) {
            return normalized;
        }
        return normalized.substring(0, 157).trim() + "...";
    }

    private static List<String> extractActiveSymbols(String queryHint) {
        if (queryHint == null || queryHint.isBlank()) {
            return List.of();
        }
        var symbols = new LinkedHashSet<String>();
        var backtickMatcher = BACKTICK_CODE.matcher(queryHint);
        while (backtickMatcher.find() && symbols.size() < 8) {
            String candidate = backtickMatcher.group(1).trim();
            if (looksLikeSymbol(candidate)) {
                symbols.add(candidate);
            }
        }
        var matcher = CAMEL_OR_PASCAL_SYMBOL.matcher(queryHint);
        while (matcher.find() && symbols.size() < 8) {
            String candidate = matcher.group().replace("()", "");
            if (looksLikeSymbol(candidate)) {
                symbols.add(candidate);
            }
        }
        return List.copyOf(symbols);
    }

    private static boolean looksLikeSymbol(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        if (candidate.contains("/") || candidate.contains("\\")) {
            return false;
        }
        // Strip trailing () for method references like AppService.handle()
        String clean = candidate.endsWith("()") ? candidate.substring(0, candidate.length() - 2) : candidate;
        // Accept dotted qualified names where each segment is a valid identifier
        // e.g. AppService.validate, dev.aceclaw.App
        if (clean.contains(".")) {
            String[] segments = clean.split("\\.");
            if (segments.length < 2 || segments.length > 6) {
                return false;
            }
            for (String seg : segments) {
                if (!seg.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    return false;
                }
            }
            return true;
        }
        return clean.matches("[A-Z][A-Za-z0-9_]{2,}")
                || clean.matches("[a-z]+[A-Z][A-Za-z0-9_]*");
    }

    private static List<String> inferPlanSignals(String queryHint) {
        if (queryHint == null || queryHint.isBlank()) {
            return List.of();
        }
        String lower = queryHint.toLowerCase();
        var signals = new ArrayList<String>();
        if (lower.contains("continue") || lower.contains("resume") || lower.contains("next step")) {
            signals.add("continue current execution");
        }
        if (lower.contains("plan") || lower.contains("next steps") || lower.contains("steps to")) {
            signals.add("planning context");
        }
        if (lower.contains("fix") || lower.contains("implement") || lower.contains("edit")
                || lower.contains("update") || lower.contains("refactor")) {
            signals.add("code change requested");
        }
        if (lower.contains("test") || lower.contains("verify") || lower.contains("review")) {
            signals.add("verification requested");
        }
        return List.copyOf(signals);
    }

    private record RankedSection(
            String key,
            String content,
            int priority,
            boolean protectedSection
    ) {}

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

package dev.aceclaw.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Central orchestrator that discovers, loads, and assembles all 8 memory tiers
 * into a unified string for system prompt injection.
 *
 * <p>Loading order (by priority):
 * <ol>
 *   <li><strong>Soul</strong> — SOUL.md (workspace first, then global fallback)</li>
 *   <li><strong>Managed Policy</strong> — Reserved for enterprise (skipped if absent)</li>
 *   <li><strong>Workspace Memory</strong> — {project}/ACECLAW.md, {project}/.aceclaw/ACECLAW.md</li>
 *   <li><strong>User Memory</strong> — ~/.aceclaw/ACECLAW.md</li>
 *   <li><strong>Local Memory</strong> — {project}/ACECLAW.local.md (gitignored, per-developer)</li>
 *   <li><strong>Auto-Memory</strong> — Learned insights from AutoMemoryStore</li>
 *   <li><strong>Markdown Memory</strong> — Persistent MEMORY.md + topic files</li>
 *   <li><strong>Daily Journal</strong> — Recent activity from DailyJournal</li>
 * </ol>
 */
public final class MemoryTierLoader {

    private static final Logger log = LoggerFactory.getLogger(MemoryTierLoader.class);

    private static final String SOUL_MD = "SOUL.md";
    private static final String ACECLAW_MD = "ACECLAW.md";
    private static final String ACECLAW_LOCAL_MD = "ACECLAW.local.md";
    private static final String MANAGED_POLICY_FILE = "managed-policy.md";

    private MemoryTierLoader() {}

    /**
     * Loads all 8 memory tiers and returns a structured result.
     *
     * @param aceclawHome   the aceclaw home directory (e.g. ~/.aceclaw)
     * @param workspacePath the workspace/project directory
     * @param memoryStore   the auto-memory store (may be null)
     * @param journal       the daily journal (may be null)
     * @return the load result with all tier content
     */
    public static LoadResult loadAll(Path aceclawHome, Path workspacePath,
                                     AutoMemoryStore memoryStore, DailyJournal journal) {
        return loadAll(aceclawHome, workspacePath, memoryStore, journal, null);
    }

    /**
     * Loads all 8 memory tiers and returns a structured result.
     *
     * @param aceclawHome    the aceclaw home directory (e.g. ~/.aceclaw)
     * @param workspacePath  the workspace/project directory
     * @param memoryStore    the auto-memory store (may be null)
     * @param journal        the daily journal (may be null)
     * @param markdownStore  the markdown memory store (may be null)
     * @return the load result with all tier content
     */
    public static LoadResult loadAll(Path aceclawHome, Path workspacePath,
                                     AutoMemoryStore memoryStore, DailyJournal journal,
                                     MarkdownMemoryStore markdownStore) {
        var sections = new ArrayList<TierSection>();
        String soulContent = null;
        int tiersLoaded = 0;

        // 1. Soul (SOUL.md) — workspace override first, then global fallback
        String soul = loadSoul(aceclawHome, workspacePath);
        if (soul != null) {
            soulContent = soul;
            sections.add(new TierSection(new MemoryTier.Soul(), soul));
            tiersLoaded++;
        }

        // 2. Managed Policy (reserved, loaded from ~/.aceclaw/managed-policy.md)
        String policy = loadFileContent(aceclawHome.resolve(MANAGED_POLICY_FILE));
        if (policy != null) {
            sections.add(new TierSection(new MemoryTier.ManagedPolicy(), policy));
            tiersLoaded++;
        }

        // 3. Workspace Memory (project ACECLAW.md files)
        String workspaceMemory = loadWorkspaceMemory(workspacePath);
        if (workspaceMemory != null) {
            sections.add(new TierSection(new MemoryTier.WorkspaceMemory(), workspaceMemory));
            tiersLoaded++;
        }

        // 4. User Memory (global ~/.aceclaw/ACECLAW.md)
        String userMemory = loadFileContent(aceclawHome.resolve(ACECLAW_MD));
        if (userMemory != null) {
            sections.add(new TierSection(new MemoryTier.UserMemory(), userMemory));
            tiersLoaded++;
        }

        // 4.5. Local Memory (per-developer ACECLAW.local.md, gitignored)
        if (workspacePath != null) {
            String localMemory = loadFileContent(workspacePath.resolve(ACECLAW_LOCAL_MD));
            if (localMemory != null) {
                sections.add(new TierSection(new MemoryTier.LocalMemory(), localMemory));
                tiersLoaded++;
            }
        }

        // 5. Auto-Memory (learned insights — always present if store is available)
        if (memoryStore != null) {
            sections.add(new TierSection(new MemoryTier.AutoMemory(), null));
            tiersLoaded++;
        }

        // 5.5. Markdown Memory (persistent MEMORY.md + topic files)
        // Always present if store is available, even if MEMORY.md doesn't exist yet.
        // This ensures the agent knows about the capability and the directory path.
        if (markdownStore != null) {
            String memoryMd = markdownStore.loadMemoryMd(); // may be null
            sections.add(new TierSection(new MemoryTier.MarkdownMemory(), memoryMd));
            tiersLoaded++;
        }

        // 6. Daily Journal (recent entries)
        if (journal != null) {
            var recentEntries = journal.loadRecentWindow();
            if (!recentEntries.isEmpty()) {
                String journalContent = String.join("\n", recentEntries);
                sections.add(new TierSection(new MemoryTier.Journal(), journalContent));
                tiersLoaded++;
            }
        }

        log.info("Loaded {} memory tiers", tiersLoaded);
        return new LoadResult(soulContent, sections, tiersLoaded);
    }

    /**
     * Assembles the loaded tiers into a single string for system prompt injection.
     *
     * <p>Delegates to {@link #formatTierSections} + {@link #joinTierSections}.
     *
     * @param result              the load result from {@link #loadAll}
     * @param memoryStore         the auto-memory store for formatting (may be null)
     * @param workspacePath       the workspace path (for memory store formatting)
     * @param maxAutoMemoryEntries maximum auto-memory entries to include
     * @return the assembled prompt section, or empty string if nothing loaded
     */
    public static String assembleForSystemPrompt(LoadResult result, AutoMemoryStore memoryStore,
                                                  Path workspacePath, int maxAutoMemoryEntries) {
        var sections = formatTierSections(result, memoryStore, workspacePath, maxAutoMemoryEntries);
        return joinTierSections(sections);
    }

    /**
     * Returns the tier sections as an ordered list (for external budget application).
     *
     * <p>Each section is formatted with its header but returned individually,
     * allowing callers to apply per-tier and total size budgets before concatenation.
     *
     * @param result              the load result from {@link #loadAll}
     * @param memoryStore         the auto-memory store for formatting (may be null)
     * @param workspacePath       the workspace path (for memory store formatting)
     * @param maxAutoMemoryEntries maximum auto-memory entries to include
     * @return ordered list of formatted tier sections
     */
    public static List<TierSection> formatTierSections(LoadResult result, AutoMemoryStore memoryStore,
                                                         Path workspacePath, int maxAutoMemoryEntries) {
        return formatTierSections(result, memoryStore, workspacePath, maxAutoMemoryEntries, null);
    }

    /**
     * Returns the tier sections as an ordered list (for external budget application),
     * with the markdown memory directory path injected for agent file access.
     *
     * @param result              the load result from {@link #loadAll}
     * @param memoryStore         the auto-memory store for formatting (may be null)
     * @param workspacePath       the workspace path (for memory store formatting)
     * @param maxAutoMemoryEntries maximum auto-memory entries to include
     * @param markdownMemoryDir   the markdown memory directory path (may be null)
     * @return ordered list of formatted tier sections
     */
    public static List<TierSection> formatTierSections(LoadResult result, AutoMemoryStore memoryStore,
                                                         Path workspacePath, int maxAutoMemoryEntries,
                                                         Path markdownMemoryDir) {
        if (result.tiersLoaded() == 0) return List.of();

        var formatted = new ArrayList<TierSection>();

        for (var section : result.tieredSections()) {
            String content = formatSingleTier(section, memoryStore, workspacePath,
                    maxAutoMemoryEntries, markdownMemoryDir);
            if (content != null && !content.isEmpty()) {
                formatted.add(new TierSection(section.tier(), content));
            }
        }

        return formatted;
    }

    /**
     * Joins formatted tier sections into a single string.
     *
     * @param sections the formatted tier sections
     * @return the concatenated prompt section
     */
    public static String joinTierSections(List<TierSection> sections) {
        var sb = new StringBuilder();
        for (var section : sections) {
            if (section.content() != null) {
                sb.append(section.content());
            }
        }
        return sb.toString();
    }

    private static String formatSingleTier(TierSection section, AutoMemoryStore memoryStore,
                                            Path workspacePath, int maxAutoMemoryEntries,
                                            Path markdownMemoryDir) {
        var sb = new StringBuilder();
        switch (section.tier()) {
            case MemoryTier.Soul _ -> {
                sb.append("\n\n# Soul (Core Identity)\n\n");
                sb.append(section.content().strip());
            }
            case MemoryTier.ManagedPolicy _ -> {
                sb.append("\n\n# Managed Policy\n\n");
                sb.append("The following policies are organization-managed. Follow them strictly.\n\n");
                sb.append(section.content().strip());
            }
            case MemoryTier.WorkspaceMemory _ -> {
                sb.append("\n\n# Project Instructions\n\n");
                sb.append("The following instructions are from the project's ACECLAW.md. ");
                sb.append("Follow them carefully.\n\n");
                sb.append(section.content().strip());
            }
            case MemoryTier.UserMemory _ -> {
                sb.append("\n\n# User Instructions\n\n");
                sb.append("The following instructions are from your global ~/.aceclaw/ACECLAW.md file. ");
                sb.append("Follow them carefully.\n\n");
                sb.append(section.content().strip());
            }
            case MemoryTier.LocalMemory _ -> {
                sb.append("\n\n# Local Developer Instructions\n\n");
                sb.append("The following instructions are from this project's ACECLAW.local.md ");
                sb.append("(per-developer, gitignored). Follow them carefully.\n\n");
                sb.append(section.content().strip());
            }
            case MemoryTier.AutoMemory _ -> {
                if (memoryStore != null && memoryStore.size() > 0) {
                    String memorySection = memoryStore.formatForPrompt(workspacePath, maxAutoMemoryEntries);
                    if (!memorySection.isEmpty()) {
                        sb.append(memorySection);
                    }
                } else {
                    sb.append("\n\n# Auto-Memory\n\n");
                    sb.append("No memories stored yet for this workspace. ");
                    sb.append("Memories will accumulate automatically as you work across sessions.\n");
                }
            }
            case MemoryTier.MarkdownMemory _ -> {
                sb.append("\n\n# Persistent Memory\n\n");
                if (markdownMemoryDir != null) {
                    sb.append("You have a persistent memory directory at `")
                            .append(markdownMemoryDir).append("/`.\n");
                    sb.append("Its contents persist across conversations.\n\n");
                    sb.append("Guidelines:\n");
                    sb.append("- `MEMORY.md` is always loaded into your system prompt ")
                            .append("— lines after 200 will be truncated, so keep it concise\n");
                    sb.append("- Create separate topic files (e.g., `debugging.md`, `patterns.md`) ")
                            .append("for detailed notes and link to them from MEMORY.md\n");
                    sb.append("- Use the read_file and write_file tools to update these files directly\n");
                    sb.append("- Record insights about problem constraints, strategies, and lessons learned\n");
                    sb.append("- Organize memory semantically by topic, not chronologically\n\n");
                } else {
                    sb.append("The following is from your persistent MEMORY.md file. ");
                    sb.append("You can update this file using standard file tools.\n\n");
                }
                if (section.content() != null) {
                    sb.append("## MEMORY.md\n\n");
                    sb.append(section.content().strip());
                } else {
                    sb.append("No MEMORY.md file exists yet. Create one when you have ");
                    sb.append("insights worth persisting across sessions.\n");
                }
            }
            case MemoryTier.Journal _ -> {
                sb.append("\n\n# Daily Journal\n\n");
                sb.append("Recent activity log from previous sessions:\n\n");
                sb.append(section.content().strip());
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    // -- Internal loading methods -----------------------------------------

    private static String loadSoul(Path aceclawHome, Path workspacePath) {
        // Workspace SOUL.md takes precedence over global
        if (workspacePath != null) {
            String workspaceSoul = loadFileContent(workspacePath.resolve(".aceclaw").resolve(SOUL_MD));
            if (workspaceSoul != null) return workspaceSoul;
        }
        return loadFileContent(aceclawHome.resolve(SOUL_MD));
    }

    private static String loadWorkspaceMemory(Path workspacePath) {
        if (workspacePath == null) return null;

        var sb = new StringBuilder();

        // Project root ACECLAW.md
        String rootInstructions = loadFileContent(workspacePath.resolve(ACECLAW_MD));
        if (rootInstructions != null) {
            sb.append(rootInstructions);
        }

        // Project .aceclaw/ACECLAW.md
        String dotAceclawInstructions = loadFileContent(
                workspacePath.resolve(".aceclaw").resolve(ACECLAW_MD));
        if (dotAceclawInstructions != null) {
            if (!sb.isEmpty()) sb.append("\n\n");
            sb.append(dotAceclawInstructions);
        }

        return sb.isEmpty() ? null : sb.toString();
    }

    private static String loadFileContent(Path file) {
        if (!Files.isRegularFile(file)) return null;
        try {
            String content = Files.readString(file);
            return content.isBlank() ? null : content;
        } catch (IOException e) {
            log.warn("Failed to read {}: {}", file, e.getMessage());
            return null;
        }
    }

    // -- Result types -----------------------------------------------------

    /**
     * Result of loading all memory tiers.
     *
     * @param soulContent    the soul content (may be null if no SOUL.md found)
     * @param tieredSections ordered list of loaded tier sections
     * @param tiersLoaded    count of tiers that had content
     */
    public record LoadResult(
            String soulContent,
            List<TierSection> tieredSections,
            int tiersLoaded
    ) {}

    /**
     * A single tier section with its content.
     *
     * @param tier    the memory tier type
     * @param content the loaded content (may be null for AutoMemory which is formatted separately)
     */
    public record TierSection(
            MemoryTier tier,
            String content
    ) {}
}

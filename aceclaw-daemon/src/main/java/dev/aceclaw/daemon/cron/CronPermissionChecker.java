package dev.aceclaw.daemon.cron;

import dev.aceclaw.core.agent.ToolPermissionChecker;
import dev.aceclaw.core.agent.ToolPermissionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * Permission checker for cron job execution.
 *
 * <p>Enforces a per-job tool allowlist. Read-only tools are always permitted;
 * additional tools can be explicitly allowed via the job's {@code allowedTools} set.
 */
public final class CronPermissionChecker implements ToolPermissionChecker {

    private static final Logger log = LoggerFactory.getLogger(CronPermissionChecker.class);

    /** Tools that are always auto-approved during cron execution (safe, read-only). */
    private static final Set<String> READ_ONLY_TOOLS = Set.of(
            "read_file", "glob", "grep", "list_directory");

    private final String jobId;
    private final Set<String> allowedTools;

    /**
     * Creates a permission checker for the given job.
     *
     * @param jobId        the job identifier (for logging)
     * @param allowedTools additional tools allowed beyond the read-only set
     */
    public CronPermissionChecker(String jobId, Set<String> allowedTools) {
        this.jobId = jobId;
        this.allowedTools = allowedTools != null ? Set.copyOf(allowedTools) : Set.of();
    }

    @Override
    public ToolPermissionResult check(String toolName, String inputJson) {
        if (READ_ONLY_TOOLS.contains(toolName) || allowedTools.contains(toolName)) {
            return ToolPermissionResult.ALLOWED;
        }
        log.debug("Cron job '{}': denied tool '{}' (allowed: {} + {})",
                jobId, toolName, READ_ONLY_TOOLS, allowedTools);
        return ToolPermissionResult.denied(
                "Tool '" + toolName + "' is not allowed for cron job '" + jobId + "'. "
                        + "Permitted tools: " + READ_ONLY_TOOLS + " + " + allowedTools);
    }
}

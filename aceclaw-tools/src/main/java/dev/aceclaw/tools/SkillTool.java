package dev.aceclaw.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.agent.CancellationAware;
import dev.aceclaw.core.agent.CancellationToken;
import dev.aceclaw.core.agent.SkillConfig;
import dev.aceclaw.core.agent.SkillContentResolver;
import dev.aceclaw.core.agent.SkillRegistry;
import dev.aceclaw.core.agent.SubAgentConfig;
import dev.aceclaw.core.agent.SubAgentRunner;
import dev.aceclaw.core.agent.Tool;
import dev.aceclaw.core.llm.StreamEventHandler;
import dev.aceclaw.security.Capability;
import dev.aceclaw.security.CapabilityAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Tool that invokes a skill by name with optional arguments.
 *
 * <p>Skills support two execution modes:
 * <ul>
 *   <li><b>INLINE</b> — the resolved skill content is returned as a tool result
 *       for the current agent to read and act upon</li>
 *   <li><b>FORK</b> — the resolved skill content is used as a system prompt for
 *       a sub-agent that runs in isolation via {@link SubAgentRunner}</li>
 * </ul>
 */
public final class SkillTool implements Tool, CapabilityAware, CancellationAware {

    private static final Logger log = LoggerFactory.getLogger(SkillTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SkillRegistry skillRegistry;
    private final SkillContentResolver contentResolver;
    private final SubAgentRunner subAgentRunner;
    private volatile CancellationToken cancellationToken;
    private volatile StreamEventHandler currentHandler;
    private volatile String currentSessionId;

    /**
     * Creates a skill tool.
     *
     * @param skillRegistry   the registry of available skills
     * @param contentResolver the content resolver for argument/command substitution
     * @param subAgentRunner  the sub-agent runner for FORK mode execution
     */
    public SkillTool(SkillRegistry skillRegistry, SkillContentResolver contentResolver,
                     SubAgentRunner subAgentRunner) {
        this(skillRegistry, contentResolver, subAgentRunner, null, null);
    }

    private SkillTool(SkillRegistry skillRegistry, SkillContentResolver contentResolver,
                      SubAgentRunner subAgentRunner, StreamEventHandler currentHandler,
                      String currentSessionId) {
        this.skillRegistry = skillRegistry;
        this.contentResolver = contentResolver;
        this.subAgentRunner = subAgentRunner;
        this.currentHandler = currentHandler;
        this.currentSessionId = currentSessionId;
    }

    @Override
    public void setCancellationToken(CancellationToken token) {
        this.cancellationToken = token;
    }

    /**
     * Creates a request-bound tool instance so concurrent sessions do not share mutable context.
     */
    public SkillTool forRequest(String sessionId, StreamEventHandler handler) {
        return new SkillTool(skillRegistry, contentResolver, subAgentRunner, handler, sessionId);
    }

    /**
     * Backward-compatible mutator used by tests; request code should prefer {@link #forRequest}.
     */
    public void setCurrentHandler(StreamEventHandler handler) {
        this.currentHandler = handler;
    }

    /**
     * Backward-compatible mutator used by tests; request code should prefer {@link #forRequest}.
     */
    public void setCurrentSessionId(String sessionId) {
        this.currentSessionId = sessionId;
    }

    @Override
    public String name() {
        return "skill";
    }

    @Override
    public String description() {
        return ToolDescriptionLoader.load(name());
    }

    @Override
    public JsonNode inputSchema() {
        var names = skillRegistry.names(currentSessionId);

        var builder = SchemaBuilder.object()
                .requiredProperty("name", names.isEmpty()
                        ? SchemaBuilder.string("The name of the skill to invoke (no skills currently available)")
                        : SchemaBuilder.stringEnum(
                                "The skill to invoke. Available: " + String.join(", ", names),
                                names.toArray(new String[0])))
                .optionalProperty("arguments", SchemaBuilder.string(
                        "Arguments to pass to the skill. Substituted into $ARGUMENTS, $1, $2, etc."));

        return builder.build();
    }

    @Override
    public ToolResult execute(String inputJson) throws Exception {
        var input = MAPPER.readTree(inputJson);

        if (!input.has("name") || input.get("name").asText().isBlank()) {
            return new ToolResult("Missing required parameter: name", true);
        }

        String skillName = input.get("name").asText();
        String arguments = input.has("arguments") ? input.get("arguments").asText() : "";

        var configOpt = skillRegistry.get(currentSessionId, skillName);
        if (configOpt.isEmpty()) {
            return new ToolResult(
                    "Unknown skill: " + skillName +
                    ". Available skills: " + String.join(", ", skillRegistry.names(currentSessionId)), true);
        }

        var config = configOpt.get();

        // Resolve the skill content (argument substitution + command preprocessing)
        String resolvedContent;
        try {
            resolvedContent = contentResolver.resolve(config, arguments);
        } catch (Exception e) {
            log.error("Failed to resolve skill '{}': {}", skillName, e.getMessage(), e);
            return new ToolResult("Skill resolution failed: " + e.getMessage(), true);
        }

        if (resolvedContent.isEmpty()) {
            return new ToolResult("Skill '" + skillName + "' has no content.", true);
        }

        log.info("Invoking skill '{}' (mode={}, args length={})",
                skillName, config.context(), arguments.length());

        return switch (config.context()) {
            case INLINE -> executeInline(skillName, resolvedContent);
            case FORK -> executeFork(config, skillName, resolvedContent, arguments);
        };
    }

    /**
     * Inline mode: returns the resolved skill content as a tool result.
     * The LLM reads the instructions and acts on them.
     */
    private ToolResult executeInline(String skillName, String resolvedContent) {
        log.info("Skill '{}' resolved inline ({} chars)", skillName, resolvedContent.length());
        return new ToolResult(resolvedContent, false);
    }

    /**
     * Fork mode: creates a SubAgentConfig from the skill and delegates to SubAgentRunner.
     */
    private ToolResult executeFork(SkillConfig config, String skillName,
                                   String resolvedContent, String arguments) {
        // Build a SubAgentConfig from the skill configuration
        var subConfig = new SubAgentConfig(
                "skill:" + skillName,
                config.description(),
                config.model(),
                config.allowedTools(),
                List.of("skill", "task"), // Prevent nesting of both skills and tasks
                config.maxTurns(),
                resolvedContent
        );

        // Build the prompt for the sub-agent
        String prompt = "Execute the skill instructions above.";
        if (arguments != null && !arguments.isBlank()) {
            prompt = "Execute the skill instructions above with arguments: " + arguments;
        }

        var handler = currentHandler;
        if (handler != null) {
            handler.onSubAgentStart("skill:" + skillName, prompt);
        }
        try {
            // #457: thread currentSessionId so the forked skill's sub-agent
            // sees the parent session's allow-list. SkillTool already
            // tracks currentSessionId via forRequest(...) — we just have to
            // forward it through the new 5-arg run() overload here.
            // Without this, every non-read-only tool the forked skill
            // tries to use gets fail-closed denied, mirroring the original
            // P1 that motivated #457 — just in a different code path.
            // (Codex re-review on #491, commit 03472f43b0.)
            var result = subAgentRunner.run(subConfig, prompt, handler,
                    cancellationToken, currentSessionId);
            if (result.isEmpty()) {
                return new ToolResult("Skill '" + skillName + "' completed but produced no output.", false);
            }
            return new ToolResult(result, false);
        } catch (Exception e) {
            log.error("Skill '{}' fork execution failed: {}", skillName, e.getMessage(), e);
            return new ToolResult("Skill execution error: " + e.getMessage(), true);
        } finally {
            if (handler != null) {
                handler.onSubAgentEnd("skill:" + skillName);
            }
        }
    }

    /**
     * #480 PR 3: structured {@link Capability.SkillInvoke}. Modelled
     * separately from {@link Capability.McpInvoke} because skills are
     * versioned, in-process, first-party code units while MCP is an
     * out-of-process server — a policy that disables third-party MCP
     * servers should not also disable first-party skills, and vice versa.
     */
    @Override
    public Capability toCapability(JsonNode args) {
        if (args == null || !args.has("name") || args.get("name").asText().isBlank()) {
            throw new IllegalArgumentException("skill requires a non-blank name");
        }
        return new Capability.SkillInvoke(args.get("name").asText());
    }
}

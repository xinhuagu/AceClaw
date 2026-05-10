package dev.aceclaw.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.agent.Tool;
import dev.aceclaw.memory.AutoMemoryStore;
import dev.aceclaw.security.Capability;
import dev.aceclaw.security.CapabilityAware;
import dev.aceclaw.security.ids.MemoryKey;
import dev.aceclaw.memory.MemoryEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Tool for actively managing persistent memory across sessions.
 *
 * <p>Supports three actions: save (store a new memory), search (find relevant memories),
 * and list (browse memories by category).
 */
public final class MemoryTool implements Tool, CapabilityAware {

    private static final Logger log = LoggerFactory.getLogger(MemoryTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private static final int DEFAULT_SEARCH_LIMIT = 10;
    private static final int DEFAULT_LIST_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private static final String VALID_CATEGORIES = Arrays.stream(MemoryEntry.Category.values())
            .map(c -> c.name().toLowerCase())
            .collect(Collectors.joining(", "));

    private final AutoMemoryStore memoryStore;
    private final Path workingDir;

    public MemoryTool(AutoMemoryStore memoryStore, Path workingDir) {
        this.memoryStore = memoryStore;
        this.workingDir = workingDir;
    }

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public String description() {
        return ToolDescriptionLoader.load(name());
    }

    @Override
    public JsonNode inputSchema() {
        return SchemaBuilder.object()
                .requiredProperty("action", SchemaBuilder.stringEnum(
                        "The action to perform", "save", "search", "list", "delete"))
                .optionalProperty("content", SchemaBuilder.string(
                        "The memory content to save (required for 'save' action). " +
                        "Write a concise, actionable insight — not raw data."))
                .optionalProperty("category", SchemaBuilder.string(
                        "Memory category (required for 'save'). For 'search'/'list', acts as optional filter. " +
                        "Values: mistake, pattern, preference, codebase_insight, strategy, workflow, " +
                        "environment, relationship, terminology, constraint, decision, tool_usage, " +
                        "communication, context, correction, bookmark, session_summary, " +
                        "error_recovery, successful_strategy, anti_pattern, user_feedback"))
                .optionalProperty("tags", SchemaBuilder.string(
                        "Comma-separated tags for the memory (for 'save'). " +
                        "E.g. \"gradle, java, testing\""))
                .optionalProperty("query", SchemaBuilder.string(
                        "Natural language search query (required for 'search' action)"))
                .optionalProperty("limit", SchemaBuilder.integer(
                        "Maximum results to return (default: 10 for search, 20 for list, max: 50)"))
                .optionalProperty("global", SchemaBuilder.bool(
                        "If true, save to global memory (cross-project). Default: false (project-scoped)."))
                .optionalProperty("id", SchemaBuilder.string(
                        "Memory entry ID (required for 'delete' action). Use 'list' or 'search' to find IDs."))
                .build();
    }

    @Override
    public ToolResult execute(String inputJson) throws Exception {
        var input = MAPPER.readTree(inputJson);

        if (!input.has("action") || input.get("action").asText().isBlank()) {
            return new ToolResult("Missing required parameter: action", true);
        }

        var action = input.get("action").asText().toLowerCase();

        return switch (action) {
            case "save" -> executeSave(input);
            case "search" -> executeSearch(input);
            case "list" -> executeList(input);
            case "delete" -> executeDelete(input);
            default -> new ToolResult(
                    "Unknown action: " + action + ". Valid actions: save, search, list, delete", true);
        };
    }

    private ToolResult executeSave(JsonNode input) {
        // Validate required fields
        if (!input.has("content") || input.get("content").asText().isBlank()) {
            return new ToolResult("Missing required parameter for 'save': content", true);
        }
        if (!input.has("category") || input.get("category").asText().isBlank()) {
            return new ToolResult("Missing required parameter for 'save': category", true);
        }

        var content = input.get("content").asText();
        var categoryStr = input.get("category").asText().toUpperCase();

        // Parse category
        MemoryEntry.Category category;
        try {
            category = MemoryEntry.Category.valueOf(categoryStr);
        } catch (IllegalArgumentException e) {
            return new ToolResult(
                    "Invalid category: " + input.get("category").asText() +
                    ". Valid categories: " + VALID_CATEGORIES, true);
        }

        // Parse tags
        List<String> tags = List.of();
        if (input.has("tags") && !input.get("tags").isNull() && !input.get("tags").asText().isBlank()) {
            tags = Arrays.stream(input.get("tags").asText().split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }

        var global = input.has("global") && input.get("global").asBoolean(false);

        log.debug("Saving memory: category={}, tags={}, global={}", category, tags, global);

        var entry = memoryStore.add(category, content, tags, "tool:memory", global, workingDir);

        return new ToolResult(
                "Memory saved successfully.\n" +
                "  ID: " + entry.id() + "\n" +
                "  Category: " + category.name().toLowerCase() + "\n" +
                "  Tags: " + (tags.isEmpty() ? "(none)" : String.join(", ", tags)) + "\n" +
                "  Scope: " + (global ? "global" : "project"),
                false);
    }

    private ToolResult executeSearch(JsonNode input) {
        if (!input.has("query") || input.get("query").asText().isBlank()) {
            return new ToolResult("Missing required parameter for 'search': query", true);
        }

        var query = input.get("query").asText();

        if (hasCategoryParam(input)) {
            var category = parseCategory(input.get("category").asText());
            if (category == null) {
                return new ToolResult(
                        "Invalid category filter: " + input.get("category").asText() +
                        ". Valid categories: " + VALID_CATEGORIES, true);
            }

            int limit = getIntParam(input, "limit", DEFAULT_SEARCH_LIMIT);
            if (limit <= 0 || limit > MAX_LIMIT) limit = DEFAULT_SEARCH_LIMIT;

            log.debug("Searching memory: query={}, category={}, limit={}", query, category, limit);
            var results = memoryStore.search(query, category, limit);

            if (results.isEmpty()) {
                return new ToolResult("No memories found matching query: " + query, false);
            }
            return new ToolResult(formatEntries(results, "Search results for: " + query), false);
        }

        int limit = getIntParam(input, "limit", DEFAULT_SEARCH_LIMIT);
        if (limit <= 0 || limit > MAX_LIMIT) limit = DEFAULT_SEARCH_LIMIT;

        log.debug("Searching memory: query={}, category=all, limit={}", query, limit);
        var results = memoryStore.search(query, null, limit);

        if (results.isEmpty()) {
            return new ToolResult("No memories found matching query: " + query, false);
        }
        return new ToolResult(formatEntries(results, "Search results for: " + query), false);
    }

    private ToolResult executeList(JsonNode input) {
        MemoryEntry.Category category = null;

        if (hasCategoryParam(input)) {
            category = parseCategory(input.get("category").asText());
            if (category == null) {
                return new ToolResult(
                        "Invalid category filter: " + input.get("category").asText() +
                        ". Valid categories: " + VALID_CATEGORIES, true);
            }
        }

        int limit = getIntParam(input, "limit", DEFAULT_LIST_LIMIT);
        if (limit <= 0 || limit > MAX_LIMIT) limit = DEFAULT_LIST_LIMIT;

        log.debug("Listing memories: category={}, limit={}", category, limit);

        var results = memoryStore.query(category, null, limit);

        if (results.isEmpty()) {
            var msg = category != null
                    ? "No memories found in category: " + category.name().toLowerCase()
                    : "No memories stored yet.";
            return new ToolResult(msg, false);
        }

        var header = category != null
                ? "Memories in category: " + category.name().toLowerCase()
                : "All memories";
        return new ToolResult(formatEntries(results, header), false);
    }

    private ToolResult executeDelete(JsonNode input) {
        if (!input.has("id") || input.get("id").asText().isBlank()) {
            return new ToolResult("Missing required parameter for 'delete': id. " +
                    "Use 'list' or 'search' to find the memory ID first.", true);
        }

        var id = input.get("id").asText();
        log.debug("Deleting memory: id={}", id);

        boolean removed = memoryStore.remove(id, workingDir);
        if (removed) {
            return new ToolResult("Memory deleted successfully. ID: " + id, false);
        } else {
            return new ToolResult("Memory not found with ID: " + id, true);
        }
    }

    // -- Helpers --------------------------------------------------------------

    /**
     * Returns true if the input has a non-blank category parameter.
     */
    private static boolean hasCategoryParam(JsonNode input) {
        return input.has("category") && !input.get("category").isNull() &&
                !input.get("category").asText().isBlank();
    }

    /**
     * Parses a category string (case-insensitive). Returns null if invalid.
     */
    private static MemoryEntry.Category parseCategory(String value) {
        try {
            return MemoryEntry.Category.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String formatEntries(List<MemoryEntry> entries, String header) {
        var sb = new StringBuilder();
        sb.append(header).append(" (").append(entries.size()).append(" entries)\n\n");

        for (var entry : entries) {
            sb.append("## [").append(entry.category().name()).append("] ");
            sb.append(entry.content());
            if (!entry.tags().isEmpty()) {
                sb.append(" [").append(String.join(", ", entry.tags())).append("]");
            }
            sb.append(" (").append(DATE_FMT.format(entry.createdAt()));
            sb.append(", id=").append(entry.id()).append(")\n");
        }

        return sb.toString().trim();
    }

    private static int getIntParam(JsonNode input, String field, int defaultValue) {
        if (input.has(field) && !input.get(field).isNull()) {
            return input.get(field).asInt(defaultValue);
        }
        return defaultValue;
    }

    /**
     * #480 PR 3: structured {@link Capability.MemoryRead} or
     * {@link Capability.MemoryWrite} depending on the action. {@code save}
     * and {@code delete} are both writes — delete drops a row, which is
     * still a mutating operation even if the visible field changes from
     * "stored content" to "absent" — so policies that gate memory mutation
     * cover both. {@code search} and {@code list} are pure reads.
     *
     * <p>{@link MemoryKey} is packed with the most-specific identifier the
     * action can supply (id for delete, query for search, category for
     * list / save) so PolicyEngine can pattern-match without having to
     * understand action semantics.
     */
    @Override
    public Capability toCapability(JsonNode args) {
        if (args == null || !args.has("action") || args.get("action").asText().isBlank()) {
            throw new IllegalArgumentException("memory requires a non-blank action");
        }
        String action = args.get("action").asText().toLowerCase();
        String tier = (args.has("global") && args.get("global").asBoolean(false)) ? "global" : "project";
        return switch (action) {
            case "save" -> {
                if (!args.has("category") || args.get("category").asText().isBlank()) {
                    throw new IllegalArgumentException("memory save requires a non-blank category");
                }
                yield new Capability.MemoryWrite(new MemoryKey(args.get("category").asText()), tier);
            }
            case "delete" -> {
                if (!args.has("id") || args.get("id").asText().isBlank()) {
                    throw new IllegalArgumentException("memory delete requires a non-blank id");
                }
                yield new Capability.MemoryWrite(new MemoryKey(args.get("id").asText()), tier);
            }
            case "search" -> {
                if (!args.has("query") || args.get("query").asText().isBlank()) {
                    throw new IllegalArgumentException("memory search requires a non-blank query");
                }
                yield new Capability.MemoryRead(new MemoryKey(args.get("query").asText()));
            }
            case "list" -> {
                String key = (args.has("category") && !args.get("category").isNull()
                        && !args.get("category").asText().isBlank())
                        ? args.get("category").asText()
                        : "*";
                yield new Capability.MemoryRead(new MemoryKey(key));
            }
            default -> throw new IllegalArgumentException(
                    "memory action must be save/search/list/delete; got " + action);
        };
    }
}

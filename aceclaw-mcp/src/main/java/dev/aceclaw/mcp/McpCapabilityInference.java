package dev.aceclaw.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import dev.aceclaw.security.Capability;
import dev.aceclaw.security.WriteMode;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Best-effort mapping from an MCP {@code (methodName, args)} pair to a
 * structured {@link Capability} variant.
 *
 * <p>Extracted from {@link McpToolBridge} so the bridge can stay a thin
 * {@code Tool} adapter and the inference rules can be evolved + unit-tested
 * independently. Pure utility class; no instance state.
 *
 * <h3>What gets inferred and why</h3>
 *
 * The structural denial layer in {@link DefaultPermissionPolicy} only matches
 * on {@link Capability.FileWrite} / {@link Capability.FileDelete} variants —
 * an MCP call that lands as opaque {@link Capability.McpInvoke} would skip
 * the hard-denial rules even when its args target {@code .env} or {@code .ssh/}.
 * This class promotes the common file-op patterns (write/delete/move/copy)
 * to the structured variants so the same rules fire uniformly.
 *
 * <h3>What gets classified as what</h3>
 *
 * <ul>
 *   <li>Method name matches {@code write|create|edit|append|put|save} (prefix
 *       or suffix) with a {@code path}-style arg → {@link Capability.FileWrite}.</li>
 *   <li>Method name matches {@code delete|remove|unlink|rm} with a similar
 *       arg → {@link Capability.FileDelete}.</li>
 *   <li>Method name matches {@code move|rename|copy|mv|cp} with a
 *       destination-style arg → see disambiguation in
 *       {@link #inferMoveCopy(String, JsonNode)}.</li>
 *   <li>Anything else → {@code null} (caller falls back to
 *       {@link Capability.McpInvoke}).</li>
 * </ul>
 *
 * <p>Conservative on both sides. False positives — a non-filesystem method
 * named {@code write_log(path=...)} would be classified as {@code FileWrite}
 * — only result in a more aggressive prompt; the user can still approve.
 * False negatives — an obscurely named file op that misses the patterns —
 * fall back to the standard MCP prompt.
 *
 * <p>The args payload itself is intentionally not retained on the emitted
 * variant — args can be huge and may carry secrets; policies decide on
 * {@code (server, method)} or the resolved path alone.
 *
 * <p>Move/copy ops emit a {@link Capability.FileMove} carrying both source
 * and destination — the policy's {@code evaluateStructural} checks both
 * endpoints. No sensitivity probing at this layer (which previously coupled
 * the inference to {@code DefaultPermissionPolicy}); the inference is now
 * pure data classification.
 */
final class McpCapabilityInference {

    private McpCapabilityInference() {}

    /**
     * Returns the inferred structured {@link Capability} for the given MCP
     * method invocation, or {@code null} when no inference rule applies (the
     * caller should fall back to {@link Capability.McpInvoke}).
     */
    static Capability infer(String mcpMethodName, JsonNode args) {
        if (args == null || args.isNull() || !args.isObject()) return null;
        String name = normalizeMethodName(mcpMethodName);

        if (WRITE_VERB.matcher(name).matches()) {
            // Most single-write methods use a `path`-style field. Some
            // weirder names (`write_to_destination(...)`) use a destination-
            // style field; cascade so they're caught too.
            Path p = safePath(extractField(args, PATH_FIELDS));
            if (p == null) p = safePath(extractField(args, DESTINATION_FIELDS));
            return p == null ? null : new Capability.FileWrite(p, WriteMode.OVERWRITE);
        }
        if (DELETE_VERB.matcher(name).matches()) {
            Path p = safePath(extractField(args, PATH_FIELDS));
            if (p == null) p = safePath(extractField(args, SOURCE_FIELDS));
            return p == null ? null : new Capability.FileDelete(p);
        }
        if (MOVE_DEST_VERB.matcher(name).matches()) {
            return inferMoveCopy(name, args);
        }
        return null;
    }

    /**
     * Two-arg op (move/rename/copy) classification. Emits a structured
     * {@link Capability.FileMove} carrying both endpoints when both resolve,
     * so the policy's {@code evaluateStructural} can check both sides for
     * sensitivity in a single pass — and the audit log accurately records
     * {@code @type=FileMove} for what is actually a move/copy.
     *
     * <p>When only one endpoint resolves (malformed args from the MCP
     * server), degrades gracefully:
     * <ul>
     *   <li>dst-only → {@link Capability.FileWrite} (destination is being
     *       written; nothing to say about source).</li>
     *   <li>src-only on a move → {@link Capability.FileDelete} (source is
     *       being removed; no destination to check). Copies with no
     *       destination fall through to {@link Capability.McpInvoke} since
     *       a copy with no target is meaningless and a {@code FileRead}
     *       wouldn't trigger structural rules anyway.</li>
     *   <li>Neither → {@code null}, caller falls back to
     *       {@link Capability.McpInvoke}.</li>
     * </ul>
     */
    private static Capability inferMoveCopy(String normalizedName, JsonNode args) {
        Path dst = safePath(extractField(args, DESTINATION_FIELDS));
        Path src = safePath(extractField(args, SOURCE_FIELDS));
        boolean deletesSource = !COPY_VERB.matcher(normalizedName).matches();

        if (src != null && dst != null) {
            return new Capability.FileMove(src, dst, deletesSource);
        }
        if (dst != null) return new Capability.FileWrite(dst, WriteMode.OVERWRITE);
        if (src != null && deletesSource) return new Capability.FileDelete(src);
        return null;
    }

    // ---------------------------------------------------------------------
    // Verb patterns
    // ---------------------------------------------------------------------

    /**
     * Method names with clear file-write intent (matched against the
     * normalized snake_case form). Prefix and suffix alternations include
     * the short {@code put} form on both sides so {@code foo_put} matches
     * just like {@code put_foo} does.
     */
    private static final Pattern WRITE_VERB = Pattern.compile(
            "^(write|create|edit|append|put|save)(_.*)?$|.*_(write|create|edit|append|save|put)$");

    /**
     * Method names with clear file-delete intent. Short {@code rm} included
     * on both sides for the same reason as {@link #WRITE_VERB}.
     */
    private static final Pattern DELETE_VERB = Pattern.compile(
            "^(delete|remove|unlink|rm)(_.*)?$|.*_(delete|remove|unlink|rm)$");

    /**
     * Method names for two-arg destination-receiving ops: move/rename/copy.
     * Short {@code mv}/{@code cp} forms included on both sides.
     */
    private static final Pattern MOVE_DEST_VERB = Pattern.compile(
            "^(move|mv|rename|copy|cp)(_.*)?$|.*_(move|rename|copy|mv|cp)$");

    /**
     * Subset of {@link #MOVE_DEST_VERB} that does NOT remove the source —
     * copy/cp. Used to decide whether a sensitive source path should be
     * treated as a delete: copy doesn't delete the source, move does.
     */
    private static final Pattern COPY_VERB = Pattern.compile(
            "^(copy|cp)(_.*)?$|.*_(copy|cp)$");

    // ---------------------------------------------------------------------
    // Field-name lookups
    // ---------------------------------------------------------------------

    /**
     * Common keys MCP filesystem-style servers use for the target path of
     * a single-arg write/delete. Order matters only for readability — only
     * the first match is extracted.
     */
    private static final List<String> PATH_FIELDS = List.of(
            "path", "file_path", "filepath", "filename", "file");

    /**
     * Common keys for the destination of a two-arg op (move/rename/copy).
     * The destination receives a new file, so it's effectively a write.
     */
    private static final List<String> DESTINATION_FIELDS = List.of(
            "destination", "dest", "target", "to", "new_path", "output_path", "output");

    /**
     * Common keys for the source of a two-arg op. For moves (not copies)
     * the source disappears, so it's effectively a delete.
     */
    private static final List<String> SOURCE_FIELDS = List.of(
            "source", "src", "from", "old_path", "input_path", "input");

    /**
     * Looks up a field in {@code args} matching any of {@code keys}, tolerant
     * of casing and underscore differences. So {@code path}, {@code Path},
     * {@code file_path}, {@code filePath}, {@code FILEPATH} all match the
     * canonical entry {@code path} or {@code file_path} in the keys list.
     * Necessary because MCP servers in the wild use mixed conventions
     * (snake_case in the official filesystem server, camelCase in many
     * third-party servers).
     */
    private static String extractField(JsonNode args, List<String> keys) {
        // Build a normalized → original lookup over args once. Cheap (args
        // objects are small) and avoids O(keys × argFields) repeat scans.
        Map<String, String> argKeysByNormalized = new HashMap<>();
        var iter = args.fieldNames();
        while (iter.hasNext()) {
            String k = iter.next();
            argKeysByNormalized.putIfAbsent(normalizeFieldName(k), k);
        }
        for (String key : keys) {
            String original = argKeysByNormalized.get(normalizeFieldName(key));
            if (original != null) {
                var node = args.get(original);
                if (node != null && node.isTextual()) {
                    return node.asText();
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // Normalization
    // ---------------------------------------------------------------------

    /**
     * Folds casing + strips underscores so {@code filePath}, {@code file_path},
     * {@code FilePath}, {@code FILEPATH} all normalize to {@code filepath}.
     * Locale.ROOT for stable case-folding regardless of host locale.
     */
    private static String normalizeFieldName(String s) {
        return s.toLowerCase(Locale.ROOT).replace("_", "");
    }

    /**
     * Normalizes a method name to snake_case so the verb regexes — which
     * expect snake_case or bare verbs — can match across naming conventions.
     * {@code writeFile} / {@code write-file} / {@code WriteFile} all
     * normalize to {@code write_file}.
     *
     * <p>Algorithm (order matters):
     * <ol>
     *   <li>{@code -} and {@code .} → {@code _} (kebab and dotted forms).</li>
     *   <li>Insert {@code _} before each uppercase that follows a lowercase
     *       or digit (camelCase split).</li>
     *   <li>Lowercase under {@link Locale#ROOT}.</li>
     * </ol>
     */
    private static String normalizeMethodName(String s) {
        String collapsed = s.replace('-', '_').replace('.', '_');
        var sb = new StringBuilder(collapsed.length() + 4);
        for (int i = 0; i < collapsed.length(); i++) {
            char c = collapsed.charAt(i);
            if (i > 0 && Character.isUpperCase(c)) {
                char prev = collapsed.charAt(i - 1);
                if (Character.isLowerCase(prev) || Character.isDigit(prev)) {
                    sb.append('_');
                }
            }
            sb.append(c);
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses {@code raw} into a {@link Path} or returns {@code null} when the
     * input is blank or malformed. Malformed paths fall back so the caller
     * can route through {@link Capability.McpInvoke} rather than crash the
     * dispatcher.
     */
    private static Path safePath(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Path.of(raw);
        } catch (InvalidPathException malformed) {
            return null;
        }
    }
}

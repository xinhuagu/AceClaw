package dev.aceclaw.core.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session-scoped detector that tracks {@code (tool, normalizedArgsHash)} pairs
 * across turns to prevent doom loops where the agent retries the exact same
 * failing tool call repeatedly.
 *
 * <p>Unlike {@link ToolFailureAdvisor} which resets each turn and classifies by
 * failure category, this detector persists for the entire session and tracks
 * exact argument fingerprints. It blocks identical calls after
 * {@link #BLOCK_THRESHOLD} consecutive failures and warns after
 * {@link #WARN_THRESHOLD}.
 *
 * <p>After a block is lifted (the agent succeeds with a different approach),
 * the fingerprint enters an exponential cooldown: if it fails again, the
 * cooldown period doubles (2 -> 4 -> 8 -> 16 -> 32 iterations, capped at 32).
 *
 * <p>Thread-safe: uses {@link ConcurrentHashMap} for parallel tool execution
 * within {@code StructuredTaskScope}.
 */
public final class DoomLoopDetector {

    private static final Logger log = LoggerFactory.getLogger(DoomLoopDetector.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Number of consecutive identical failures before warning. */
    static final int WARN_THRESHOLD = 1;

    /** Number of consecutive identical failures before blocking. */
    static final int BLOCK_THRESHOLD = 2;

    /** Default initial cooldown iterations after a block is lifted. */
    private static final int INITIAL_COOLDOWN = 2;

    private final ConcurrentHashMap<String, FailureRecord> failureMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CooldownEntry> cooldowns = new ConcurrentHashMap<>();

    /**
     * Record tracking consecutive failure counts for a specific tool+args fingerprint.
     *
     * @param count     consecutive failure count
     * @param lastError last error message (for diagnostics)
     * @param firstSeen when this fingerprint first failed
     */
    record FailureRecord(int count, String lastError, Instant firstSeen) {
        FailureRecord {
            if (count < 0) throw new IllegalArgumentException("count must be >= 0");
        }
    }

    /**
     * Record tracking exponential cooldown state for a previously blocked fingerprint.
     *
     * @param blockedCount       number of times this fingerprint has been blocked
     * @param cooldownIterations current cooldown period in tool iterations
     * @param iterationsSinceBlock iterations elapsed since the last block
     */
    record CooldownEntry(int blockedCount, int cooldownIterations, int iterationsSinceBlock) {
        CooldownEntry {
            if (blockedCount < 0) throw new IllegalArgumentException("blockedCount must be >= 0");
            if (cooldownIterations < 0) throw new IllegalArgumentException("cooldownIterations must be >= 0");
            if (iterationsSinceBlock < 0) throw new IllegalArgumentException("iterationsSinceBlock must be >= 0");
        }
    }

    /**
     * Sealed verdict returned by {@link #preCheck(String, String)}.
     */
    public sealed interface Verdict {
        /** Allow the tool call to proceed without intervention. */
        record Allow() implements Verdict {}

        /** Allow but warn: the same call has failed before. */
        record Warn(String advice, int failCount) implements Verdict {
            public Warn {
                if (advice == null || advice.isBlank()) throw new IllegalArgumentException("advice required");
            }
        }

        /** Block the tool call: too many consecutive identical failures. */
        record Block(String reason, int failCount) implements Verdict {
            public Block {
                if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason required");
            }
        }
    }

    /**
     * Checks whether the given tool call should be allowed, warned, or blocked.
     * Called before tool execution.
     *
     * @param toolName  the tool name (must not be null)
     * @param inputJson the tool input JSON string (may be null or empty)
     * @return the verdict: Allow, Warn, or Block
     */
    public Verdict preCheck(String toolName, String inputJson) {
        if (toolName == null) return new Verdict.Allow();
        String fp = fingerprint(toolName, inputJson);

        // Check cooldown: if fingerprint is in cooldown and hasn't expired, block
        var cooldown = cooldowns.get(fp);
        if (cooldown != null && cooldown.iterationsSinceBlock() < cooldown.cooldownIterations()) {
            String reason = ("Doom loop cooldown active: tool=%s has been blocked %d time(s). " +
                    "Cooldown: %d/%d iterations remaining. Try a fundamentally different approach.")
                    .formatted(toolName, cooldown.blockedCount(),
                            cooldown.cooldownIterations() - cooldown.iterationsSinceBlock(),
                            cooldown.cooldownIterations());
            var record = failureMap.get(fp);
            int failCount = record != null ? record.count() : 0;
            return new Verdict.Block(reason, failCount);
        }

        var record = failureMap.get(fp);
        if (record == null) {
            return new Verdict.Allow();
        }

        if (record.count() >= BLOCK_THRESHOLD) {
            String reason = ("Doom loop detected: tool=%s called with identical arguments failed %d " +
                    "consecutive times. Last error: %s. " +
                    "This call is blocked. Change your approach or arguments before retrying.")
                    .formatted(toolName, record.count(), truncate(record.lastError(), 200));
            return new Verdict.Block(reason, record.count());
        }

        if (record.count() >= WARN_THRESHOLD) {
            String advice = ("Warning: tool=%s with these arguments has already failed %d time(s). " +
                    "Last error: %s. Consider changing your approach before retrying.")
                    .formatted(toolName, record.count(), truncate(record.lastError(), 200));
            return new Verdict.Warn(advice, record.count());
        }

        return new Verdict.Allow();
    }

    /**
     * Records the result of a tool execution to update failure tracking.
     * Called after tool execution (both success and failure).
     *
     * @param toolName  the tool name
     * @param inputJson the tool input JSON
     * @param isError   whether the tool returned an error
     * @param errorText the error text (only meaningful when isError=true)
     */
    public void recordResult(String toolName, String inputJson, boolean isError, String errorText) {
        if (toolName == null) return;
        String fp = fingerprint(toolName, inputJson);

        // Tick cooldowns for all fingerprints (each tool call counts as one iteration)
        tickCooldowns();

        if (isError) {
            failureMap.compute(fp, (_, existing) -> {
                if (existing == null) {
                    return new FailureRecord(1, errorText, Instant.now());
                }
                return new FailureRecord(existing.count() + 1, errorText, existing.firstSeen());
            });
        } else {
            // Success: check if this fingerprint was previously blocked
            var previous = failureMap.remove(fp);
            if (previous != null && previous.count() >= BLOCK_THRESHOLD) {
                // Enter cooldown: if it fails again, cooldown doubles
                cooldowns.compute(fp, (_, existing) -> {
                    if (existing == null) {
                        return new CooldownEntry(1, INITIAL_COOLDOWN, 0);
                    }
                    int newCooldown = Math.min(existing.cooldownIterations() * 2, 32);
                    return new CooldownEntry(existing.blockedCount() + 1, newCooldown, 0);
                });
                log.info("Doom loop fingerprint cleared by success, entering cooldown: tool={}", toolName);
            }
            // Also clear cooldown if success proves the approach works
            if (previous == null || previous.count() < BLOCK_THRESHOLD) {
                cooldowns.remove(fp);
            }
        }
    }

    /**
     * Increment iterationsSinceBlock for all cooldown entries.
     */
    private void tickCooldowns() {
        cooldowns.replaceAll((_, entry) ->
                new CooldownEntry(entry.blockedCount(), entry.cooldownIterations(),
                        entry.iterationsSinceBlock() + 1));
    }

    /**
     * Computes a deterministic fingerprint for a tool call by normalizing the
     * input JSON (sort keys recursively, strip whitespace) and hashing with SHA-256.
     *
     * <p>Falls back to hashing the raw string if JSON parsing fails.
     *
     * @param toolName  the tool name
     * @param inputJson the tool input JSON (may be null or empty)
     * @return a hex-encoded SHA-256 hash string
     */
    static String fingerprint(String toolName, String inputJson) {
        String normalizedInput;
        if (inputJson == null || inputJson.isBlank()) {
            normalizedInput = "{}";
        } else {
            try {
                var tree = JSON.readTree(inputJson);
                if (tree != null && tree.isObject()) {
                    normalizedInput = sortKeys(tree).toString();
                } else {
                    normalizedInput = inputJson.strip();
                }
            } catch (Exception e) {
                normalizedInput = inputJson.strip();
            }
        }

        String payload = toolName + ":" + normalizedInput;
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to be available in all Java implementations
            throw new AssertionError("SHA-256 not available", e);
        }
    }

    /**
     * Recursively sorts JSON object keys for deterministic normalization.
     */
    private static JsonNode sortKeys(JsonNode node) {
        if (node.isObject()) {
            var sorted = new TreeMap<String, JsonNode>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                sorted.put(entry.getKey(), sortKeys(entry.getValue()));
            }
            var objectNode = JSON.createObjectNode();
            sorted.forEach(objectNode::set);
            return objectNode;
        }
        if (node.isArray()) {
            var arrayNode = JSON.createArrayNode();
            for (var element : node) {
                arrayNode.add(sortKeys(element));
            }
            return arrayNode;
        }
        return node;
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "(no error text)";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }
}

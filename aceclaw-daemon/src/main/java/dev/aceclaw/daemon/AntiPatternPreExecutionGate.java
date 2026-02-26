package dev.aceclaw.daemon;

import dev.aceclaw.memory.AutoMemoryStore;
import dev.aceclaw.memory.CandidateKind;
import dev.aceclaw.memory.CandidateState;
import dev.aceclaw.memory.CandidateStore;
import dev.aceclaw.memory.FailureType;
import dev.aceclaw.memory.MemoryEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pre-execution anti-pattern gate that can BLOCK or PENALIZE tool calls
 * based on learned anti-pattern rules.
 */
final class AntiPatternPreExecutionGate {

    private static final Logger log = LoggerFactory.getLogger(AntiPatternPreExecutionGate.class);
    private static final Duration REFRESH_INTERVAL = Duration.ofSeconds(10);
    private static final Set<String> STOP_WORDS = Set.of(
            "avoid", "causes", "cause", "recurring", "errors", "error", "with", "from",
            "this", "that", "when", "then", "and", "the", "for", "into", "tool");
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^a-z0-9_]+");

    private final RuleSource source;
    private final RuleFeedbackProvider feedbackProvider;
    private volatile CachedRules cache = new CachedRules(Instant.EPOCH, List.of());

    AntiPatternPreExecutionGate(RuleSource source) {
        this(source, RuleFeedbackProvider.noop());
    }

    AntiPatternPreExecutionGate(RuleSource source, RuleFeedbackProvider feedbackProvider) {
        this.source = Objects.requireNonNull(source, "source");
        this.feedbackProvider = Objects.requireNonNull(feedbackProvider, "feedbackProvider");
    }

    static AntiPatternPreExecutionGate fromStores(AutoMemoryStore memoryStore, CandidateStore candidateStore) {
        return new AntiPatternPreExecutionGate(
                new StoreBackedRuleSource(memoryStore, candidateStore),
                RuleFeedbackProvider.noop());
    }

    static AntiPatternPreExecutionGate fromStores(
            AutoMemoryStore memoryStore,
            CandidateStore candidateStore,
            RuleFeedbackProvider feedbackProvider) {
        return new AntiPatternPreExecutionGate(
                new StoreBackedRuleSource(memoryStore, candidateStore),
                feedbackProvider);
    }

    Decision evaluate(String toolName, String inputJson, String toolDescription) {
        Objects.requireNonNull(toolName, "toolName");
        var rules = loadRules();
        if (rules.isEmpty()) {
            return Decision.allow();
        }
        var combinedContext = (inputJson == null ? "" : inputJson) + " " +
                (toolDescription == null ? "" : toolDescription);
        var contextTokens = tokenize(combinedContext);
        var contextFailureTypes = inferFailureTypes(combinedContext);

        Rule bestPenalize = null;
        int bestPenalizeOverlap = -1;

        for (var rule : rules) {
            if (!matchesTool(rule, toolName)) {
                continue;
            }
            int overlap = overlap(rule.keywords(), contextTokens);
            boolean hasKeywords = !rule.keywords().isEmpty();
            boolean hasFailureTypes = !rule.failureTypes().isEmpty();
            boolean keywordMatch = hasKeywords && overlap > 0;
            boolean failureTypeMatch = hasFailureTypes && intersects(rule.failureTypes(), contextFailureTypes);
            boolean matches =
                    (hasKeywords && hasFailureTypes && keywordMatch && failureTypeMatch)
                            || (hasKeywords && !hasFailureTypes && keywordMatch)
                            || (!hasKeywords && hasFailureTypes && failureTypeMatch);
            if (!matches) {
                continue;
            }
            if (rule.action() == Action.BLOCK) {
                if (shouldDowngradeToPenalize(rule.ruleId())) {
                    return Decision.penalize(
                            rule.ruleId(),
                            rule.reason(),
                            rule.fallback());
                }
                return Decision.block(rule.ruleId(), rule.reason(), rule.fallback());
            }
            if (rule.action() == Action.PENALIZE && overlap >= bestPenalizeOverlap) {
                bestPenalizeOverlap = overlap;
                bestPenalize = rule;
            }
        }

        if (bestPenalize != null) {
            return Decision.penalize(bestPenalize.ruleId(), bestPenalize.reason(), bestPenalize.fallback());
        }
        return Decision.allow();
    }

    private boolean shouldDowngradeToPenalize(String ruleId) {
        var stats = feedbackProvider.statsFor(ruleId);
        if (stats == null) {
            return false;
        }
        if (stats.blockedCount() < 3) {
            return false;
        }
        double falsePositiveRate = stats.falsePositiveRate();
        return falsePositiveRate >= 0.5;
    }

    private List<Rule> loadRules() {
        var current = cache;
        Instant now = Instant.now();
        if (Duration.between(current.loadedAt(), now).compareTo(REFRESH_INTERVAL) < 0) {
            return current.rules();
        }
        synchronized (this) {
            current = cache;
            now = Instant.now();
            if (Duration.between(current.loadedAt(), now).compareTo(REFRESH_INTERVAL) < 0) {
                return current.rules();
            }
            List<Rule> loaded;
            try {
                loaded = source.load();
                loaded = loaded != null ? List.copyOf(loaded) : List.of();
            } catch (Exception e) {
                log.warn("Failed to load anti-pattern rules, keeping previous cache", e);
                return current.rules();
            }
            cache = new CachedRules(now, loaded);
            return loaded;
        }
    }

    private static boolean matchesTool(Rule rule, String toolName) {
        String tag = rule.toolTag();
        return tag == null || tag.isBlank() || "general".equals(tag) || tag.equals(toolName);
    }

    private static int overlap(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        int count = 0;
        for (var token : a) {
            if (b.contains(token)) count++;
        }
        return count;
    }

    private static boolean intersects(Set<FailureType> a, Set<FailureType> b) {
        if (a.isEmpty() || b.isEmpty()) return false;
        for (var item : a) {
            if (b.contains(item)) {
                return true;
            }
        }
        return false;
    }

    static Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) return Set.of();
        var result = new HashSet<String>();
        for (var raw : TOKEN_SPLIT.split(text.toLowerCase(Locale.ROOT))) {
            if (raw.isBlank()) continue;
            if (raw.length() < 3) continue;
            if (STOP_WORDS.contains(raw)) continue;
            result.add(raw);
        }
        return Set.copyOf(result);
    }

    static Set<FailureType> inferFailureTypes(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        var types = new HashSet<FailureType>();
        if (normalized.contains("permission denied")) {
            types.add(FailureType.PERMISSION_DENIED);
        }
        if (normalized.contains("permission pending timeout")) {
            types.add(FailureType.PERMISSION_PENDING_TIMEOUT);
        }
        if (containsAny(normalized, "timeout", "timed out")) {
            types.add(FailureType.TIMEOUT);
        }
        if (containsAny(normalized, "module not found", "no module named", "not installed", "command not found")) {
            types.add(FailureType.DEPENDENCY_MISSING);
        }
        if (containsAny(normalized, "unsupported", "cannot parse", "parse error", "encrypted", "ole", "irm")) {
            types.add(FailureType.CAPABILITY_MISMATCH);
        }
        if (containsAny(normalized, "broken pipe", "status: failed")) {
            types.add(FailureType.BROKEN);
        }
        if (containsAny(normalized, "cancelled", "canceled")) {
            types.add(FailureType.CANCELLED);
        }
        return Set.copyOf(types);
    }

    private static boolean containsAny(String text, String... needles) {
        for (var needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    interface RuleSource {
        List<Rule> load();
    }

    private static final class StoreBackedRuleSource implements RuleSource {
        private final AutoMemoryStore memoryStore;
        private final CandidateStore candidateStore;

        StoreBackedRuleSource(AutoMemoryStore memoryStore, CandidateStore candidateStore) {
            this.memoryStore = memoryStore;
            this.candidateStore = candidateStore;
        }

        @Override
        public List<Rule> load() {
            var rules = new ArrayList<Rule>();

            if (candidateStore != null) {
                var promoted = candidateStore.byState(CandidateState.PROMOTED).stream()
                        .filter(c -> c.kind() == CandidateKind.ANTI_PATTERN)
                        .toList();
                for (var c : promoted) {
                    Action action = (c.score() >= 0.85 && c.evidenceCount() >= 3) ? Action.BLOCK : Action.PENALIZE;
                    rules.add(new Rule(
                            "candidate:" + c.id(),
                            c.toolTag(),
                            c.content(),
                            c.content(),
                            fallbackFor(c.toolTag(), c.content()),
                            action,
                            tokenize(c.content()),
                            inferFailureTypes(c.content())
                    ));
                }
            }

            if (memoryStore != null) {
                var anti = memoryStore.query(MemoryEntry.Category.ANTI_PATTERN, null, 200);
                for (var m : anti) {
                    String toolTag = firstToolTag(m.tags());
                    rules.add(new Rule(
                            "memory:" + m.id(),
                            toolTag,
                            m.content(),
                            m.content(),
                            fallbackFor(toolTag, m.content()),
                            Action.PENALIZE,
                            tokenize(m.content()),
                            inferFailureTypes(m.content())
                    ));
                }

                var failures = memoryStore.query(MemoryEntry.Category.FAILURE_SIGNAL, null, 200);
                for (var f : failures) {
                    String toolTag = firstToolTag(f.tags());
                    rules.add(new Rule(
                            "failure:" + f.id(),
                            toolTag,
                            f.content(),
                            f.content(),
                            fallbackFor(toolTag, f.content()),
                            Action.PENALIZE,
                            tokenize(f.content()),
                            inferFailureTypes(f.content())
                    ));
                }
            }

            rules.sort(Comparator.comparing(Rule::action).reversed());
            return List.copyOf(rules);
        }

        private static String firstToolTag(List<String> tags) {
            if (tags == null || tags.isEmpty()) return "general";
            for (var tag : tags) {
                if (tag == null || tag.isBlank()) continue;
                if ("anti-pattern".equals(tag)) continue;
                return tag;
            }
            return "general";
        }

        private static String fallbackFor(String toolTag, String content) {
            String tool = (toolTag == null || toolTag.isBlank()) ? "current tool" : toolTag;
            return "Avoid repeating this pattern on " + tool
                    + ". First inspect constraints/capabilities, then choose an alternative path.";
        }
    }

    enum Action {
        ALLOW,
        PENALIZE,
        BLOCK
    }

    record Rule(
            String ruleId,
            String toolTag,
            String reason,
            String sourceContent,
            String fallback,
            Action action,
            Set<String> keywords,
            Set<FailureType> failureTypes
    ) {}

    record Decision(Action action, String ruleId, String reason, String fallback) {
        static Decision allow() {
            return new Decision(Action.ALLOW, "", "", "");
        }

        static Decision penalize(String ruleId, String reason, String fallback) {
            return new Decision(Action.PENALIZE, ruleId, reason, fallback);
        }

        static Decision block(String ruleId, String reason, String fallback) {
            return new Decision(Action.BLOCK, ruleId, reason, fallback);
        }
    }

    private record CachedRules(Instant loadedAt, List<Rule> rules) {
        private CachedRules {
            loadedAt = Objects.requireNonNull(loadedAt, "loadedAt");
            rules = rules != null ? List.copyOf(rules) : List.of();
        }
    }

    interface RuleFeedbackProvider {
        RuleFeedbackStats statsFor(String ruleId);

        static RuleFeedbackProvider noop() {
            return _ -> RuleFeedbackStats.empty();
        }
    }

    record RuleFeedbackStats(int blockedCount, int falsePositiveCount) {
        static RuleFeedbackStats empty() {
            return new RuleFeedbackStats(0, 0);
        }

        double falsePositiveRate() {
            if (blockedCount <= 0) return 0.0;
            return falsePositiveCount / (double) blockedCount;
        }
    }
}

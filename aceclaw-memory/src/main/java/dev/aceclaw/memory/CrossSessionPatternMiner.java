package dev.aceclaw.memory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Mines recurring cross-session patterns from the historical log index.
 */
public final class CrossSessionPatternMiner {

    public static final int DEFAULT_SESSION_WINDOW = 20;
    private static final int MIN_ERROR_CHAIN_SUPPORT = 3;
    private static final int MIN_WORKFLOW_SUPPORT = 3;
    private static final int MIN_CONVERGENCE_SUPPORT = 3;
    private static final int MIN_DEGRADATION_SUPPORT = 4;

    public MiningResult mine(HistoricalLogIndex index,
                             AutoMemoryStore memoryStore,
                             String workspaceHash,
                             Path projectPath) {
        return mine(index, memoryStore, workspaceHash, projectPath, DEFAULT_SESSION_WINDOW);
    }

    public MiningResult mine(HistoricalLogIndex index,
                             AutoMemoryStore memoryStore,
                             String workspaceHash,
                             Path projectPath,
                             int sessionWindow) {
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(memoryStore, "memoryStore");
        Objects.requireNonNull(projectPath, "projectPath");

        int effectiveWindow = Math.max(1, sessionWindow);
        var recentSessions = recentSessionIds(index, workspaceHash, effectiveWindow);
        if (recentSessions.isEmpty()) {
            return new MiningResult(List.of(), List.of(), List.of(), List.of());
        }
        var chronologicalSessions = new ArrayList<>(recentSessions);
        chronologicalSessions.sort(Comparator.comparingInt(recentSessions::indexOf).reversed());

        var recentSessionSet = Set.copyOf(recentSessions);
        var toolEntries = index.toolInvocations(workspaceHash, null, null).stream()
                .filter(entry -> recentSessionSet.contains(entry.sessionId()))
                .toList();
        var errorEntries = index.errorEntries(workspaceHash, null, null).stream()
                .filter(entry -> recentSessionSet.contains(entry.sessionId()))
                .toList();
        var patternEntries = index.patterns(workspaceHash, null, null).stream()
                .filter(entry -> recentSessionSet.contains(entry.sessionId()))
                .toList();

        var frequentErrorChains = frequentErrorSequences(errorEntries, chronologicalSessions, MIN_ERROR_CHAIN_SUPPORT);
        var stableWorkflows = stableWorkflows(patternEntries, recentSessions, MIN_WORKFLOW_SUPPORT);
        var convergingStrategies = convergingStrategies(stableWorkflows, patternEntries, toolEntries, chronologicalSessions);
        var degradationSignals = degradationSignals(toolEntries, chronologicalSessions, MIN_DEGRADATION_SUPPORT);

        persistPatterns(memoryStore, projectPath, frequentErrorChains, stableWorkflows, convergingStrategies, degradationSignals);
        return new MiningResult(
                List.copyOf(frequentErrorChains),
                List.copyOf(stableWorkflows),
                List.copyOf(convergingStrategies),
                List.copyOf(degradationSignals));
    }

    public List<FrequentErrorChain> frequentErrorSequences(HistoricalLogIndex index,
                                                           String workspaceHash,
                                                           int sessionWindow,
                                                           int minSupport) {
        var sessions = recentSessionIds(index, workspaceHash, Math.max(1, sessionWindow));
        var sessionSet = Set.copyOf(sessions);
        var errors = index.errorEntries(workspaceHash, null, null).stream()
                .filter(entry -> sessionSet.contains(entry.sessionId()))
                .toList();
        return frequentErrorSequences(errors, sessions, minSupport);
    }

    public List<ConvergingStrategy> convergingStrategies(HistoricalLogIndex index,
                                                         String workspaceHash,
                                                         int sessionWindow) {
        var sessions = recentSessionIds(index, workspaceHash, Math.max(1, sessionWindow));
        var chronologicalSessions = new ArrayList<>(sessions);
        chronologicalSessions.sort(Comparator.comparingInt(sessions::indexOf).reversed());
        var sessionSet = Set.copyOf(sessions);
        var tools = index.toolInvocations(workspaceHash, null, null).stream()
                .filter(entry -> sessionSet.contains(entry.sessionId()))
                .toList();
        var patterns = index.patterns(workspaceHash, null, null).stream()
                .filter(entry -> sessionSet.contains(entry.sessionId()))
                .toList();
        var workflows = stableWorkflows(patterns, sessions, MIN_WORKFLOW_SUPPORT);
        return convergingStrategies(workflows, patterns, tools, chronologicalSessions);
    }

    public List<DegradationSignal> degradationSignals(HistoricalLogIndex index,
                                                      String workspaceHash,
                                                      int sessionWindow) {
        var sessions = recentSessionIds(index, workspaceHash, Math.max(1, sessionWindow));
        var chronologicalSessions = new ArrayList<>(sessions);
        chronologicalSessions.sort(Comparator.comparingInt(sessions::indexOf).reversed());
        var sessionSet = Set.copyOf(sessions);
        var tools = index.toolInvocations(workspaceHash, null, null).stream()
                .filter(entry -> sessionSet.contains(entry.sessionId()))
                .toList();
        return degradationSignals(tools, chronologicalSessions, MIN_DEGRADATION_SUPPORT);
    }

    private static List<String> recentSessionIds(HistoricalLogIndex index, String workspaceHash, int sessionWindow) {
        var latestBySession = new LinkedHashMap<String, Instant>();
        for (var entry : index.toolInvocations(workspaceHash, null, null)) {
            latestBySession.merge(entry.sessionId(), entry.timestamp(), CrossSessionPatternMiner::latest);
        }
        for (var entry : index.errorEntries(workspaceHash, null, null)) {
            latestBySession.merge(entry.sessionId(), entry.timestamp(), CrossSessionPatternMiner::latest);
        }
        for (var entry : index.patterns(workspaceHash, null, null)) {
            latestBySession.merge(entry.sessionId(), entry.timestamp(), CrossSessionPatternMiner::latest);
        }
        return latestBySession.entrySet().stream()
                .sorted(Map.Entry.<String, Instant>comparingByValue().reversed())
                .limit(sessionWindow)
                .map(Map.Entry::getKey)
                .toList();
    }

    private static List<FrequentErrorChain> frequentErrorSequences(List<HistoricalLogIndex.ErrorEntry> errorEntries,
                                                                   List<String> recentSessions,
                                                                   int minSupport) {
        var order = sessionOrder(recentSessions);
        var grouped = errorEntries.stream()
                .sorted(Comparator.comparing((HistoricalLogIndex.ErrorEntry e) -> order.getOrDefault(e.sessionId(), Integer.MAX_VALUE))
                        .thenComparing(HistoricalLogIndex.ErrorEntry::sequence))
                .collect(Collectors.groupingBy(
                        HistoricalLogIndex.ErrorEntry::sessionId,
                        LinkedHashMap::new,
                        Collectors.toList()));

        var support = new LinkedHashMap<String, LinkedHashSet<String>>();
        for (var session : recentSessions) {
            var entries = grouped.get(session);
            if (entries == null || entries.size() < 2) {
                continue;
            }
            var classes = entries.stream()
                    .sorted(Comparator.comparingInt(HistoricalLogIndex.ErrorEntry::sequence))
                    .map(HistoricalLogIndex.ErrorEntry::errorClass)
                    .toList();
            for (int i = 0; i < classes.size() - 1; i++) {
                var a = classes.get(i);
                var b = classes.get(i + 1);
                if (a == null || b == null) {
                    continue;
                }
                String key = a.name() + "->" + b.name();
                support.computeIfAbsent(key, _ -> new LinkedHashSet<>()).add(session);
            }
        }

        var results = new ArrayList<FrequentErrorChain>();
        for (var entry : support.entrySet()) {
            int count = entry.getValue().size();
            if (count < minSupport) {
                continue;
            }
            String[] parts = entry.getKey().split("->", 2);
            var chain = List.of(ErrorClass.valueOf(parts[0]), ErrorClass.valueOf(parts[1]));
            double confidence = Math.min(1.0, 0.35 + count * 0.1);
            results.add(new FrequentErrorChain(chain, count, confidence, List.copyOf(entry.getValue())));
        }
        results.sort(Comparator.comparingInt(FrequentErrorChain::support).reversed());
        return results;
    }

    private static List<StableWorkflow> stableWorkflows(List<HistoricalLogIndex.PatternEntry> patternEntries,
                                                        List<String> recentSessions,
                                                        int minSupport) {
        var sessions = Set.copyOf(recentSessions);
        var grouped = patternEntries.stream()
                .filter(entry -> entry.patternType() == PatternType.WORKFLOW)
                .filter(entry -> sessions.contains(entry.sessionId()))
                .collect(Collectors.groupingBy(
                        entry -> normalize(entry.description()),
                        LinkedHashMap::new,
                        Collectors.toCollection(ArrayList::new)));

        var results = new ArrayList<StableWorkflow>();
        for (var entry : grouped.entrySet()) {
            var support = entry.getValue().stream()
                    .map(HistoricalLogIndex.PatternEntry::sessionId)
                    .distinct()
                    .count();
            if (support < minSupport) {
                continue;
            }
            double confidence = Math.min(1.0, 0.4 + support * 0.08);
            String description = entry.getValue().getFirst().description();
            var sessionIds = entry.getValue().stream()
                    .map(HistoricalLogIndex.PatternEntry::sessionId)
                    .distinct()
                    .toList();
            results.add(new StableWorkflow(description, (int) support, confidence, sessionIds));
        }
        results.sort(Comparator.comparingInt(StableWorkflow::support).reversed());
        return results;
    }

    private static List<ConvergingStrategy> convergingStrategies(List<StableWorkflow> stableWorkflows,
                                                                 List<HistoricalLogIndex.PatternEntry> patternEntries,
                                                                 List<HistoricalLogIndex.ToolInvocationEntry> toolEntries,
                                                                 List<String> recentSessions) {
        var order = sessionOrder(recentSessions);
        var invocationsBySession = toolEntries.stream()
                .collect(Collectors.groupingBy(
                        HistoricalLogIndex.ToolInvocationEntry::sessionId,
                        Collectors.summingInt(HistoricalLogIndex.ToolInvocationEntry::invocationCount)));

        var results = new ArrayList<ConvergingStrategy>();
        for (var workflow : stableWorkflows) {
            var sessionIds = patternEntries.stream()
                    .filter(entry -> entry.patternType() == PatternType.WORKFLOW)
                    .filter(entry -> normalize(entry.description()).equals(normalize(workflow.description())))
                    .map(HistoricalLogIndex.PatternEntry::sessionId)
                    .distinct()
                    .sorted(Comparator.comparingInt(id -> order.getOrDefault(id, Integer.MAX_VALUE)))
                    .toList();
            if (sessionIds.size() < MIN_CONVERGENCE_SUPPORT) {
                continue;
            }
            var invocationSeries = sessionIds.stream()
                    .map(id -> invocationsBySession.getOrDefault(id, 0))
                    .filter(count -> count > 0)
                    .toList();
            if (invocationSeries.size() < MIN_CONVERGENCE_SUPPORT) {
                continue;
            }
            int midpoint = invocationSeries.size() / 2;
            double earlyAverage = averageInts(invocationSeries.subList(0, Math.max(1, midpoint)));
            double lateAverage = averageInts(invocationSeries.subList(Math.max(1, midpoint), invocationSeries.size()));
            if (lateAverage >= earlyAverage - 1.0 || lateAverage >= earlyAverage * 0.9) {
                continue;
            }
            double confidence = Math.min(1.0, 0.45 + workflow.support() * 0.08);
            results.add(new ConvergingStrategy(
                    workflow.description(),
                    workflow.support(),
                    earlyAverage,
                    lateAverage,
                    confidence,
                    sessionIds));
        }
        results.sort(Comparator.comparingDouble(ConvergingStrategy::confidence).reversed());
        return results;
    }

    private static List<DegradationSignal> degradationSignals(List<HistoricalLogIndex.ToolInvocationEntry> toolEntries,
                                                              List<String> recentSessions,
                                                              int minSupport) {
        var order = sessionOrder(recentSessions);
        var grouped = toolEntries.stream()
                .sorted(Comparator.comparing((HistoricalLogIndex.ToolInvocationEntry e) -> order.getOrDefault(e.sessionId(), Integer.MAX_VALUE))
                        .thenComparing(HistoricalLogIndex.ToolInvocationEntry::timestamp))
                .collect(Collectors.groupingBy(HistoricalLogIndex.ToolInvocationEntry::tool,
                        LinkedHashMap::new,
                        Collectors.toCollection(ArrayList::new)));

        var results = new ArrayList<DegradationSignal>();
        for (var entry : grouped.entrySet()) {
            var series = entry.getValue().stream()
                    .map(tool -> new SessionErrorRate(tool.sessionId(), errorRate(tool)))
                    .distinct()
                    .toList();
            if (series.size() < minSupport) {
                continue;
            }
            int midpoint = series.size() / 2;
            double earlier = averageDoubles(series.subList(0, Math.max(1, midpoint)).stream()
                    .map(SessionErrorRate::errorRate).toList());
            double later = averageDoubles(series.subList(Math.max(1, midpoint), series.size()).stream()
                    .map(SessionErrorRate::errorRate).toList());
            if (later < earlier + 0.20 || later < earlier * 1.25) {
                continue;
            }
            double confidence = Math.min(1.0, 0.4 + series.size() * 0.08);
            results.add(new DegradationSignal(
                    entry.getKey(),
                    earlier,
                    later,
                    series.size(),
                    confidence,
                    series.stream().map(SessionErrorRate::sessionId).toList()));
        }
        results.sort(Comparator.comparingDouble(DegradationSignal::confidence).reversed());
        return results;
    }

    private static void persistPatterns(AutoMemoryStore memoryStore,
                                        Path projectPath,
                                        List<FrequentErrorChain> frequentErrorChains,
                                        List<StableWorkflow> stableWorkflows,
                                        List<ConvergingStrategy> convergingStrategies,
                                        List<DegradationSignal> degradationSignals) {
        for (var chain : frequentErrorChains) {
            String key = chain.chain().stream().map(Enum::name).collect(Collectors.joining("->"));
            memoryStore.addIfAbsent(
                    MemoryEntry.Category.ANTI_PATTERN,
                    "Across recent sessions, the error chain "
                            + key + " recurred in " + chain.support() + " sessions. Avoid repeating this recovery path.",
                    List.of("cross-session", "error-chain", "anti-pattern"),
                    "cross-session:error-chain:" + key,
                    false,
                    projectPath);
        }
        for (var workflow : stableWorkflows) {
            String key = stableWorkflowKey(workflow.description());
            memoryStore.addIfAbsent(
                    MemoryEntry.Category.WORKFLOW,
                    "Stable cross-session workflow observed in " + workflow.support()
                            + " sessions: " + workflow.description(),
                    List.of("cross-session", "stable-workflow", "workflow"),
                    "cross-session:workflow:" + key,
                    false,
                    projectPath);
        }
        for (var strategy : convergingStrategies) {
            String key = stableWorkflowKey(strategy.description());
            memoryStore.addIfAbsent(
                    MemoryEntry.Category.SUCCESSFUL_STRATEGY,
                    "A converging strategy is emerging: " + strategy.description()
                            + " improved from average " + format(strategy.earlyAverageSteps())
                            + " tool invocations to " + format(strategy.lateAverageSteps())
                            + " across recent sessions.",
                    List.of("cross-session", "converging-strategy", "successful-strategy"),
                    "cross-session:converging:" + key,
                    false,
                    projectPath);
        }
        for (var signal : degradationSignals) {
            String key = normalize(signal.toolName());
            memoryStore.addIfAbsent(
                    MemoryEntry.Category.FAILURE_SIGNAL,
                    "Tool '" + signal.toolName() + "' shows a degradation signal: average error rate rose from "
                            + format(signal.earlierErrorRate()) + " to " + format(signal.laterErrorRate())
                            + " across recent sessions.",
                    List.of(signal.toolName(), "cross-session", "degradation", "failure-signal"),
                    "cross-session:degradation:" + key,
                    false,
                    projectPath);
        }
    }

    private static Map<String, Integer> sessionOrder(List<String> sessions) {
        var order = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < sessions.size(); i++) {
            order.put(sessions.get(i), i);
        }
        return order;
    }

    private static Instant latest(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String stableWorkflowKey(String description) {
        String normalized = normalize(description);
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80);
    }

    private static double averageInts(List<Integer> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        return values.stream().mapToInt(Integer::intValue).average().orElse(0.0);
    }

    private static double averageDoubles(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private static double errorRate(HistoricalLogIndex.ToolInvocationEntry entry) {
        if (entry.invocationCount() <= 0) {
            return 0.0;
        }
        return (double) entry.errorCount() / entry.invocationCount();
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    public record MiningResult(
            List<FrequentErrorChain> frequentErrorChains,
            List<StableWorkflow> stableWorkflows,
            List<ConvergingStrategy> convergingStrategies,
            List<DegradationSignal> degradationSignals
    ) {}

    public record FrequentErrorChain(
            List<ErrorClass> chain,
            int support,
            double confidence,
            List<String> sessionIds
    ) {}

    public record StableWorkflow(
            String description,
            int support,
            double confidence,
            List<String> sessionIds
    ) {}

    public record ConvergingStrategy(
            String description,
            int support,
            double earlyAverageSteps,
            double lateAverageSteps,
            double confidence,
            List<String> sessionIds
    ) {}

    public record DegradationSignal(
            String toolName,
            double earlierErrorRate,
            double laterErrorRate,
            int support,
            double confidence,
            List<String> sessionIds
    ) {}

    private record SessionErrorRate(String sessionId, double errorRate) {}
}

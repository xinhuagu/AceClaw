package dev.aceclaw.memory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Detects rising and falling trends across recent historical sessions.
 */
public final class TrendDetector {

    public static final int DEFAULT_SESSION_WINDOW = 10;
    private static final int MIN_SERIES_SUPPORT = 4;

    private static final double ERROR_RATE_ABSOLUTE_THRESHOLD = 0.10;
    private static final double ERROR_RATE_RELATIVE_THRESHOLD = 0.35;
    private static final double DURATION_ABSOLUTE_THRESHOLD_MS = 250.0;
    private static final double DURATION_RELATIVE_THRESHOLD = 0.25;
    private static final double INVOCATION_ABSOLUTE_THRESHOLD = 1.0;
    private static final double INVOCATION_RELATIVE_THRESHOLD = 0.15;
    private static final double ERROR_FREQUENCY_ABSOLUTE_THRESHOLD = 0.75;
    private static final double ERROR_FREQUENCY_RELATIVE_THRESHOLD = 0.30;

    public List<Trend> detect(HistoricalLogIndex index, int windowSize) {
        return detect(index, null, null, null, windowSize);
    }

    public List<Trend> detect(HistoricalLogIndex index,
                              AutoMemoryStore memoryStore,
                              String workspaceHash,
                              Path projectPath) {
        return detect(index, memoryStore, workspaceHash, projectPath, DEFAULT_SESSION_WINDOW);
    }

    public List<Trend> detect(HistoricalLogIndex index,
                              AutoMemoryStore memoryStore,
                              String workspaceHash,
                              Path projectPath,
                              int windowSize) {
        Objects.requireNonNull(index, "index");
        String effectiveWorkspaceHash = workspaceHash;
        if (memoryStore != null) {
            Objects.requireNonNull(projectPath, "projectPath");
            effectiveWorkspaceHash = Objects.requireNonNull(workspaceHash,
                    "workspaceHash must be provided when persisting trends");
            if (effectiveWorkspaceHash.isBlank()) {
                throw new IllegalArgumentException("workspaceHash must not be blank when persisting trends");
            }
            String expectedWorkspaceHash = WorkspacePaths.workspaceHash(projectPath);
            if (!Objects.equals(expectedWorkspaceHash, effectiveWorkspaceHash)) {
                throw new IllegalArgumentException("workspaceHash does not match projectPath");
            }
        }
        int effectiveWindow = Math.max(1, windowSize);
        var windowData = loadWindowData(index, effectiveWorkspaceHash, effectiveWindow);
        if (windowData.recentSessions().isEmpty()) {
            return List.of();
        }

        var trends = new ArrayList<Trend>();
        trends.addAll(toolErrorRateTrends(windowData.toolEntries(), windowData.chronologicalSessions(), effectiveWindow));
        trends.addAll(toolDurationTrends(windowData.toolEntries(), windowData.chronologicalSessions(), effectiveWindow));
        trends.addAll(errorClassFrequencyTrends(windowData.errorEntries(), windowData.chronologicalSessions(), effectiveWindow));
        trends.addAll(overallInvocationTrends(windowData.toolEntries(), windowData.chronologicalSessions(), effectiveWindow));
        trends.sort(Comparator.comparingDouble((Trend trend) -> Math.abs(trend.magnitude())).reversed()
                .thenComparing(Trend::metric));

        if (memoryStore != null && !trends.isEmpty()) {
            persistTrends(memoryStore, projectPath, trends);
        }
        return List.copyOf(trends);
    }

    private static SessionWindowData loadWindowData(HistoricalLogIndex index, String workspaceHash, int sessionWindow) {
        var toolEntries = index.toolInvocations(workspaceHash, null, null);
        var errorEntries = index.errorEntries(workspaceHash, null, null);
        var patternEntries = index.patterns(workspaceHash, null, null);
        var recentSessions = recentSessionIds(toolEntries, errorEntries, patternEntries, sessionWindow);
        var chronologicalSessions = new ArrayList<>(recentSessions);
        chronologicalSessions.sort(Comparator.comparingInt(recentSessions::indexOf).reversed());
        var recentSessionSet = Set.copyOf(recentSessions);
        return new SessionWindowData(
                recentSessions,
                chronologicalSessions,
                toolEntries.stream().filter(entry -> recentSessionSet.contains(entry.sessionId())).toList(),
                errorEntries.stream().filter(entry -> recentSessionSet.contains(entry.sessionId())).toList()
        );
    }

    private static List<String> recentSessionIds(List<HistoricalLogIndex.ToolInvocationEntry> toolEntries,
                                                 List<HistoricalLogIndex.ErrorEntry> errorEntries,
                                                 List<HistoricalLogIndex.PatternEntry> patternEntries,
                                                 int sessionWindow) {
        var latestBySession = new LinkedHashMap<String, Instant>();
        for (var entry : toolEntries) {
            latestBySession.merge(entry.sessionId(), entry.timestamp(), TrendDetector::latest);
        }
        for (var entry : errorEntries) {
            latestBySession.merge(entry.sessionId(), entry.timestamp(), TrendDetector::latest);
        }
        for (var entry : patternEntries) {
            latestBySession.merge(entry.sessionId(), entry.timestamp(), TrendDetector::latest);
        }
        return latestBySession.entrySet().stream()
                .sorted(Map.Entry.<String, Instant>comparingByValue().reversed())
                .limit(sessionWindow)
                .map(Map.Entry::getKey)
                .toList();
    }

    private static List<Trend> toolErrorRateTrends(List<HistoricalLogIndex.ToolInvocationEntry> toolEntries,
                                                   List<String> chronologicalSessions,
                                                   int windowSize) {
        var order = sessionOrder(chronologicalSessions);
        return toolEntries.stream()
                .sorted(Comparator.comparing((HistoricalLogIndex.ToolInvocationEntry entry) ->
                        order.getOrDefault(entry.sessionId(), Integer.MAX_VALUE)))
                .collect(Collectors.groupingBy(
                        HistoricalLogIndex.ToolInvocationEntry::tool,
                        LinkedHashMap::new,
                        Collectors.toCollection(ArrayList::new)))
                .entrySet().stream()
                .map(entry -> trendForSeries(
                        entry.getKey() + ".errorRate",
                        entry.getKey(),
                        entry.getValue().stream()
                                .map(toolEntry -> new SessionValue(toolEntry.sessionId(), errorRate(toolEntry)))
                                .distinct()
                                .toList(),
                        ERROR_RATE_ABSOLUTE_THRESHOLD,
                        ERROR_RATE_RELATIVE_THRESHOLD,
                        windowSize,
                        (tool, direction, earlier, later, magnitude, effectiveWindow) ->
                                "Tool '" + tool + "' error rate " + directionVerb(direction)
                                        + " from " + formatPercent(earlier)
                                        + " to " + formatPercent(later)
                                        + " across the last " + effectiveWindow + " sessions."))
                .flatMap(OptionalTrend::stream)
                .toList();
    }

    private static List<Trend> toolDurationTrends(List<HistoricalLogIndex.ToolInvocationEntry> toolEntries,
                                                  List<String> chronologicalSessions,
                                                  int windowSize) {
        var order = sessionOrder(chronologicalSessions);
        return toolEntries.stream()
                .sorted(Comparator.comparing((HistoricalLogIndex.ToolInvocationEntry entry) ->
                        order.getOrDefault(entry.sessionId(), Integer.MAX_VALUE)))
                .collect(Collectors.groupingBy(
                        HistoricalLogIndex.ToolInvocationEntry::tool,
                        LinkedHashMap::new,
                        Collectors.toCollection(ArrayList::new)))
                .entrySet().stream()
                .map(entry -> trendForSeries(
                        entry.getKey() + ".avgDurationMs",
                        entry.getKey(),
                        entry.getValue().stream()
                                .map(toolEntry -> new SessionValue(toolEntry.sessionId(), averageDurationMs(toolEntry)))
                                .distinct()
                                .toList(),
                        DURATION_ABSOLUTE_THRESHOLD_MS,
                        DURATION_RELATIVE_THRESHOLD,
                        windowSize,
                        (tool, direction, earlier, later, magnitude, effectiveWindow) ->
                                "Tool '" + tool + "' average duration " + directionVerb(direction)
                                        + " from " + formatMillis(earlier)
                                        + " to " + formatMillis(later)
                                        + " across the last " + effectiveWindow + " sessions."))
                .flatMap(OptionalTrend::stream)
                .toList();
    }

    private static List<Trend> errorClassFrequencyTrends(List<HistoricalLogIndex.ErrorEntry> errorEntries,
                                                         List<String> chronologicalSessions,
                                                         int windowSize) {
        var order = sessionOrder(chronologicalSessions);
        return errorEntries.stream()
                .sorted(Comparator.comparing((HistoricalLogIndex.ErrorEntry entry) ->
                        order.getOrDefault(entry.sessionId(), Integer.MAX_VALUE))
                        .thenComparing(HistoricalLogIndex.ErrorEntry::sequence))
                .collect(Collectors.groupingBy(
                        HistoricalLogIndex.ErrorEntry::errorClass,
                        () -> new EnumMap<>(ErrorClass.class),
                        Collectors.toCollection(ArrayList::new)))
                .entrySet().stream()
                .map(entry -> trendForSeries(
                        entry.getKey().name().toLowerCase(Locale.ROOT) + ".frequency",
                        entry.getKey().name(),
                        perSessionErrorFrequency(entry.getValue(), chronologicalSessions).stream()
                                .map(pair -> new SessionValue(pair.sessionId(), pair.value()))
                                .toList(),
                        ERROR_FREQUENCY_ABSOLUTE_THRESHOLD,
                        ERROR_FREQUENCY_RELATIVE_THRESHOLD,
                        windowSize,
                        (errorClass, direction, earlier, later, magnitude, effectiveWindow) ->
                                "Error class '" + errorClass + "' frequency " + directionVerb(direction)
                                        + " from " + formatDecimal(earlier)
                                        + " to " + formatDecimal(later)
                                        + " per session across the last " + effectiveWindow + " sessions."))
                .flatMap(OptionalTrend::stream)
                .toList();
    }

    private static List<Trend> overallInvocationTrends(List<HistoricalLogIndex.ToolInvocationEntry> toolEntries,
                                                       List<String> chronologicalSessions,
                                                       int windowSize) {
        var totalBySession = toolEntries.stream()
                .collect(Collectors.groupingBy(
                        HistoricalLogIndex.ToolInvocationEntry::sessionId,
                        LinkedHashMap::new,
                        Collectors.summingInt(HistoricalLogIndex.ToolInvocationEntry::invocationCount)));
        var series = chronologicalSessions.stream()
                .map(sessionId -> new SessionValue(
                        sessionId,
                        totalBySession.getOrDefault(sessionId, 0).doubleValue()))
                .toList();
        return trendForSeries(
                "overall.toolInvocationsPerSession",
                "overall",
                series,
                INVOCATION_ABSOLUTE_THRESHOLD,
                INVOCATION_RELATIVE_THRESHOLD,
                windowSize,
                (ignored, direction, earlier, later, magnitude, effectiveWindow) ->
                        "Overall tool invocations per session " + directionVerb(direction)
                                + " from " + formatDecimal(earlier)
                                + " to " + formatDecimal(later)
                                + " across the last " + effectiveWindow + " sessions."
        ).stream().toList();
    }

    private static OptionalTrend trendForSeries(String metric,
                                                String label,
                                                List<SessionValue> rawSeries,
                                                double absoluteThreshold,
                                                double relativeThreshold,
                                                int windowSize,
                                                TrendDescriptionBuilder descriptionBuilder) {
        var series = rawSeries.stream().distinct().toList();
        if (series.size() < MIN_SERIES_SUPPORT) {
            return OptionalTrend.empty();
        }

        int midpoint = series.size() / 2;
        var earlyValues = series.subList(0, Math.max(1, midpoint)).stream().map(SessionValue::value).toList();
        var lateValues = series.subList(Math.max(1, midpoint), series.size()).stream().map(SessionValue::value).toList();
        double earlier = average(earlyValues);
        double later = average(lateValues);
        TrendDirection direction = directionFor(earlier, later, absoluteThreshold, relativeThreshold);
        if (direction == TrendDirection.STABLE) {
            return OptionalTrend.empty();
        }
        double magnitude = percentChange(earlier, later);
        return OptionalTrend.of(new Trend(
                metric,
                direction,
                magnitude,
                windowSize,
                descriptionBuilder.build(label, direction, earlier, later, magnitude, series.size())));
    }

    private static List<SessionValuePair> perSessionErrorFrequency(List<HistoricalLogIndex.ErrorEntry> entries,
                                                                   List<String> chronologicalSessions) {
        var countsBySession = entries.stream()
                .collect(Collectors.groupingBy(
                        HistoricalLogIndex.ErrorEntry::sessionId,
                        LinkedHashMap::new,
                        Collectors.counting()));
        return chronologicalSessions.stream()
                .map(sessionId -> new SessionValuePair(
                        sessionId,
                        countsBySession.getOrDefault(sessionId, 0L).doubleValue()))
                .toList();
    }

    private static void persistTrends(AutoMemoryStore memoryStore, Path projectPath, List<Trend> trends) {
        for (var trend : trends) {
            var category = categoryFor(trend);
            if (category == null) {
                continue;
            }
            memoryStore.addIfAbsent(
                    category,
                    trend.description(),
                    tagsFor(trend),
                    "trend:" + trend.metric() + ":" + trend.direction().name().toLowerCase(Locale.ROOT),
                    false,
                    projectPath);
        }
    }

    private static MemoryEntry.Category categoryFor(Trend trend) {
        String metric = trend.metric();
        return switch (trend.direction()) {
            case RISING -> {
                if (metric.endsWith(".errorRate") || metric.endsWith(".frequency")) {
                    yield MemoryEntry.Category.ANTI_PATTERN;
                }
                yield MemoryEntry.Category.FAILURE_SIGNAL;
            }
            case FALLING -> MemoryEntry.Category.SUCCESSFUL_STRATEGY;
            case STABLE -> null;
        };
    }

    private static List<String> tagsFor(Trend trend) {
        var tags = new ArrayList<String>();
        tags.add("trend");
        tags.add(trend.direction().name().toLowerCase(Locale.ROOT));
        tags.add("historical-learning");
        if (trend.metric().startsWith("overall.")) {
            tags.add("overall");
        } else {
            int dot = trend.metric().indexOf('.');
            if (dot > 0) {
                tags.add(trend.metric().substring(0, dot));
            }
        }
        if (trend.metric().endsWith(".errorRate")) {
            tags.add("error-rate");
        } else if (trend.metric().endsWith(".avgDurationMs")) {
            tags.add("duration");
        } else if (trend.metric().endsWith(".frequency")) {
            tags.add("error-frequency");
        } else if (trend.metric().equals("overall.toolInvocationsPerSession")) {
            tags.add("efficiency");
        }
        return List.copyOf(tags);
    }

    private static TrendDirection directionFor(double earlier,
                                               double later,
                                               double absoluteThreshold,
                                               double relativeThreshold) {
        double delta = later - earlier;
        double threshold = Math.max(absoluteThreshold, Math.abs(earlier) * relativeThreshold);
        if (delta >= threshold) {
            return TrendDirection.RISING;
        }
        if (-delta >= threshold) {
            return TrendDirection.FALLING;
        }
        return TrendDirection.STABLE;
    }

    private static double average(List<Double> values) {
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

    private static double averageDurationMs(HistoricalLogIndex.ToolInvocationEntry entry) {
        if (entry.invocationCount() <= 0) {
            return 0.0;
        }
        return (double) entry.totalDurationMs() / entry.invocationCount();
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

    private static double percentChange(double earlier, double later) {
        double denominator = Math.max(0.0001, Math.abs(earlier));
        return ((later - earlier) / denominator) * 100.0;
    }

    private static String directionVerb(TrendDirection direction) {
        return switch (direction) {
            case RISING -> "rose";
            case FALLING -> "fell";
            case STABLE -> "held steady";
        };
    }

    private static String formatPercent(double value) {
        return String.format(Locale.ROOT, "%.0f%%", value * 100.0);
    }

    private static String formatMillis(double value) {
        return String.format(Locale.ROOT, "%.0fms", value);
    }

    private static String formatDecimal(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    public enum TrendDirection {
        RISING,
        FALLING,
        STABLE
    }

    public record Trend(
            String metric,
            TrendDirection direction,
            double magnitude,
            int windowSize,
            String description
    ) {
        public Trend {
            metric = Objects.requireNonNull(metric, "metric");
            direction = Objects.requireNonNull(direction, "direction");
            description = description != null ? description : "";
            if (windowSize < 1) {
                throw new IllegalArgumentException("windowSize must be >= 1");
            }
        }
    }

    private record SessionValue(String sessionId, double value) {}

    private record SessionValuePair(String sessionId, double value) {}

    private record SessionWindowData(
            List<String> recentSessions,
            List<String> chronologicalSessions,
            List<HistoricalLogIndex.ToolInvocationEntry> toolEntries,
            List<HistoricalLogIndex.ErrorEntry> errorEntries
    ) {
        private SessionWindowData {
            recentSessions = recentSessions != null ? List.copyOf(recentSessions) : List.of();
            chronologicalSessions = chronologicalSessions != null ? List.copyOf(chronologicalSessions) : List.of();
            toolEntries = toolEntries != null ? List.copyOf(toolEntries) : List.of();
            errorEntries = errorEntries != null ? List.copyOf(errorEntries) : List.of();
        }
    }

    @FunctionalInterface
    private interface TrendDescriptionBuilder {
        String build(String label, TrendDirection direction, double earlier, double later, double magnitude, int effectiveWindow);
    }

    private record OptionalTrend(Trend value) {
        static OptionalTrend of(Trend value) {
            return new OptionalTrend(value);
        }

        static OptionalTrend empty() {
            return new OptionalTrend(null);
        }

        java.util.stream.Stream<Trend> stream() {
            return value == null ? java.util.stream.Stream.empty() : java.util.stream.Stream.of(value);
        }
    }
}

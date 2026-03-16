package dev.aceclaw.daemon;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Generic section-based system prompt assembly with global budget enforcement.
 *
 * <p>Sections keep their insertion order in the final prompt, while truncation decisions
 * are driven by section priority so low-value context is removed first.
 */
public final class ContextAssemblyPlan {

    private final List<Section> sections = new ArrayList<>();

    public ContextAssemblyPlan addSection(String key, String content, int priority, boolean protectedSection) {
        sections.add(new Section(key, content, priority, protectedSection));
        return this;
    }

    public Result build(SystemPromptBudget budget) {
        Objects.requireNonNull(budget, "budget");

        var working = new ArrayList<SectionState>();
        for (var section : sections) {
            if (section.content() == null || section.content().isBlank()) {
                continue;
            }
            String content = section.content();
            int originalChars = content.length();
            boolean truncated = false;
            if (!section.protectedSection() && content.length() > budget.maxPerTierChars()) {
                content = TierTruncator.truncateContent(content, budget.maxPerTierChars());
                truncated = true;
            }
            working.add(new SectionState(section, originalChars, content, truncated));
        }

        enforceTotalBudget(working, budget.maxTotalChars(), false);
        enforceTotalBudget(working, budget.maxTotalChars(), true);

        var sb = new StringBuilder();
        var truncatedKeys = new ArrayList<String>();
        var sectionResults = new ArrayList<SectionResult>();
        for (var state : working) {
            boolean included = state.content() != null && !state.content().isBlank();
            if (!included || state.truncated()) {
                truncatedKeys.add(state.section().key());
            }
            if (included) {
                sb.append(state.content());
            }
            sectionResults.add(new SectionResult(
                    state.section().key(),
                    state.section().priority(),
                    state.section().protectedSection(),
                    state.originalChars(),
                    included ? state.content().length() : 0,
                    included,
                    state.truncated(),
                    included ? state.content() : ""));
        }
        return new Result(sb.toString(), List.copyOf(truncatedKeys), List.copyOf(sectionResults));
    }

    private static void enforceTotalBudget(List<SectionState> working, int maxTotalChars, boolean includeProtected) {
        int totalChars = totalChars(working);
        if (totalChars <= maxTotalChars) {
            return;
        }
        var truncatable = working.stream()
                .filter(state -> state.content() != null && (includeProtected || !state.section().protectedSection()))
                .sorted(Comparator
                        .comparing((SectionState state) -> state.section().protectedSection())
                        .thenComparingInt(state -> state.section().priority()))
                .toList();
        for (var state : truncatable) {
            totalChars = totalChars(working);
            if (totalChars <= maxTotalChars) {
                break;
            }
            int excess = totalChars - maxTotalChars;
            int currentLen = state.content().length();
            int targetLen = Math.max(0, currentLen - excess);
            if (targetLen == 0) {
                state.clear();
            } else if (targetLen < currentLen) {
                state.content(TierTruncator.truncateContent(state.content(), targetLen));
                state.truncated(true);
            }
        }
    }

    private static int totalChars(List<SectionState> sections) {
        int total = 0;
        for (var section : sections) {
            if (section.content() != null) {
                total += section.content().length();
            }
        }
        return total;
    }

    public record Section(String key, String content, int priority, boolean protectedSection) {
        public Section {
            Objects.requireNonNull(key, "key");
            content = content != null ? content : "";
        }
    }

    public record Result(String prompt, List<String> truncatedSectionKeys, List<SectionResult> sections) {
        public Result {
            Objects.requireNonNull(prompt, "prompt");
            truncatedSectionKeys = truncatedSectionKeys != null ? List.copyOf(truncatedSectionKeys) : List.of();
            sections = sections != null ? List.copyOf(sections) : List.of();
        }
    }

    public record SectionResult(
            String key,
            int priority,
            boolean protectedSection,
            int originalChars,
            int finalChars,
            boolean included,
            boolean truncated,
            String content
    ) {
        public SectionResult {
            Objects.requireNonNull(key, "key");
            content = content != null ? content : "";
        }
    }

    private static final class SectionState {
        private final Section section;
        private final int originalChars;
        private String content;
        private boolean truncated;

        private SectionState(Section section, int originalChars, String content, boolean truncated) {
            this.section = section;
            this.originalChars = Math.max(0, originalChars);
            this.content = content;
            this.truncated = truncated;
        }

        private Section section() {
            return section;
        }

        private String content() {
            return content;
        }

        private void content(String content) {
            this.content = content;
        }

        private boolean truncated() {
            return truncated;
        }

        private void truncated(boolean truncated) {
            this.truncated = truncated;
        }

        private void clear() {
            this.content = null;
            this.truncated = true;
        }

        private int originalChars() {
            return originalChars;
        }
    }
}

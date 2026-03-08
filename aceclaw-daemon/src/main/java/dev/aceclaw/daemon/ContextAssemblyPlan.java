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
            boolean truncated = false;
            if (!section.protectedSection() && content.length() > budget.maxPerTierChars()) {
                content = TierTruncator.truncateContent(content, budget.maxPerTierChars());
                truncated = true;
            }
            working.add(new SectionState(section, content, truncated));
        }

        int totalChars = totalChars(working);
        if (totalChars > budget.maxTotalChars()) {
            var truncatable = working.stream()
                    .filter(state -> !state.section().protectedSection())
                    .sorted(Comparator.comparingInt(state -> state.section().priority()))
                    .toList();
            for (var state : truncatable) {
                totalChars = totalChars(working);
                if (totalChars <= budget.maxTotalChars()) {
                    break;
                }
                int excess = totalChars - budget.maxTotalChars();
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

        var sb = new StringBuilder();
        var truncatedKeys = new ArrayList<String>();
        for (var state : working) {
            if (state.content() == null || state.content().isBlank()) {
                truncatedKeys.add(state.section().key());
                continue;
            }
            if (state.truncated()) {
                truncatedKeys.add(state.section().key());
            }
            sb.append(state.content());
        }
        return new Result(sb.toString(), List.copyOf(truncatedKeys));
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

    public record Result(String prompt, List<String> truncatedSectionKeys) {
        public Result {
            Objects.requireNonNull(prompt, "prompt");
            truncatedSectionKeys = truncatedSectionKeys != null ? List.copyOf(truncatedSectionKeys) : List.of();
        }
    }

    private static final class SectionState {
        private final Section section;
        private String content;
        private boolean truncated;

        private SectionState(Section section, String content, boolean truncated) {
            this.section = section;
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
    }
}

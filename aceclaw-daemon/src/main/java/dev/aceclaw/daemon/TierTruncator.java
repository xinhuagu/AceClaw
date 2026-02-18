package dev.aceclaw.daemon;

import dev.aceclaw.memory.MemoryTier;
import dev.aceclaw.memory.MemoryTierLoader.TierSection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Applies size budget constraints to memory tier sections.
 *
 * <p>Uses a two-pass approach:
 * <ol>
 *   <li>Enforce per-tier cap: any single tier exceeding {@code maxPerTierChars} is truncated</li>
 *   <li>Enforce total cap: if sum exceeds {@code maxTotalChars}, truncate from lowest priority first</li>
 * </ol>
 *
 * <p>Truncation uses a 70/20/10 split (head/tail/marker) inspired by OpenClaw,
 * preserving both core instructions at the start and recent additions at the end.
 *
 * <p>Soul (priority 100) and Managed Policy (priority 90) tiers are never truncated.
 */
public final class TierTruncator {

    private static final Logger log = LoggerFactory.getLogger(TierTruncator.class);

    /** Minimum priority that is exempt from truncation. */
    private static final int PROTECTED_MIN_PRIORITY = 90;

    private TierTruncator() {}

    /**
     * Applies budget constraints to tier sections, returning a new list with truncated content.
     *
     * @param sections the original tier sections (in priority order)
     * @param budget   the size budget
     * @return new list with content truncated as needed
     */
    public static List<TierSection> applyBudget(List<TierSection> sections, SystemPromptBudget budget) {
        // Pass 1: enforce per-tier cap
        var result = new ArrayList<TierSection>();
        for (var section : sections) {
            if (section.content() == null) {
                result.add(section);
                continue;
            }
            if (isProtected(section.tier())) {
                result.add(section);
                continue;
            }
            if (section.content().length() > budget.maxPerTierChars()) {
                log.info("Tier {} exceeds per-tier cap ({} > {} chars), truncating",
                        section.tier().displayName(), section.content().length(), budget.maxPerTierChars());
                result.add(new TierSection(section.tier(),
                        truncateContent(section.content(), budget.maxPerTierChars())));
            } else {
                result.add(section);
            }
        }

        // Pass 2: enforce total cap
        int total = totalChars(result);
        if (total <= budget.maxTotalChars()) {
            return result;
        }

        log.info("System prompt total ({} chars) exceeds budget ({} chars), truncating by priority",
                total, budget.maxTotalChars());

        // Sort truncatable tiers by priority ascending (lowest priority first)
        var truncatable = result.stream()
                .filter(s -> s.content() != null && !isProtected(s.tier()))
                .sorted(Comparator.comparingInt(s -> s.tier().priority()))
                .toList();

        for (var section : truncatable) {
            total = totalChars(result);
            if (total <= budget.maxTotalChars()) break;

            int excess = total - budget.maxTotalChars();
            int currentLen = section.content().length();
            int targetLen = Math.max(0, currentLen - excess);

            if (targetLen == 0) {
                // Remove this tier entirely
                log.info("Removing tier {} entirely to fit budget", section.tier().displayName());
                result.replaceAll(s -> s == section
                        ? new TierSection(section.tier(), null)
                        : s);
            } else {
                log.info("Truncating tier {} from {} to {} chars",
                        section.tier().displayName(), currentLen, targetLen);
                String truncated = truncateContent(section.content(), targetLen);
                result.replaceAll(s -> s == section
                        ? new TierSection(section.tier(), truncated)
                        : s);
            }
        }

        // Remove null-content sections (tiers removed entirely)
        result.removeIf(s -> s.content() == null
                && !(s.tier() instanceof MemoryTier.AutoMemory));

        return result;
    }

    /**
     * Truncates content using a 70/20/10 split: 70% head, 20% tail, 10% marker.
     *
     * @param content  the content to truncate
     * @param maxChars the maximum character count
     * @return the truncated content
     */
    static String truncateContent(String content, int maxChars) {
        if (content == null || content.length() <= maxChars) return content;
        if (maxChars <= 0) return "";

        int originalLen = content.length();
        int headChars = (int) (maxChars * 0.70);
        int tailChars = (int) (maxChars * 0.20);

        String marker = "\n\n<!-- [TRUNCATED] Original: " + originalLen
                + " chars, showing first " + headChars + " + last " + tailChars + " chars -->\n\n";

        // Ensure we don't exceed maxChars with the marker
        int markerLen = marker.length();
        int available = maxChars - markerLen;
        if (available <= 0) {
            return content.substring(0, maxChars);
        }

        headChars = Math.min(headChars, (int) (available * 0.78));
        tailChars = Math.min(tailChars, available - headChars);

        if (tailChars <= 0) {
            return content.substring(0, headChars) + marker;
        }

        return content.substring(0, headChars)
                + marker
                + content.substring(originalLen - tailChars);
    }

    private static boolean isProtected(MemoryTier tier) {
        return tier.priority() >= PROTECTED_MIN_PRIORITY;
    }

    private static int totalChars(List<TierSection> sections) {
        int total = 0;
        for (var s : sections) {
            if (s.content() != null) {
                total += s.content().length();
            }
        }
        return total;
    }
}

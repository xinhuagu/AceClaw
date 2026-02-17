package dev.aceclaw.memory;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryTierTest {

    @Test
    void allTiersHaveDisplayNames() {
        var tiers = allTiers();
        for (var tier : tiers) {
            assertThat(tier.displayName()).isNotNull().isNotEmpty();
        }
    }

    @Test
    void priorityOrder() {
        var tiers = allTiers();
        // Sort by priority descending
        var sorted = Arrays.stream(tiers)
                .sorted(Comparator.comparingInt(MemoryTier::priority).reversed())
                .toList();

        assertThat(sorted.get(0)).isInstanceOf(MemoryTier.Soul.class);
        assertThat(sorted.get(1)).isInstanceOf(MemoryTier.ManagedPolicy.class);
        assertThat(sorted.get(2)).isInstanceOf(MemoryTier.WorkspaceMemory.class);
        assertThat(sorted.get(3)).isInstanceOf(MemoryTier.UserMemory.class);
        assertThat(sorted.get(4)).isInstanceOf(MemoryTier.AutoMemory.class);
        assertThat(sorted.get(5)).isInstanceOf(MemoryTier.Journal.class);
    }

    @Test
    void sealedInterfaceCoversAllCases() {
        // Exhaustive pattern match (compiler-checked)
        for (var tier : allTiers()) {
            String name = switch (tier) {
                case MemoryTier.Soul s -> s.displayName();
                case MemoryTier.ManagedPolicy mp -> mp.displayName();
                case MemoryTier.WorkspaceMemory wm -> wm.displayName();
                case MemoryTier.UserMemory um -> um.displayName();
                case MemoryTier.AutoMemory am -> am.displayName();
                case MemoryTier.Journal j -> j.displayName();
            };
            assertThat(name).isNotEmpty();
        }
    }

    @Test
    void uniquePriorities() {
        var tiers = allTiers();
        var priorities = Arrays.stream(tiers).map(MemoryTier::priority).toList();
        assertThat(priorities).doesNotHaveDuplicates();
    }

    private MemoryTier[] allTiers() {
        return new MemoryTier[]{
                new MemoryTier.Soul(),
                new MemoryTier.ManagedPolicy(),
                new MemoryTier.WorkspaceMemory(),
                new MemoryTier.UserMemory(),
                new MemoryTier.AutoMemory(),
                new MemoryTier.Journal()
        };
    }
}

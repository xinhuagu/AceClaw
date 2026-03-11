package dev.aceclaw.daemon;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory ring buffer for skill draft lifecycle events, exposed via polling RPC.
 */
public final class SkillDraftEventFeed {

    private static final int DEFAULT_MAX_EVENTS = 256;

    private final int maxEvents;
    private final AtomicLong sequence = new AtomicLong(0);
    private final Object lock = new Object();
    private final Deque<Entry> entries = new ArrayDeque<>();

    public SkillDraftEventFeed() {
        this(DEFAULT_MAX_EVENTS);
    }

    public SkillDraftEventFeed(int maxEvents) {
        if (maxEvents <= 0) {
            throw new IllegalArgumentException("maxEvents must be > 0, got: " + maxEvents);
        }
        this.maxEvents = maxEvents;
    }

    public void append(SkillDraftEvent event) {
        Objects.requireNonNull(event, "event");
        synchronized (lock) {
            long seq = sequence.incrementAndGet();
            entries.addLast(new Entry(seq, event));
            while (entries.size() > maxEvents) {
                entries.removeFirst();
            }
        }
    }

    public PollResult poll(long afterSeq, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<Entry> out = new ArrayList<>(safeLimit);
        long nextSeq = sequence.get();
        synchronized (lock) {
            for (Entry entry : entries) {
                if (entry.sequence() <= afterSeq) {
                    continue;
                }
                out.add(entry);
                if (out.size() >= safeLimit) {
                    break;
                }
            }
            if (!entries.isEmpty()) {
                nextSeq = entries.getLast().sequence();
            }
        }
        return new PollResult(nextSeq, out);
    }

    public record Entry(long sequence, SkillDraftEvent event) {}

    public record PollResult(long nextSequence, List<Entry> entries) {
        public PollResult {
            entries = entries != null ? List.copyOf(entries) : List.of();
        }
    }
}

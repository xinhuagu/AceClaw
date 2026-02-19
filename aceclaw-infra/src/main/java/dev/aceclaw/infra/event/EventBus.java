package dev.aceclaw.infra.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Type-safe, asynchronous event bus for internal AceClaw communication.
 *
 * <p>Each subscriber gets a dedicated {@link BlockingQueue} drained by a virtual
 * thread, providing natural backpressure without reactive frameworks. Subscribers
 * are matched by event type using {@code instanceof}.
 *
 * <p>Thread-safe: publishers can call {@link #publish(AceClawEvent)} from any thread.
 *
 * <p>Usage:
 * <pre>{@code
 * var bus = new EventBus();
 * bus.start();
 *
 * // Subscribe to specific event types
 * bus.subscribe(AgentEvent.class, event -> {
 *     switch (event) {
 *         case AgentEvent.TurnStarted ts -> log.info("Turn {} started", ts.turnNumber());
 *         case AgentEvent.TurnCompleted tc -> log.info("Turn {} done in {}ms", tc.turnNumber(), tc.durationMs());
 *         default -> {}
 *     }
 * });
 *
 * // Publish from anywhere
 * bus.publish(new AgentEvent.TurnStarted("sess1", 1, Instant.now()));
 *
 * bus.stop();
 * }</pre>
 */
public final class EventBus {

    private static final Logger log = LoggerFactory.getLogger(EventBus.class);

    /** Maximum queued events per subscriber before oldest are dropped. */
    private static final int DEFAULT_QUEUE_CAPACITY = 1024;

    private final List<Subscription<?>> subscriptions = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final int queueCapacity;

    public EventBus() {
        this(DEFAULT_QUEUE_CAPACITY);
    }

    public EventBus(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    /**
     * Starts the event bus. Must be called before publishing or subscribing.
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("Event bus started (queue capacity: {})", queueCapacity);
        }
    }

    /**
     * Stops the event bus and all subscriber threads.
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            for (var sub : subscriptions) {
                sub.stop();
            }
            subscriptions.clear();
            log.info("Event bus stopped");
        }
    }

    /**
     * Returns true if the event bus is running.
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Subscribes to events of the given type (including subtypes).
     *
     * @param eventType the event class to listen for
     * @param handler   callback invoked on a dedicated virtual thread
     * @param <T>       the event type
     * @return a handle that can be used to unsubscribe
     */
    public <T extends AceClawEvent> Subscription<T> subscribe(Class<T> eventType, EventHandler<T> handler) {
        var subscription = new Subscription<>(eventType, handler, queueCapacity, running);
        subscriptions.add(subscription);

        if (running.get()) {
            subscription.start();
        }

        log.debug("Subscribed to {} (total subscribers: {})", eventType.getSimpleName(), subscriptions.size());
        return subscription;
    }

    /**
     * Publishes an event to all matching subscribers.
     *
     * <p>Non-blocking: events are enqueued to each subscriber's queue.
     * If a subscriber's queue is full, the event is dropped with a warning.
     *
     * @param event the event to publish
     */
    public void publish(AceClawEvent event) {
        if (!running.get()) {
            log.warn("Event bus not running, dropping event: {}", event.getClass().getSimpleName());
            return;
        }

        for (var sub : subscriptions) {
            sub.offer(event);
        }
    }

    /**
     * Returns the number of active subscriptions.
     */
    public int subscriberCount() {
        return subscriptions.size();
    }

    // -- Subscription handle ------------------------------------------------

    /**
     * A subscription handle that manages the subscriber's queue and drain thread.
     *
     * @param <T> the event type this subscription listens for
     */
    public static final class Subscription<T extends AceClawEvent> {

        private final Class<T> eventType;
        private final EventHandler<T> handler;
        private final BlockingQueue<AceClawEvent> queue;
        private final AtomicBoolean busRunning;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private volatile Thread drainThread;

        Subscription(Class<T> eventType, EventHandler<T> handler, int capacity, AtomicBoolean busRunning) {
            this.eventType = eventType;
            this.handler = handler;
            this.queue = new LinkedBlockingQueue<>(capacity);
            this.busRunning = busRunning;
        }

        void start() {
            drainThread = Thread.ofVirtual()
                    .name("eventbus-" + eventType.getSimpleName().toLowerCase())
                    .start(this::drainLoop);
        }

        void stop() {
            active.set(false);
            if (drainThread != null) {
                drainThread.interrupt();
            }
        }

        @SuppressWarnings("unchecked")
        void offer(AceClawEvent event) {
            if (!active.get()) return;
            if (!eventType.isInstance(event)) return;

            if (!queue.offer(event)) {
                LoggerFactory.getLogger(EventBus.class)
                        .warn("Subscriber queue full for {}, dropping event", eventType.getSimpleName());
            }
        }

        /**
         * Returns the number of events pending in this subscriber's queue.
         */
        public int pendingCount() {
            return queue.size();
        }

        @SuppressWarnings("unchecked")
        private void drainLoop() {
            while (active.get() && busRunning.get()) {
                try {
                    var event = queue.take();
                    if (eventType.isInstance(event)) {
                        handler.handle((T) event);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    LoggerFactory.getLogger(EventBus.class)
                            .error("Subscriber error for {}: {}", eventType.getSimpleName(), e.getMessage(), e);
                }
            }
        }
    }
}

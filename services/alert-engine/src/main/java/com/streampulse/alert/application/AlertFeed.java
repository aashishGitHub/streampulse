/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.application;

import com.streampulse.alert.domain.Alert;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Fan-out of alert changes to live subscribers, with a bounded replay buffer.
 *
 * <h2>Ordering and resume</h2>
 *
 * Sequence numbers, the replay buffer and every subscriber queue are touched only under one lock.
 * {@link #subscribe} copies the buffered events after the client's last id into the subscriber's
 * queue and registers it for live events inside that same critical section, so no event can fall
 * between "replayed" and "live" and none can be seen twice. The sequence is per instance: it
 * restarts at 1 with the process, so an id that goes backwards means "re-read the full list".
 *
 * <h2>Slow subscribers</h2>
 *
 * Publishing runs on the Kafka consumer thread and must never block on a client. Each subscriber
 * has a bounded queue; when it is full that subscriber is closed (and the stream ends, so the
 * client reconnects with its last id) while everyone else carries on.
 *
 * <p>Delivery here never throws, so this channel cannot hold back the aggregate acknowledgement.
 */
public final class AlertFeed implements AlertChannel {

    public record Entry(long sequence, Alert alert) {}

    private final int replayCapacity;
    private final int liveSlack;
    private final ReentrantLock lock = new ReentrantLock();
    private final Deque<Entry> buffer = new ArrayDeque<>();
    private final List<Subscription> subscribers = new CopyOnWriteArrayList<>();
    private long sequence;

    /**
     * @param replayCapacity how many recent events (all tenants) can be replayed
     * @param liveSlack how many live events a subscriber may fall behind by; its queue also has to
     *     hold a full replay, so the queue size is the sum
     */
    public AlertFeed(int replayCapacity, int liveSlack) {
        if (replayCapacity < 1 || liveSlack < 1) {
            throw new IllegalArgumentException("capacities must be positive");
        }
        this.replayCapacity = replayCapacity;
        this.liveSlack = liveSlack;
    }

    @Override
    public String name() {
        return "sse";
    }

    @Override
    public void deliver(Alert alert) {
        publish(alert);
    }

    /** @return the sequence number assigned */
    public long publish(Alert alert) {
        lock.lock();
        try {
            Entry entry = new Entry(++sequence, alert);
            buffer.addLast(entry);
            if (buffer.size() > replayCapacity) {
                buffer.removeFirst();
            }
            for (Subscription subscription : subscribers) {
                if (subscription.tenantId.equals(alert.tenantId()) && !subscription.queue.offer(entry)) {
                    subscription.overflowed = true;
                    subscribers.remove(subscription);
                }
            }
            return entry.sequence();
        } finally {
            lock.unlock();
        }
    }

    public Subscription subscribe(String tenantId, OptionalLong lastEventId) {
        lock.lock();
        try {
            Subscription subscription = new Subscription(tenantId, replayCapacity + liveSlack);
            if (lastEventId.isPresent()) {
                for (Entry entry : buffer) {
                    if (entry.alert().tenantId().equals(tenantId) && entry.sequence() > lastEventId.getAsLong()) {
                        subscription.queue.add(entry);
                    }
                }
            }
            subscribers.add(subscription);
            return subscription;
        } finally {
            lock.unlock();
        }
    }

    /** Ends every open stream; used at shutdown, which would otherwise wait for streams that never end. */
    public void closeAll() {
        for (Subscription subscription : new ArrayList<>(subscribers)) {
            subscription.close();
        }
    }

    public int subscriberCount() {
        return subscribers.size();
    }

    public final class Subscription implements AutoCloseable {

        private static final Entry WAKE = new Entry(-1, null);

        private final String tenantId;
        private final BlockingQueue<Entry> queue;
        private volatile boolean overflowed;
        private volatile boolean closed;

        private Subscription(String tenantId, int capacity) {
            this.tenantId = tenantId;
            this.queue = new ArrayBlockingQueue<>(capacity);
        }

        /**
         * Waits for the next event.
         *
         * @return the entry, or null when nothing arrived in time ({@link #isEnded()} false: send a
         *     heartbeat) or the subscription ended ({@link #isEnded()} true)
         */
        public Entry next(Duration wait) throws InterruptedException {
            if (isEnded()) {
                return null;
            }
            Entry entry = queue.poll(wait.toNanos(), TimeUnit.NANOSECONDS);
            return entry == null || entry == WAKE || isEnded() ? null : entry;
        }

        /** True once closed or overflowed. After an overflow, unsent events are replayable by id. */
        public boolean isEnded() {
            return closed || overflowed;
        }

        public boolean overflowed() {
            return overflowed;
        }

        @Override
        public void close() {
            closed = true;
            lock.lock();
            try {
                subscribers.remove(this);
            } finally {
                lock.unlock();
            }
            queue.offer(WAKE);
        }
    }
}

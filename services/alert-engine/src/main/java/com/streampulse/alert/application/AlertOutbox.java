/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.application;

import com.streampulse.alert.domain.Alert;
import com.streampulse.alert.domain.AlertMetrics;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Changes the engine has applied but not every channel has accepted yet.
 *
 * <p>The engine updates state first, so a failed delivery cannot simply be retried by re-evaluating:
 * the redelivered aggregate would be recognised as a duplicate and the alert would be lost. The
 * outbox is where "applied but not delivered" lives. Each entry records which channels still owe it;
 * a channel is retried alone and never sees an alert twice after it has accepted it, and one slow
 * channel does not hold back the others. Per channel, alerts leave in the order they were added.
 *
 * <p>In memory, like the state it protects: a restart loses both together.
 */
public final class AlertOutbox {

    private static final class Entry {
        final Alert alert;
        final Set<String> owing;

        Entry(Alert alert, Set<String> owing) {
            this.alert = alert;
            this.owing = owing;
        }
    }

    private final List<AlertChannel> channels;
    private final AlertMetrics metrics;
    private final ReentrantLock lock = new ReentrantLock();
    private final Deque<Entry> pending = new ArrayDeque<>();

    public AlertOutbox(List<AlertChannel> channels, AlertMetrics metrics) {
        this.channels = List.copyOf(channels);
        this.metrics = metrics;
    }

    public void add(List<Alert> alerts) {
        lock.lock();
        try {
            for (Alert alert : alerts) {
                Set<String> owing = new HashSet<>();
                channels.forEach(c -> owing.add(c.name()));
                pending.add(new Entry(alert, owing));
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Delivers what is owed. A channel that fails stops at its first failure (so it never reorders)
     * while the others carry on; afterwards the failure is rethrown with the other channels' failures
     * attached as suppressed.
     *
     * @throws DeliveryException if any channel still owes something
     */
    public void drain() {
        lock.lock();
        try {
            DeliveryException failure = null;
            for (AlertChannel channel : channels) {
                try {
                    drainChannel(channel);
                } catch (DeliveryException e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
            pending.removeIf(entry -> entry.owing.isEmpty());
            if (failure != null) {
                throw failure;
            }
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return pending.size();
        } finally {
            lock.unlock();
        }
    }

    private void drainChannel(AlertChannel channel) {
        for (Entry entry : new ArrayList<>(pending)) {
            if (!entry.owing.contains(channel.name())) {
                continue;
            }
            try {
                channel.deliver(entry.alert);
            } catch (RuntimeException e) {
                metrics.deliveryFailed(channel.name());
                throw e instanceof DeliveryException de
                        ? de
                        : new DeliveryException("channel " + channel.name() + " failed", e);
            }
            entry.owing.remove(channel.name());
            metrics.delivered(channel.name());
        }
    }
}

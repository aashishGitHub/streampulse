/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.loadgen;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;

public final class LatencyRecorder {

    private final AtomicLongArray samples;
    private final AtomicInteger next = new AtomicInteger();

    public LatencyRecorder(int capacity) {
        this.samples = new AtomicLongArray(capacity);
    }

    public void record(long nanos) {
        int slot = next.getAndIncrement();
        if (slot < samples.length()) {
            samples.set(slot, nanos);
        }
    }

    public long dropped() {
        return Math.max(0, next.get() - samples.length());
    }

    /** Call after the writers finish: a slot is claimed before it is filled, so a live read can see a zero. */
    public long[] sortedSnapshot() {
        int size = Math.min(next.get(), samples.length());
        long[] copy = new long[size];
        for (int i = 0; i < size; i++) {
            copy[i] = samples.get(i);
        }
        Arrays.sort(copy);
        return copy;
    }
}

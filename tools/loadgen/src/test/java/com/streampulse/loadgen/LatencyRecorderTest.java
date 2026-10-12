/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.loadgen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class LatencyRecorderTest {

    @Test
    void snapshotIsSortedAndContainsOnlyRecordedValues() {
        LatencyRecorder recorder = new LatencyRecorder(10);
        recorder.record(30);
        recorder.record(10);
        recorder.record(20);

        assertThat(recorder.sortedSnapshot()).containsExactly(10, 20, 30);
    }

    @Test
    void valuesBeyondCapacityAreCountedNotStored() {
        LatencyRecorder recorder = new LatencyRecorder(2);
        recorder.record(1);
        recorder.record(2);
        recorder.record(3);

        assertThat(recorder.sortedSnapshot()).containsExactly(1, 2);
        assertThat(recorder.dropped()).isEqualTo(1);
    }

    @Test
    void safeUnderConcurrentWritersOnVirtualThreads() {
        int writers = 2_000;
        LatencyRecorder recorder = new LatencyRecorder(writers);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            IntStream.range(0, writers).forEach(i -> executor.submit(() -> recorder.record(i)));
        }

        long[] snapshot = recorder.sortedSnapshot();
        assertThat(snapshot).hasSize(writers);
        assertThat(snapshot).isSorted();
        assertThat(snapshot[0]).isZero();
        assertThat(snapshot[writers - 1]).isEqualTo(writers - 1);
    }
}

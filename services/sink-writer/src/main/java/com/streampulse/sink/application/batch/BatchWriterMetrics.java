/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.sink.application.batch;

import java.time.Duration;
import java.util.function.IntSupplier;

public interface BatchWriterMetrics {

    BatchWriterMetrics NONE = new BatchWriterMetrics() {
        @Override
        public void bindQueueDepth(IntSupplier queueDepth) {}

        @Override
        public void flushed(int records, Duration latency) {}

        @Override
        public void flushFailed() {}

        @Override
        public void deadLettered() {}
    };

    /** Called once at start; the supplier is polled by the metrics backend, never by the writer. */
    void bindQueueDepth(IntSupplier queueDepth);

    /** One successful write to the sink, whole batch or a single isolated record. */
    void flushed(int records, Duration latency);

    void flushFailed();

    void deadLettered();
}

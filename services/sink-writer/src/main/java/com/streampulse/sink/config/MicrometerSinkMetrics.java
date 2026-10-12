/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.sink.config;

import com.streampulse.sink.application.SinkMetrics;
import com.streampulse.sink.application.batch.BatchWriterMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.function.IntSupplier;

/** Names are fixed by hot-store-contract.md; dashboards and alerts are written against them. */
public class MicrometerSinkMetrics implements BatchWriterMetrics, SinkMetrics {

    private final MeterRegistry registry;
    private final Timer freshness;
    private final Timer flush;
    private final Counter flushFailures;
    private final Counter deadLetters;
    private final Counter projectionFailures;
    private volatile IntSupplier queueDepth;

    public MicrometerSinkMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.freshness = Timer.builder("streampulse.sink.freshness")
                .description("Window end to durable write")
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofSeconds(1))
                .maximumExpectedValue(Duration.ofMinutes(10))
                .register(registry);
        this.flush = Timer.builder("streampulse.sink.batch.flush")
                .description("One successful write to the durable store")
                .register(registry);
        this.flushFailures = Counter.builder("streampulse.sink.batch.flush.failures")
                .description("Failed writes to the durable store, whole batch or single record")
                .register(registry);
        this.deadLetters = Counter.builder("streampulse.sink.dead.letters")
                .description("Records parked on the dead-letter topic")
                .baseUnit("records")
                .register(registry);
        this.projectionFailures = Counter.builder("streampulse.sink.projection.failures")
                .description("Batches whose Redis projection failed after Postgres succeeded")
                .register(registry);
    }

    @Override
    public void bindQueueDepth(IntSupplier depth) {
        // Held in a field AND by the gauge's supplier: a gauge over a bare method reference can be
        // garbage-collected and then reports NaN forever.
        this.queueDepth = depth;
        Gauge.builder("streampulse.sink.batch.queue.depth", () -> queueDepth.getAsInt())
                .description("Records waiting in the batch writer's buffer")
                .baseUnit("records")
                .register(registry);
    }

    @Override
    public void flushed(int records, Duration latency) {
        flush.record(latency);
    }

    @Override
    public void flushFailed() {
        flushFailures.increment();
    }

    @Override
    public void deadLettered() {
        deadLetters.increment();
    }

    @Override
    public void freshness(Duration lag) {
        freshness.record(lag);
    }

    @Override
    public void projectionFailed() {
        projectionFailures.increment();
    }
}

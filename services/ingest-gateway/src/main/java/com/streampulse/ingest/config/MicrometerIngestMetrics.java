/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.config;

import com.streampulse.ingest.application.IngestMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Micrometer implementation of the metrics port.
 *
 * <h2>Cardinality discipline</h2>
 *
 * Every distinct combination of tag values is a separate time series. Tagging by
 * {@code eventId} or {@code sessionId} would create millions and take Prometheus down —
 * the single most common observability mistake, and one that is far easier to prevent here
 * than to clean up later.
 *
 * <p>So these counters carry <b>no</b> dynamic tags. Tenant would be acceptable (there are
 * three) and Phase 8 may add it deliberately; an id never is.
 *
 * <h2>Why counters are created once</h2>
 *
 * {@code Counter.builder(...).register(registry)} does a registry lookup. Doing it per call
 * in the hot path is wasted work, so they are resolved in the constructor and the
 * {@code increment} calls become field accesses.
 */
public class MicrometerIngestMetrics implements IngestMetrics {

    private final Counter published;
    private final Counter duplicates;
    private final Counter rejectedForCapacity;
    private final Counter idempotencyUnavailable;
    private final Counter publishFailures;

    public MicrometerIngestMetrics(MeterRegistry registry) {
        this.published = Counter.builder("streampulse.ingest.events.published")
                .description("Events accepted and published to Kafka")
                .baseUnit("events")
                .register(registry);

        this.duplicates = Counter.builder("streampulse.ingest.events.duplicates")
                .description("Events recognised as already seen. Healthy — it means a client retried")
                .baseUnit("events")
                .register(registry);

        this.rejectedForCapacity = Counter.builder("streampulse.ingest.events.rejected")
                .description("Events refused because the gateway was saturated (backpressure)")
                .baseUnit("events")
                .register(registry);

        // The SLI for BS-04's degraded-dependency scenario: when this rises, deduplication
        // is off and the pipeline is running at-least-once without protection.
        this.idempotencyUnavailable = Counter.builder("streampulse.ingest.idempotency.unavailable")
                .description("Times the dedup store was unreachable and we failed open")
                .register(registry);

        this.publishFailures = Counter.builder("streampulse.ingest.publish.failures")
                .description("Batches that failed to reach Kafka")
                .register(registry);
    }

    @Override
    public void recordPublished(int count) {
        published.increment(count);
    }

    @Override
    public void recordDuplicates(int count) {
        duplicates.increment(count);
    }

    @Override
    public void recordRejectedForCapacity(int count) {
        rejectedForCapacity.increment(count);
    }

    @Override
    public void recordIdempotencyUnavailable() {
        idempotencyUnavailable.increment();
    }

    @Override
    public void recordPublishFailure() {
        publishFailures.increment();
    }
}

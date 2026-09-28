/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.application;

/**
 * Port for the counters the use case emits.
 *
 * <h2>Why an interface rather than injecting a MeterRegistry</h2>
 *
 * It keeps Micrometer out of the application layer, so {@link IngestService} stays testable
 * with a no-op implementation and no metrics infrastructure. It also makes the metric names
 * a deliberate, reviewable list in one place, rather than string literals scattered through
 * business logic where they quietly drift.
 *
 * <h2>Cardinality warning</h2>
 *
 * Implementations must NOT tag by {@code eventId}, {@code sessionId} or anything else with
 * unbounded cardinality. Every distinct tag combination is a separate time series; tagging by
 * session id would create millions and take Prometheus down. Tenant is fine — there are
 * three of them. This is the classic observability mistake, and it is easier to prevent here
 * than to fix later.
 */
public interface IngestMetrics {

    void recordPublished(int count);

    void recordDuplicates(int count);

    void recordRejectedForCapacity(int count);

    /** Rejected because the TENANT exceeded its quota — distinct from our own saturation. */
    void recordRejectedForQuota(int count);

    void recordIdempotencyUnavailable();

    void recordPublishFailure();

    /** No-op, for unit tests that do not care about metrics. */
    static IngestMetrics noop() {
        return new IngestMetrics() {
            @Override
            public void recordPublished(int count) {}

            @Override
            public void recordDuplicates(int count) {}

            @Override
            public void recordRejectedForCapacity(int count) {}

            @Override
            public void recordRejectedForQuota(int count) {}

            @Override
            public void recordIdempotencyUnavailable() {}

            @Override
            public void recordPublishFailure() {}
        };
    }
}

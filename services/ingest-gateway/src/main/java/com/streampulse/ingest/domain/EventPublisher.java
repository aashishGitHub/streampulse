/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.domain;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Outbound port: somewhere to publish events.
 *
 * <h2>Why this interface exists</h2>
 *
 * The domain declares what it needs; infrastructure implements it. That inversion is what
 * makes Phase 10's Kinesis adapter a new class rather than a rewrite — the Strategy pattern,
 * selected by Spring profile.
 *
 * <p>Note what the interface does <b>not</b> mention: Kafka, topics, partitions, serializers.
 * If any of those appeared here, the abstraction would be a Kafka client with extra steps.
 *
 * <h2>Why it returns a CompletableFuture</h2>
 *
 * Publishing is asynchronous and the caller needs to know when the broker has acknowledged,
 * because {@code 202 Accepted} must not be returned before the data is durable (REQ-IG-17).
 * Returning a future lets the use case decide how to wait, and lets several batches be
 * in flight at once.
 */
public interface EventPublisher {

    /**
     * Publishes every event, keyed by {@link IngestCommand.IngestEvent#partitionKey()}.
     *
     * @return completes when the broker has acknowledged ALL records, or completes
     *     exceptionally if any failed. Partial success is not represented, because the caller
     *     cannot act on it: the batch is retried as a whole and idempotency makes that safe.
     */
    CompletableFuture<Void> publishAll(TenantScopedEvents events);

    /** A batch bound to its tenant, so an adapter cannot accidentally publish untagged data. */
    record TenantScopedEvents(String tenantId, List<IngestCommand.IngestEvent> events) {}
}

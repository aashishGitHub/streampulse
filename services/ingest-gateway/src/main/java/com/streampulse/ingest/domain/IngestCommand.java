/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.domain;

import com.streampulse.platform.domain.EventId;
import com.streampulse.platform.domain.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A validated batch, ready to publish.
 *
 * <h2>Why a domain type and not the HTTP request object</h2>
 *
 * The request DTO is shaped by JSON and by what clients happen to send. This type is shaped
 * by what the business rules need. Keeping them separate means:
 *
 * <ul>
 *   <li>the JSON wire format can change without touching business logic;
 *   <li>the gRPC adapter (BS-09) maps to this <em>same</em> type, so both protocols share
 *       one code path — proven by {@code ProtocolParityTest};
 *   <li>the domain has no Jackson or Jakarta annotations, so it is unit-testable with
 *       plain {@code new}. Enforced by ArchUnit.
 * </ul>
 *
 * <h2>Why tenantId is a constructor argument, not a field of the event</h2>
 *
 * It comes from the verified JWT, never from the request body (REQ-IG-08). Accepting it from
 * the body would let any authenticated caller write into another tenant — the worst failure
 * a multi-tenant system can have. Making it a separate parameter means the wire format
 * cannot supply it even by accident.
 */
public record IngestCommand(TenantId tenantId, List<IngestEvent> events, Instant receivedAt) {

    public IngestCommand {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(receivedAt, "receivedAt");
        if (events.isEmpty()) {
            throw new IllegalArgumentException("a batch must contain at least one event");
        }
        // Defensive copy: the caller must not be able to mutate the batch after we have
        // validated it. `List.copyOf` also rejects nulls, which is a free extra check.
        events = List.copyOf(events);
    }

    public int size() {
        return events.size();
    }

    /** One event inside a batch, after validation and mapping. */
    public record IngestEvent(
            EventId eventId,
            String sessionId,
            String titleId,
            String deviceType,
            String region,
            Instant occurredAt,
            String type,
            Object payload) {

        public IngestEvent {
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(occurredAt, "occurredAt");
            Objects.requireNonNull(type, "type");
        }

        /**
         * The Kafka partition key.
         *
         * <p>{@code sessionId}, because all events for one viewing session must land on one
         * partition — that is the only ordering Kafka guarantees, and stream processing needs
         * {@code session_start → heartbeat → session_end} in order.
         *
         * <p>The cost we accept: aggregating per <em>title</em> then needs a repartition.
         * Ordering is a correctness property; repartitioning is a performance cost.
         */
        public String partitionKey() {
            return sessionId;
        }
    }
}

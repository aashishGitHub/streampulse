/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.adapter.out.kafka;

import com.streampulse.contracts.v1.DeviceType;
import com.streampulse.contracts.v1.EventType;
import com.streampulse.contracts.v1.TelemetryEvent;
import com.streampulse.ingest.domain.IngestCommand;
import java.time.Instant;

/**
 * Maps the domain event to the Avro wire type.
 *
 * <h2>Why a mapper exists at all</h2>
 *
 * The domain type is shaped by business rules; the Avro type is shaped by the wire contract.
 * Keeping them separate means the schema can evolve — a new optional field, a renamed
 * payload variant — without the domain noticing, and the domain stays free of generated code.
 *
 * <p>It also means the gRPC adapter (BS-09) maps <em>its</em> protobuf into the same domain
 * type, and this single mapper produces the Kafka record. That is what makes
 * {@code ProtocolParityTest} meaningful: both protocols converge before anything is published.
 */
public class AvroEventMapper {

    public TelemetryEvent toAvro(String tenantId, IngestCommand.IngestEvent event) {
        return TelemetryEvent.newBuilder()
                .setEventId(event.eventId().value())
                // tenantId comes from the VERIFIED JWT, never from the request body (REQ-IG-08).
                .setTenantId(tenantId)
                .setSessionId(event.sessionId())
                .setTitleId(event.titleId())
                .setDeviceType(parseDeviceType(event.deviceType()))
                .setRegion(event.region() == null ? "unknown" : event.region())
                .setOccurredAt(event.occurredAt())
                // receivedAt is stamped HERE, by us. A client-supplied ingest time would be
                // unverifiable, and the gap between the two is how Phase 3 measures lateness.
                .setReceivedAt(Instant.now())
                .setType(parseEventType(event.type()))
                .setPayload(null) // payload variants land in Phase 3, with the consumer
                .build();
    }

    /**
     * Unknown symbols degrade to the UNKNOWN value rather than throwing.
     *
     * <p>A newer client sending a device type this build has never heard of must not fail the
     * whole batch. The schema's enum {@code default} provides the same protection on the read
     * side; this is the write side of the same principle.
     */
    private DeviceType parseDeviceType(String raw) {
        if (raw == null) {
            return DeviceType.DEVICE_UNKNOWN;
        }
        try {
            return DeviceType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return DeviceType.DEVICE_UNKNOWN;
        }
    }

    private EventType parseEventType(String raw) {
        if (raw == null) {
            return EventType.EVENT_UNKNOWN;
        }
        try {
            return EventType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return EventType.EVENT_UNKNOWN;
        }
    }
}

/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.adapter.in.rest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * Wire DTOs for the REST adapter.
 *
 * <h2>Why these are separate from the domain types</h2>
 *
 * These are shaped by JSON and by Bean Validation. The domain types are shaped by business
 * rules. Keeping them apart means the JSON contract can change without touching logic, the
 * domain carries no Jakarta annotations (enforced by ArchUnit), and the gRPC adapter maps
 * its own wire type into the same domain type.
 *
 * <h2>Note what is absent: tenantId</h2>
 *
 * There is deliberately no tenant field. It comes from the verified JWT (REQ-IG-08).
 * Accepting it from the body would let any authenticated caller write into another tenant —
 * the worst failure a multi-tenant system can have. The safest way to not trust a field is
 * to not have one.
 */
final class IngestDtos {

    private IngestDtos() {}

    /** Request body for {@code POST /v1/events:batch}. */
    record EventBatchRequest(
            @NotEmpty(message = "a batch must contain at least one event")
                    @Size(max = 500, message = "a batch may contain at most 500 events")
                    @Valid
                    List<EventDto> events) {}

    /**
     * One event.
     *
     * <p>{@code @Valid} on the list above is what makes these nested constraints run.
     * Without it, Bean Validation checks the list itself and never descends into the
     * elements — a genuinely common bug that lets invalid events straight through.
     */
    record EventDto(
            @NotBlank(message = "eventId is required and is the idempotency key") String eventId,
            @NotBlank(message = "sessionId is required; it is the Kafka partition key") String sessionId,
            String titleId,
            String deviceType,
            String region,
            @NotNull(message = "occurredAt is required") Instant occurredAt,
            @NotBlank(message = "type is required") String type,
            Object payload) {}

    /**
     * Response for an accepted batch.
     *
     * @param duplicates how many were already seen. A healthy number, not an error — it means
     *     a client retried and idempotency did its job.
     */
    record BatchAcceptedResponse(int accepted, int duplicates, String batchId, boolean degraded) {}
}

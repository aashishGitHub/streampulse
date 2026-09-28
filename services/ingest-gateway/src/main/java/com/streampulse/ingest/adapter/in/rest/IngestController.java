/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.adapter.in.rest;

import com.streampulse.ingest.application.IngestUseCase;
import com.streampulse.ingest.domain.IngestCommand;
import com.streampulse.ingest.domain.IngestResult;
import com.streampulse.platform.domain.EventId;
import com.streampulse.platform.domain.TenantId;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The HTTP front door.
 *
 * <h2>How thin this class is, is the point</h2>
 *
 * It does exactly three things: map the wire format to the domain, call the use case, map
 * the result back. No validation logic (Bean Validation), no idempotency (the use case), no
 * Kafka (the adapter). That is what lets the gRPC adapter in BS-09 be a genuinely new front
 * end rather than a second copy of the rules — and what makes {@code ProtocolParityTest}
 * able to assert both produce identical Kafka records.
 *
 * <p>If business logic ever appears here, the parity test is the thing that catches it.
 */
@RestController
class IngestController {

    private static final Logger log = LoggerFactory.getLogger(IngestController.class);

    private final IngestUseCase ingest;
    private final TenantResolver tenantResolver;

    // Constructor injection, no @Autowired: a single constructor is autowired implicitly,
    // the field is final, and the class is unit-testable with plain `new`.
    IngestController(IngestUseCase ingest, TenantResolver tenantResolver) {
        this.ingest = ingest;
        this.tenantResolver = tenantResolver;
    }

    @PostMapping(path = "/v1/events:batch", consumes = "application/json", produces = "application/json")
    ResponseEntity<IngestDtos.BatchAcceptedResponse> ingestBatch(
            @Valid @RequestBody IngestDtos.EventBatchRequest request,
            // Optional: per-event eventId already provides idempotency. This lets a client
            // short-circuit a whole retried batch without us deduplicating 500 ids one by one.
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            // The tenant comes from the VERIFIED token, never the body or a client-set
            // header (REQ-IG-07/08). Spring Security has already validated the signature,
            // issuer and expiry before this method runs.
            Authentication authentication) {

        TenantId tenant = tenantResolver.resolve(authentication);

        IngestCommand command = new IngestCommand(tenant, toDomain(request.events()), Instant.now());

        IngestResult result = ingest.ingest(command);

        log.debug(
                "Ingested batch tenant={} published={} duplicates={} degraded={}",
                tenant,
                result.published(),
                result.duplicates(),
                result.idempotencyDegraded());

        // 202 Accepted, NOT 201 Created. The events are durable in Kafka but not yet
        // processed; claiming otherwise would be a lie the client might act on by discarding
        // its local copy. The status code is part of the contract.
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new IngestDtos.BatchAcceptedResponse(
                        result.published(),
                        result.duplicates(),
                        idempotencyKey != null
                                ? idempotencyKey
                                : UUID.randomUUID().toString(),
                        result.idempotencyDegraded()));
    }

    private List<IngestCommand.IngestEvent> toDomain(List<IngestDtos.EventDto> dtos) {
        return dtos.stream()
                .map(d -> new IngestCommand.IngestEvent(
                        // EventId validates the UUID here, at the boundary. Everything
                        // downstream can then assume it is well-formed.
                        EventId.of(d.eventId()),
                        d.sessionId(),
                        d.titleId(),
                        d.deviceType(),
                        d.region(),
                        d.occurredAt(),
                        d.type(),
                        d.payload()))
                .toList();
    }
}

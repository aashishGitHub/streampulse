/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.adapter.out.kafka;

import com.streampulse.contracts.v1.TelemetryEvent;
import com.streampulse.ingest.domain.EventPublisher;
import com.streampulse.ingest.domain.IngestCommand;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Publishes to Kafka. The only class in this service that knows Kafka exists.
 *
 * <h2>Why the port returns a future</h2>
 *
 * {@code 202 Accepted} promises the events are durable. Returning before the broker
 * acknowledges would make that a lie a client acts on by discarding its local copy. The
 * future lets the use case decide how to wait, and lets several sends be in flight at once.
 *
 * <h2>Delivery guarantees come from configuration, not from this code</h2>
 *
 * {@code enable.idempotence=true} and {@code acks=all} in {@code application.yml} are what
 * make a producer-side retry safe: the broker assigns a producer id and sequence number per
 * partition, so a retried record that already landed is discarded rather than appended
 * twice. Without it, a retry after a lost acknowledgement silently duplicates.
 *
 * <p>Note the layering: <b>producer</b> idempotence stops retries duplicating on the wire;
 * our <b>Redis</b> claim stops the client's own retries duplicating. They solve different
 * problems and you need both.
 */
public class KafkaEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

    private final KafkaTemplate<String, byte[]> kafka;
    private final String topic;
    private final AvroEventMapper mapper;

    public KafkaEventPublisher(KafkaTemplate<String, byte[]> kafka, String topic, AvroEventMapper mapper) {
        this.kafka = kafka;
        this.topic = topic;
        this.mapper = mapper;
    }

    @Override
    public CompletableFuture<Void> publishAll(TenantScopedEvents scoped) {
        // CompletableFuture<?>[] rather than List<CompletableFuture<?>>: Java's generics are
        // invariant, so a List<CompletableFuture<SendResult>> is NOT a List<CompletableFuture<?>>.
        // Collecting straight to an array sidesteps the wildcard-capture problem and is what
        // allOf wants anyway.
        CompletableFuture<?>[] sends = scoped.events().stream()
                .map(event -> send(scoped.tenantId(), event))
                .toArray(CompletableFuture<?>[]::new);

        // allOf completes when EVERY send has completed, and completes exceptionally if any
        // failed. Partial success is not represented because the caller cannot act on it:
        // the batch is retried whole, and idempotency makes that safe.
        return CompletableFuture.allOf(sends);
    }

    private CompletableFuture<?> send(String tenantId, IngestCommand.IngestEvent event) {
        TelemetryEvent avro = mapper.toAvro(tenantId, event);
        byte[] value = serialize(avro);

        // The KEY is what decides the partition, and therefore the ordering guarantee.
        // sessionId keeps one viewing session on one partition. See specs/01-contracts/design.md.
        return kafka.send(topic, event.partitionKey(), value).whenComplete((result, failure) -> {
            if (failure != null) {
                // Log the KEY, never the payload: payloads are large and may carry data we
                // should not spray into logs.
                log.error("Failed to publish event key={} to {}", event.partitionKey(), topic, failure);
            }
        });
    }

    /**
     * Avro binary encoding.
     *
     * <p>Phase 2 writes the raw encoding. Phase 3 adds Schema Registry, which prefixes a
     * magic byte and a 4-byte schema id so consumers can resolve the writer's schema. Doing
     * it by hand here keeps the phase free of another running dependency.
     */
    private byte[] serialize(TelemetryEvent event) {
        try {
            var writer = new SpecificDatumWriter<TelemetryEvent>(TelemetryEvent.class);
            var out = new ByteArrayOutputStream();
            BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
            writer.write(event, encoder);
            encoder.flush();
            return out.toByteArray();
        } catch (IOException e) {
            // A serialization failure is a programming error, not a transient fault —
            // retrying cannot help, so it must not be swallowed into a retry loop.
            throw new UncheckedIOException("Failed to serialize event " + event.getEventId(), e);
        }
    }
}

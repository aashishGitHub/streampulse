/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.adapter.out.kinesis;

import com.streampulse.contracts.v1.TelemetryEvent;
import com.streampulse.ingest.adapter.out.kafka.AvroEventMapper;
import com.streampulse.ingest.domain.EventPublisher;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient;
import software.amazon.awssdk.services.kinesis.model.PutRecordsRequest;
import software.amazon.awssdk.services.kinesis.model.PutRecordsRequestEntry;
import software.amazon.awssdk.services.kinesis.model.PutRecordsResponse;

/**
 * Publishes to AWS Kinesis Data Streams. The default backbone.
 *
 * <h2>Kinesis vs Kafka, in the terms that matter here</h2>
 *
 * <pre>
 *   Kafka                         Kinesis
 *   ─────                         ───────
 *   partition                     shard
 *   partition key → partition     partition key → shard   ← SAME ordering guarantee
 *   consumer group                KCL application / enhanced fan-out
 *   offset (consumer-managed)     sequence number + checkpoint (in DynamoDB)
 *   retention: time/size          retention: 24h (up to 365 days)
 *   scale: add partitions         scale: split/merge shards
 *   throughput: broker-bound      1 MB/s or 1000 rec/s PER SHARD (a hard quota)
 * </pre>
 *
 * The property this service depends on is identical: <b>records with the same partition key
 * land on the same shard, in order</b>. So {@code sessionId} keeps a viewing session ordered
 * exactly as it did on Kafka, and the domain never learns which one it is talking to.
 *
 * <p>The genuinely different one is the last row. Kinesis quotas are per shard and hard —
 * exceed them and you get {@code ProvisionedThroughputExceededException}, which is a
 * throttle, not a failure. Kafka has no equivalent fixed per-partition cap. That single
 * difference drives most real Kinesis design work, and it is why {@code PutRecords} can
 * partially fail (see below).
 *
 * <h2>Running this for free</h2>
 *
 * Locally this points at <b>moto</b>, a fake AWS API in a container. Credentials are the
 * literal string {@code test}; nothing reaches AWS and no account is needed. The SDK cannot
 * tell the difference, so the code is identical to production. See ADR-0003 for what that
 * does and does not prove.
 */
public class KinesisEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KinesisEventPublisher.class);

    private final KinesisAsyncClient kinesis;
    private final String streamName;
    private final AvroEventMapper mapper;

    public KinesisEventPublisher(KinesisAsyncClient kinesis, String streamName, AvroEventMapper mapper) {
        this.kinesis = kinesis;
        this.streamName = streamName;
        this.mapper = mapper;
    }

    @Override
    public CompletableFuture<Void> publishAll(TenantScopedEvents scoped) {
        List<PutRecordsRequestEntry> entries = scoped.events().stream()
                .map(event -> PutRecordsRequestEntry.builder()
                        // The PARTITION KEY decides the shard, and therefore the ordering
                        // guarantee. sessionId, for exactly the reason it was the Kafka key.
                        .partitionKey(event.partitionKey())
                        .data(SdkBytes.fromByteArray(serialize(mapper.toAvro(scoped.tenantId(), event))))
                        .build())
                .toList();

        PutRecordsRequest request = PutRecordsRequest.builder()
                .streamName(streamName)
                .records(entries)
                .build();

        return kinesis.putRecords(request).thenAccept(this::failOnPartialFailure);
    }

    /**
     * Turns a partial failure into a failed future.
     *
     * <h2>The Kinesis trap</h2>
     *
     * {@code PutRecords} returns <b>HTTP 200 even when individual records failed</b>. The
     * per-record outcome is in {@code FailedRecordCount} and the {@code Records} list, and
     * the usual cause is {@code ProvisionedThroughputExceededException} — one shard hit its
     * 1 MB/s or 1000 rec/s quota while the others were fine.
     *
     * <p>Code that checks only the HTTP status silently drops those records. It is the single
     * most common Kinesis bug, and it is invisible until someone notices the numbers are low.
     *
     * <p>We fail the whole batch, because the caller already retries the batch as a unit and
     * idempotency (the Redis claim) makes that safe: the records that succeeded are recognised
     * as duplicates on the retry and are not written twice. A production system under
     * sustained throttling would resubmit only the failed entries with backoff — that is
     * Phase 9's work, once there is load to measure it against.
     */
    private void failOnPartialFailure(PutRecordsResponse response) {
        int failed = response.failedRecordCount() == null ? 0 : response.failedRecordCount();
        if (failed == 0) {
            return;
        }

        String firstError = response.records().stream()
                .filter(r -> r.errorCode() != null)
                .findFirst()
                .map(r -> r.errorCode() + ": " + r.errorMessage())
                .orElse("unknown");

        log.error(
                "Kinesis PutRecords partially failed: {}/{} records, first error {}",
                failed,
                response.records().size(),
                firstError);

        throw new IllegalStateException("Kinesis rejected " + failed + " of "
                + response.records().size() + " records; first error " + firstError);
    }

    private byte[] serialize(TelemetryEvent event) {
        try {
            var writer = new SpecificDatumWriter<TelemetryEvent>(TelemetryEvent.class);
            var out = new ByteArrayOutputStream();
            BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
            writer.write(event, encoder);
            encoder.flush();
            return out.toByteArray();
        } catch (IOException e) {
            // A serialization failure is a bug, not a transient fault — retrying cannot help,
            // so it must not be swallowed into a retry loop.
            throw new UncheckedIOException("Failed to serialize event " + event.getEventId(), e);
        }
    }
}

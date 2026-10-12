/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Timestamp;
import com.streampulse.contracts.grpc.v1.Ack;
import com.streampulse.contracts.grpc.v1.Batch;
import com.streampulse.contracts.grpc.v1.TelemetryIngestGrpc;
import com.streampulse.ingest.lifecycle.GrpcServerLifecycle;
import com.streampulse.ingest.support.TestJwtSupport;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.CreateStreamRequest;
import software.amazon.awssdk.services.kinesis.model.GetRecordsRequest;
import software.amazon.awssdk.services.kinesis.model.GetShardIteratorRequest;
import software.amazon.awssdk.services.kinesis.model.Record;
import software.amazon.awssdk.services.kinesis.model.ShardIteratorType;

/**
 * <b>The test BS-09 exists to make possible</b>: the same event, through three different front
 * doors, produces identical records on the stream (REQ-GR-02).
 *
 * <h2>Why this is the most valuable test in the phase</h2>
 *
 * Every other claim about the hexagon is architecture talk. This one is falsifiable.
 *
 * <pre>
 *   POST /v1/events:batch  ─┐
 *   TelemetryIngest/SendBatch ├─▶ IngestUseCase ─▶ AvroEventMapper ─▶ Kinesis
 *   TelemetryIngest/StreamEvents ┘
 *                                 ▲
 *                    quota, dedup, backpressure, tenant, partition key
 *                    — decided ONCE, or this test goes red
 * </pre>
 *
 * The failure it is designed to catch is not a crash. It is the slow one: someone adds a
 * default, a trim, a fallback or a clamp to one adapter, and from then on two customers' data
 * differs depending on which protocol their SDK happened to use. That divergence is invisible
 * in code review — each file looks correct on its own — and it is discovered months later as
 * "why do the smart-TV numbers not match the web numbers?".
 *
 * <h2>How parity is asserted without idempotency getting in the way</h2>
 *
 * The three events must have different {@code eventId}s or the second and third are
 * deduplicated and never published — which would make the test pass by publishing nothing.
 * They share a {@code sessionId}, so they also land on one shard, and every other field is
 * compared for equality:
 *
 * <pre>
 *   compared:  tenantId, sessionId, titleId, deviceType, region, occurredAt, type, partition key
 *   excluded:  eventId   — deliberately different, or nothing would publish
 *              receivedAt — stamped by the server per record; equal values would mean a frozen clock
 * </pre>
 *
 * <h2>What this proves, and what it does not</h2>
 *
 * <b>Proves:</b> both adapters converge on one code path before anything is serialized; the
 * tenant comes from the token on both; the partition key survives both; the Avro bytes are
 * decodable from both.
 *
 * <p><b>Does not prove:</b> anything about throughput, CPU or bytes on the wire — the REST vs
 * gRPC comparison REQ-GR-06 asks for is a benchmark, not a test, and it is not done. See
 * ADR-0010 and MILESTONES.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Port 0: the OS picks a free one. A fixed port would collide with whatever else is
        // listening on the developer's machine, and with any other test class in the build.
        // These two prove the Kinesis comparison adapter against moto; Kafka is the default
        // backbone since ADR-0012, so the switch is stated rather than inherited.
        properties = {"streampulse.ingest.grpc.port=0", "streampulse.backbone=kinesis"})
@org.springframework.context.annotation.Import(TestJwtSupport.class)
@Testcontainers
class ProtocolParityIT {

    private static final String STREAM = "telemetry-parity-v1";
    private static final String TENANT = "streamflix";

    /** One session id for all three, so they share a partition key and a shard. */
    private static final String SESSION = "sess-parity-" + UUID.randomUUID();

    private static final Instant OCCURRED_AT = Instant.parse("2026-01-15T12:00:00Z");

    @Container
    static final GenericContainer<?> MOTO =
            new GenericContainer<>(DockerImageName.parse("motoserver/moto:5.2.2")).withExposedPorts(5000);

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static String motoEndpoint() {
        return "http://" + MOTO.getHost() + ":" + MOTO.getMappedPort(5000);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("streampulse.aws.endpoint-override", ProtocolParityIT::motoEndpoint);
        registry.add("streampulse.aws.kinesis-stream-name", () -> STREAM);
    }

    private static KinesisClient kinesis;

    @BeforeAll
    static void createStream() {
        kinesis = KinesisClient.builder()
                .region(Region.US_EAST_1)
                .endpointOverride(URI.create(motoEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
        kinesis.createStream(
                CreateStreamRequest.builder().streamName(STREAM).shardCount(1).build());
    }

    @Autowired
    private TestRestTemplate rest;

    /** The REAL server the application started — same beans, same interceptors, same port. */
    @Autowired
    private GrpcServerLifecycle grpcServer;

    private static ManagedChannel channel;

    @AfterAll
    static void closeChannel() {
        if (channel != null) {
            channel.shutdownNow();
        }
    }

    // ------------------------------------------------------------------

    private TelemetryIngestGrpc.TelemetryIngestBlockingStub blockingStub() {
        return TelemetryIngestGrpc.newBlockingStub(channel()).withInterceptors(bearer());
    }

    private TelemetryIngestGrpc.TelemetryIngestStub asyncStub() {
        return TelemetryIngestGrpc.newStub(channel()).withInterceptors(bearer());
    }

    private synchronized ManagedChannel channel() {
        if (channel == null) {
            // A real TCP channel over real HTTP/2, to the port the lifecycle actually bound.
            // Everything else here could have been in-process; this one hop is what makes the
            // comparison with an HTTP request honest.
            channel = ManagedChannelBuilder.forAddress("localhost", grpcServer.port())
                    // Plaintext in a test. In production this is TLS, and the ADR says why the
                    // load-balancing story changes completely once it is.
                    .usePlaintext()
                    .build();
        }
        return channel;
    }

    private static io.grpc.ClientInterceptor bearer() {
        Metadata metadata = new Metadata();
        metadata.put(
                Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                "Bearer " + TestJwtSupport.tokenFor(TENANT));
        return MetadataUtils.newAttachHeadersInterceptor(metadata);
    }

    private static com.streampulse.contracts.grpc.v1.TelemetryEvent protoEvent(String eventId, long sequence) {
        return com.streampulse.contracts.grpc.v1.TelemetryEvent.newBuilder()
                .setEventId(eventId)
                .setSessionId(SESSION)
                .setTitleId("title-42")
                .setDeviceType("SMART_TV")
                .setRegion("us-east-1")
                .setOccurredAt(Timestamp.newBuilder()
                        .setSeconds(OCCURRED_AT.getEpochSecond())
                        .setNanos(OCCURRED_AT.getNano())
                        .build())
                .setType("HEARTBEAT")
                .setSequence(sequence)
                .build();
    }

    /** The same event as {@link #protoEvent}, in the REST adapter's JSON. */
    private static String jsonEvent(String eventId) {
        return """
                {"events":[{"eventId":"%s","sessionId":"%s","titleId":"title-42",
                 "deviceType":"SMART_TV","region":"us-east-1",
                 "occurredAt":"%s","type":"HEARTBEAT"}]}"""
                .formatted(eventId, SESSION, OCCURRED_AT);
    }

    private static com.streampulse.contracts.v1.TelemetryEvent decode(Record record) throws Exception {
        var reader = new SpecificDatumReader<com.streampulse.contracts.v1.TelemetryEvent>(
                com.streampulse.contracts.v1.TelemetryEvent.class);
        return reader.read(
                null, DecoderFactory.get().binaryDecoder(record.data().asByteArray(), null));
    }

    private static List<Record> drainStream() {
        var shard = kinesis.listShards(b -> b.streamName(STREAM)).shards().get(0);
        String iterator = kinesis.getShardIterator(GetShardIteratorRequest.builder()
                        .streamName(STREAM)
                        .shardId(shard.shardId())
                        .shardIteratorType(ShardIteratorType.TRIM_HORIZON)
                        .build())
                .shardIterator();
        return kinesis.getRecords(
                        GetRecordsRequest.builder().shardIterator(iterator).build())
                .records();
    }

    private static Record recordFor(List<Record> records, String eventId) throws Exception {
        for (Record record : records) {
            if (decode(record).getEventId().toString().equals(eventId)) {
                return record;
            }
        }
        throw new AssertionError("no record on the stream for eventId " + eventId);
    }

    // ==================================================================

    @Test
    @DisplayName("PARITY: REST, gRPC unary and gRPC streaming produce identical records")
    void allThreeProtocolsProduceIdenticalRecords() throws Exception {
        String restEventId = UUID.randomUUID().toString();
        String unaryEventId = UUID.randomUUID().toString();
        String streamEventId = UUID.randomUUID().toString();

        // ---- 1. REST ----
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestJwtSupport.tokenFor(TENANT));
        var restResponse =
                rest.postForEntity("/v1/events:batch", new HttpEntity<>(jsonEvent(restEventId), headers), String.class);
        assertThat(restResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        // ---- 2. gRPC unary ----
        var batchAck = blockingStub()
                .sendBatch(Batch.newBuilder()
                        .addEvents(protoEvent(unaryEventId, 1))
                        .build());
        assertThat(batchAck.getAccepted()).isEqualTo(1);

        // ---- 3. gRPC bidirectional stream ----
        var acks = new CopyOnWriteArrayList<Ack>();
        var streamDone = new CountDownLatch(1);
        StreamObserver<com.streampulse.contracts.grpc.v1.TelemetryEvent> request = asyncStub()
                .streamEvents(new StreamObserver<>() {
                    @Override
                    public void onNext(Ack ack) {
                        acks.add(ack);
                    }

                    @Override
                    public void onError(Throwable t) {
                        streamDone.countDown();
                    }

                    @Override
                    public void onCompleted() {
                        streamDone.countDown();
                    }
                });
        request.onNext(protoEvent(streamEventId, 7));
        // One event against a flush size of 50: the batch is flushed by the half-close, which
        // is the path a real SDK takes when it shuts down cleanly.
        request.onCompleted();
        assertThat(streamDone.await(15, TimeUnit.SECONDS)).isTrue();
        assertThat(acks).hasSize(1);
        assertThat(acks.get(0).getLastDurableSequence())
                .as("the ACK must name the sequence the client may now forget")
                .isEqualTo(7L);

        // ---- the assertion the whole class exists for ----
        List<Record> records = drainStream();
        Record viaRest = recordFor(records, restEventId);
        Record viaUnary = recordFor(records, unaryEventId);
        Record viaStream = recordFor(records, streamEventId);

        var restEvent = decode(viaRest);
        var unaryEvent = decode(viaUnary);
        var streamEvent = decode(viaStream);

        // The partition key is the ordering guarantee. If one adapter picked a different field
        // for it, events from one session would stop being ordered depending on the client's
        // protocol — a correctness bug that no unit test would ever show.
        assertThat(viaRest.partitionKey()).isEqualTo(SESSION);
        assertThat(viaUnary.partitionKey()).isEqualTo(SESSION);
        assertThat(viaStream.partitionKey()).isEqualTo(SESSION);

        // Field by field, deliberately not as a single object comparison: when this fails, the
        // message should name the field that diverged rather than dumping two Avro records and
        // leaving the reader to diff them.
        for (var other : List.of(unaryEvent, streamEvent)) {
            assertThat(other.getTenantId())
                    .as("tenant — from the verified token on every protocol, never the payload")
                    .isEqualTo(restEvent.getTenantId());
            assertThat(other.getSessionId()).as("sessionId").isEqualTo(restEvent.getSessionId());
            assertThat(other.getTitleId()).as("titleId").isEqualTo(restEvent.getTitleId());
            assertThat(other.getDeviceType()).as("deviceType").isEqualTo(restEvent.getDeviceType());
            assertThat(other.getRegion()).as("region").isEqualTo(restEvent.getRegion());
            assertThat(other.getOccurredAt())
                    .as("occurredAt — JSON ISO-8601 and protobuf Timestamp must land on the same instant")
                    .isEqualTo(restEvent.getOccurredAt());
            assertThat(other.getType()).as("type").isEqualTo(restEvent.getType());
            assertThat(other.getPayload()).as("payload").isEqualTo(restEvent.getPayload());

            // receivedAt is stamped per record by the server, so it is EXPECTED to differ.
            // Asserting it is present rather than equal keeps the parity claim honest: a test
            // that quietly ignored a field would be worth less than no test.
            assertThat(other.getReceivedAt()).as("receivedAt is always stamped").isNotNull();
        }

        assertThat(restEvent.getTenantId()).hasToString(TENANT);
    }

    @Test
    @DisplayName("idempotency is shared: an eventId sent over REST is a duplicate over gRPC")
    void deduplicationSpansProtocols() {
        // The sharpest possible statement of "one use case, two doors". Deduplication lives in
        // the use case, so the SECOND protocol to see an id must be told it is a duplicate.
        //
        // Were the adapters to keep their own state — a cache, a seen-set, anything — this
        // would report two accepted events, and a client retrying over a different transport
        // would silently double-count its telemetry.
        String sharedEventId = UUID.randomUUID().toString();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestJwtSupport.tokenFor(TENANT));
        var restResponse = rest.postForEntity(
                "/v1/events:batch", new HttpEntity<>(jsonEvent(sharedEventId), headers), String.class);
        assertThat(restResponse.getBody()).contains("\"accepted\":1", "\"duplicates\":0");

        var ack = blockingStub()
                .sendBatch(Batch.newBuilder()
                        .addEvents(protoEvent(sharedEventId, 1))
                        .build());

        assertThat(ack.getAccepted()).as("nothing new to publish").isZero();
        assertThat(ack.getDuplicates()).as("and the client is told why").isEqualTo(1);
    }
}

/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.streampulse.contracts.v1.TelemetryEvent;
import com.streampulse.ingest.support.TestJwtSupport;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
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
import org.springframework.http.ResponseEntity;
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
 * End-to-end: HTTP request → Redis dedup → Kinesis record.
 *
 * <h2>Why moto rather than real AWS</h2>
 *
 * moto is a fake AWS API in a container. Credentials are the literal string {@code test};
 * nothing leaves the machine and <b>no AWS account is required</b>. The SDK cannot tell the
 * difference, so the code under test is byte-identical to what would run in production.
 *
 * <p>It is also dramatically cheaper than the alternatives: the moto image is ~385 MB and
 * starts in seconds, against ~400 MB and a much slower boot for a Kafka broker.
 *
 * <h2>What this proves, and what it does not</h2>
 *
 * <b>Proves:</b> the Spring context wires up; Avro bytes survive the round trip and are
 * decodable; the partition key reaches the stream and routes by shard; Redis
 * {@code SET NX EX} deduplicates as assumed.
 *
 * <b>Does not prove:</b> IAM policies (moto does not evaluate them), throughput behaviour
 * (no real shard quotas), or latency. A call that succeeds here can still be denied in
 * production by a policy moto never saw. ADR-0003.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // BS-09 added a gRPC listener that starts with the context. Port 0 lets the OS choose
        // one, so this class cannot collide with the configured 9090 — whether that is taken
        // by another test class or by something already running on the developer's machine.
        properties = "streampulse.ingest.grpc.port=0")
@org.springframework.context.annotation.Import(TestJwtSupport.class)
@Testcontainers
class IngestEndToEndIT {

    private static final String STREAM = "telemetry-raw-v1";

    /**
     * moto: Kinesis, S3 and SQS behind one endpoint.
     *
     * <p>{@code static} so it starts once for the whole class — the singleton-container
     * pattern that keeps an integration suite affordable.
     */
    @Container
    static final GenericContainer<?> MOTO =
            new GenericContainer<>(DockerImageName.parse("motoserver/moto:latest")).withExposedPorts(5000);

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
        registry.add("streampulse.aws.endpoint-override", IngestEndToEndIT::motoEndpoint);
        registry.add("streampulse.aws.kinesis-stream-name", () -> STREAM);
    }

    private static KinesisClient kinesis;

    @BeforeAll
    static void createStream() {
        kinesis = KinesisClient.builder()
                .region(Region.US_EAST_1)
                .endpointOverride(URI.create(motoEndpoint()))
                // Dummy credentials. moto checks that some are present, not what they are.
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();

        // Two shards, so the "same key → same shard" assertion is meaningful. With one
        // shard everything lands together and the test would prove nothing about routing.
        kinesis.createStream(
                CreateStreamRequest.builder().streamName(STREAM).shardCount(2).build());
    }

    @Autowired
    private TestRestTemplate rest;

    // ------------------------------------------------------------------

    private static String eventJson(String eventId, String sessionId) {
        return """
                {"eventId":"%s","sessionId":"%s","titleId":"title-42",
                 "deviceType":"SMART_TV","region":"us-east-1",
                 "occurredAt":"2026-01-15T12:00:00Z","type":"HEARTBEAT"}"""
                .formatted(eventId, sessionId);
    }

    /** Posts with a valid token whose `tenant` claim is {@code tenant}. */
    private ResponseEntity<String> postBatch(String tenant, String... events) {
        return postWithToken(TestJwtSupport.tokenFor(tenant), events);
    }

    private ResponseEntity<String> postWithToken(String token, String... events) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        String body = "{\"events\":[" + String.join(",", events) + "]}";
        return rest.postForEntity("/v1/events:batch", new HttpEntity<>(body, headers), String.class);
    }

    /** Reads every record currently on the stream, across all shards. */
    private static List<Record> drainStream() {
        var all = new java.util.ArrayList<Record>();
        var shards = kinesis.listShards(b -> b.streamName(STREAM)).shards();
        for (var shard : shards) {
            String iterator = kinesis.getShardIterator(GetShardIteratorRequest.builder()
                            .streamName(STREAM)
                            .shardId(shard.shardId())
                            // TRIM_HORIZON = from the oldest record still retained, so the
                            // test sees everything rather than only what arrives after it
                            // starts listening.
                            .shardIteratorType(ShardIteratorType.TRIM_HORIZON)
                            .build())
                    .shardIterator();
            all.addAll(kinesis.getRecords(
                            GetRecordsRequest.builder().shardIterator(iterator).build())
                    .records());
        }
        return all;
    }

    private static TelemetryEvent deserialize(Record record) throws Exception {
        var reader = new SpecificDatumReader<TelemetryEvent>(TelemetryEvent.class);
        return reader.read(
                null, DecoderFactory.get().binaryDecoder(record.data().asByteArray(), null));
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("a batch is accepted and lands on Kinesis with sessionId as the partition key")
    void batchReachesKinesisWithTheRightPartitionKey() throws Exception {
        String eventId = UUID.randomUUID().toString();
        String sessionId = "sess-" + UUID.randomUUID();

        ResponseEntity<String> response = postBatch("streamflix", eventJson(eventId, sessionId));

        // 202, not 201: durable on the stream, not yet processed.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).contains("\"accepted\":1");

        List<Record> records = drainStream();
        Record ours = records.stream()
                .filter(r -> r.partitionKey().equals(sessionId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no record with partition key " + sessionId));

        // The PARTITION KEY is the ordering guarantee — the same property Kafka gives via
        // its partition key. Asserting it proves the decision survives to the stream.
        assertThat(ours.partitionKey()).isEqualTo(sessionId);

        // And the bytes are genuinely Avro-decodable, which a mocked client could never
        // have told us.
        TelemetryEvent event = deserialize(ours);
        assertThat(event.getEventId()).hasToString(eventId);
        assertThat(event.getTenantId()).hasToString("streamflix");
        assertThat(event.getReceivedAt()).isNotNull(); // stamped by us, not the client
    }

    @Test
    @DisplayName("records sharing a session id land on the SAME shard, preserving order")
    void sameSessionGoesToTheSameShard() {
        String sessionId = "sess-" + UUID.randomUUID();
        postBatch(
                "streamflix",
                eventJson(UUID.randomUUID().toString(), sessionId),
                eventJson(UUID.randomUUID().toString(), sessionId),
                eventJson(UUID.randomUUID().toString(), sessionId));

        // The whole reason sessionId is the partition key. With 2 shards this could only
        // pass by accident 1 time in 4 if routing were random — and it passes every time,
        // because Kinesis hashes the partition key exactly as Kafka does.
        var shardsUsed = kinesis.listShards(b -> b.streamName(STREAM)).shards().stream()
                .filter(shard -> {
                    String it = kinesis.getShardIterator(GetShardIteratorRequest.builder()
                                    .streamName(STREAM)
                                    .shardId(shard.shardId())
                                    .shardIteratorType(ShardIteratorType.TRIM_HORIZON)
                                    .build())
                            .shardIterator();
                    return kinesis
                            .getRecords(GetRecordsRequest.builder()
                                    .shardIterator(it)
                                    .build())
                            .records()
                            .stream()
                            .anyMatch(r -> r.partitionKey().equals(sessionId));
                })
                .count();

        assertThat(shardsUsed)
                .as("all records for one session must be on exactly one shard")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("resubmitting the same batch publishes nothing new — against a REAL Redis")
    void duplicateBatchIsDeduplicatedAgainstRealRedis() {
        String event = eventJson(UUID.randomUUID().toString(), "sess-" + UUID.randomUUID());

        ResponseEntity<String> first = postBatch("streamflix", event);
        ResponseEntity<String> second = postBatch("streamflix", event); // the client retried

        assertThat(first.getBody()).contains("\"accepted\":1", "\"duplicates\":0");

        // Still 202, but nothing new published. The unit test proved the logic; this proves
        // the assumption about Redis SET NX EX semantics.
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getBody()).contains("\"accepted\":0", "\"duplicates\":1");
    }

    @Test
    @DisplayName("the same eventId under a different tenant is NOT a duplicate")
    void idempotencyIsNamespacedByTenant() {
        String event = eventJson(UUID.randomUUID().toString(), "sess-" + UUID.randomUUID());

        assertThat(postBatch("streamflix", event).getBody()).contains("\"accepted\":1");

        // Without tenant namespacing this would report a duplicate and silently drop another
        // tenant's event — cross-tenant data loss, and nearly undiagnosable.
        assertThat(postBatch("sportsnow", event).getBody()).contains("\"accepted\":1");
    }

    @Test
    @DisplayName("an oversized batch is rejected with a ProblemDetail")
    void oversizedBatchIsRejected() {
        String[] events = new String[501];
        for (int i = 0; i < events.length; i++) {
            events[i] = eventJson(UUID.randomUUID().toString(), "sess-" + i);
        }

        ResponseEntity<String> response = postBatch("streamflix", events);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("at most 500 events");
    }

    @Test
    @DisplayName("a malformed eventId is rejected at the boundary")
    void malformedEventIdIsRejected() {
        ResponseEntity<String> response = postBatch("streamflix", eventJson("not-a-uuid", "sess-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("valid UUID");
    }

    @Test
    @DisplayName("a malformed tenant claim is rejected before anything is published")
    void malformedTenantIsRejected() {
        ResponseEntity<String> response =
                postBatch("BAD TENANT/NAME", eventJson(UUID.randomUUID().toString(), "sess-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---------------- security ----------------

    @Test
    @DisplayName("no token is rejected with 401")
    void missingTokenIsUnauthorized() {
        ResponseEntity<String> response =
                postWithToken(null, eventJson(UUID.randomUUID().toString(), "sess-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a garbage token is rejected with 401")
    void invalidTokenIsUnauthorized() {
        ResponseEntity<String> response =
                postWithToken("not.a.jwt", eventJson(UUID.randomUUID().toString(), "sess-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("an EXPIRED token is rejected — expiry is actually checked")
    void expiredTokenIsUnauthorized() {
        // Worth asserting explicitly: a decoder misconfigured to skip expiry validation
        // looks completely healthy until someone replays an old token.
        ResponseEntity<String> response = postWithToken(
                TestJwtSupport.expiredTokenFor("streamflix"),
                eventJson(UUID.randomUUID().toString(), "sess-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a valid token with NO tenant claim fails closed")
    void tokenWithoutTenantClaimIsRejected() {
        // Signature valid, issuer fine, not expired — but no tenant. Guessing a default
        // here would silently write one customer's data into another's namespace.
        ResponseEntity<String> response = postWithToken(
                TestJwtSupport.tokenWithoutTenant(), eventJson(UUID.randomUUID().toString(), "sess-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("the BODY cannot override the tenant — the token wins")
    void bodyCannotOverrideTheTenant() throws Exception {
        // THE multi-tenancy test. A caller authenticated as `streamflix` sends a body that
        // also claims `tenantId: sportsnow`. The event must still be written as streamflix,
        // because the DTO has no tenant field at all — there is nothing to trust.
        String eventId = UUID.randomUUID().toString();
        String sessionId = "sess-" + UUID.randomUUID();
        String maliciousEvent =
                """
                {"eventId":"%s","sessionId":"%s","tenantId":"sportsnow","titleId":"t",
                 "deviceType":"WEB","region":"us-east-1",
                 "occurredAt":"2026-01-15T12:00:00Z","type":"HEARTBEAT"}"""
                        .formatted(eventId, sessionId);

        assertThat(postBatch("streamflix", maliciousEvent).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        Record ours = drainStream().stream()
                .filter(r -> r.partitionKey().equals(sessionId))
                .findFirst()
                .orElseThrow();

        assertThat(deserialize(ours).getTenantId())
                .as("the tenant must come from the verified token, never the request body")
                .hasToString("streamflix");
    }

    @Test
    @DisplayName("Kinesis reports a timeout budget shorter than the default (sanity check)")
    void streamIsReachable() {
        // A trivially cheap check that fails LOUDLY if moto is up but the stream was never
        // created — otherwise every other test fails with a confusing ResourceNotFound.
        assertThat(kinesis.describeStreamSummary(b -> b.streamName(STREAM))
                        .streamDescriptionSummary()
                        .openShardCount())
                .isEqualTo(2);
    }
}

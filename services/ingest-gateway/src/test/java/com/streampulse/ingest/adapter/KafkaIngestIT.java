/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.streampulse.contracts.codec.AvroBinaryCodec;
import com.streampulse.contracts.v1.TelemetryEvent;
import com.streampulse.ingest.support.TestJwtSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * HTTP request → Redis dedup → a real Kafka broker, with NO property selecting the backbone:
 * Kafka is the default since ADR-0012, and this test is what stops that default from
 * silently rotting.
 *
 * <p>What it proves that a mocked {@code KafkaTemplate} cannot: the record key, the partition
 * it lands on, the timestamp the broker stored, and that the bytes decode.
 *
 * <p>Does not prove broker-side exactly-once or behaviour under broker failure; the single
 * broker here cannot lose a replica.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "streampulse.ingest.grpc.port=0")
@org.springframework.context.annotation.Import(TestJwtSupport.class)
@Testcontainers
class KafkaIngestIT {

    private static final String TOPIC = "telemetry.raw.v1";
    private static final int PARTITIONS = 12;
    private static final Instant OCCURRED_AT = Instant.parse("2026-01-15T12:00:00Z");
    private static final AvroBinaryCodec<TelemetryEvent> CODEC = AvroBinaryCodec.of(TelemetryEvent.class);

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @BeforeAll
    static void createTopic() throws Exception {
        // 12 partitions, like docker/compose.yml. With the broker's default of 1 the
        // "same session, same partition" assertion below would hold trivially and prove nothing.
        try (var admin = AdminClient.create(Map.<String, Object>of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, PARTITIONS, (short) 1)))
                    .all()
                    .get();
        }
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private KafkaTemplate<String, byte[]> kafkaTemplate;

    private static String eventJson(String eventId, String sessionId) {
        return """
                {"eventId":"%s","sessionId":"%s","titleId":"title-42",
                 "deviceType":"SMART_TV","region":"us-east-1",
                 "occurredAt":"%s","type":"HEARTBEAT"}"""
                .formatted(eventId, sessionId, OCCURRED_AT);
    }

    private ResponseEntity<String> postBatch(String... events) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestJwtSupport.tokenFor("streamflix"));
        String body = "{\"events\":[" + String.join(",", events) + "]}";
        return rest.postForEntity("/v1/events:batch", new HttpEntity<>(body, headers), String.class);
    }

    /** Everything on the topic right now, read from every partition up to its end offset. */
    private static List<ConsumerRecord<String, byte[]>> readAll() {
        var props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        try (var consumer = new KafkaConsumer<String, byte[]>(props)) {
            List<TopicPartition> partitions = consumer.partitionsFor(TOPIC).stream()
                    .map(info -> new TopicPartition(TOPIC, info.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> ends = consumer.endOffsets(partitions);

            var records = new ArrayList<ConsumerRecord<String, byte[]>>();
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (partitions.stream().anyMatch(tp -> consumer.position(tp) < ends.get(tp))) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("did not reach the end offsets within 20s: " + ends);
                }
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            }
            return records;
        }
    }

    private static List<ConsumerRecord<String, byte[]>> forSession(String sessionId) {
        return readAll().stream().filter(r -> sessionId.equals(r.key())).toList();
    }

    @Test
    @DisplayName("a batch lands on the default Kafka backbone keyed by sessionId, as decodable Avro")
    void batchReachesKafkaWithTheRightKey() {
        String eventId = UUID.randomUUID().toString();
        String sessionId = "sess-" + UUID.randomUUID();

        ResponseEntity<String> response = postBatch(eventJson(eventId, sessionId));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        List<ConsumerRecord<String, byte[]>> ours = forSession(sessionId);
        assertThat(ours).hasSize(1);

        TelemetryEvent decoded = CODEC.decode(ours.get(0).value());
        // Avro decodes strings as Utf8 (a CharSequence), not String: compare as text.
        assertThat(decoded.getEventId().toString()).isEqualTo(eventId);
        assertThat(decoded.getTenantId().toString()).isEqualTo("streamflix");
        assertThat(decoded.getSessionId().toString()).isEqualTo(sessionId);
    }

    @Test
    @DisplayName("all events of one session land on one partition, in submission order")
    void sameSessionSamePartition() {
        String sessionId = "sess-" + UUID.randomUUID();
        List<String> ids = List.of(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString());

        postBatch(eventJson(ids.get(0), sessionId), eventJson(ids.get(1), sessionId), eventJson(ids.get(2), sessionId));

        List<ConsumerRecord<String, byte[]>> ours = forSession(sessionId);
        assertThat(ours).hasSize(3);
        Set<Integer> partitionsUsed =
                ours.stream().map(ConsumerRecord::partition).collect(Collectors.toSet());
        assertThat(partitionsUsed).hasSize(1);
        assertThat(ours.stream()
                        .map(r -> CODEC.decode(r.value()).getEventId().toString())
                        .toList())
                .containsExactlyElementsOf(ids);
    }

    @Test
    @DisplayName("the record timestamp is the event's occurredAt, so Kafka Streams gets event time for free")
    void recordTimestampIsEventTime() {
        String sessionId = "sess-" + UUID.randomUUID();

        postBatch(eventJson(UUID.randomUUID().toString(), sessionId));

        ConsumerRecord<String, byte[]> record = forSession(sessionId).get(0);
        // The event is from January, the test runs later: if the broker had stamped append
        // time instead, these would differ by months.
        assertThat(record.timestamp()).isEqualTo(OCCURRED_AT.toEpochMilli());
    }

    @Test
    @DisplayName("a client retry of the same batch publishes each event once")
    void retriedBatchPublishesOnce() {
        String eventId = UUID.randomUUID().toString();
        String sessionId = "sess-" + UUID.randomUUID();
        String body = eventJson(eventId, sessionId);

        assertThat(postBatch(body).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(postBatch(body).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        assertThat(forSession(sessionId)).hasSize(1);
    }

    @Test
    @DisplayName("the producer is configured for idempotence and acks=all, the producer half of no loss / no duplicate")
    void producerDeliveryGuarantees() {
        Map<String, Object> config = kafkaTemplate.getProducerFactory().getConfigurationProperties();

        assertThat(String.valueOf(config.get("enable.idempotence"))).isEqualTo("true");
        assertThat(String.valueOf(config.get("acks"))).isEqualTo("all");
    }
}

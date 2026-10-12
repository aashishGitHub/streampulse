/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streampulse.contracts.codec.AvroBinaryCodec;
import com.streampulse.contracts.v1.DeviceType;
import com.streampulse.contracts.v1.EventType;
import com.streampulse.contracts.v1.MetricAggregate;
import com.streampulse.contracts.v1.MetricName;
import com.streampulse.contracts.v1.TelemetryEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The real application against a real broker, with {@code processing.guarantee=exactly_once_v2}
 * exactly as in application.yml. TopologyTestDriver proves the logic; this proves the wiring
 * and that the transactional configuration actually works on a broker.
 *
 * <p>One partition per topic, so there is one task and stream time is deterministic. With
 * several, a window only closes when ITS partition sees a later event: the quiet-partition
 * limit recorded in ADR-0012.
 *
 * <p>Does not prove recovery: nothing here kills the processor mid-window.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class StreamProcessorIT {

    private static final String RAW = "telemetry.raw.v1";
    private static final String AGG = "telemetry.agg.v1";
    private static final String DLT = "telemetry.raw.v1.dlt";
    private static final AvroBinaryCodec<TelemetryEvent> EVENTS = AvroBinaryCodec.of(TelemetryEvent.class);
    private static final AvroBinaryCodec<MetricAggregate> AGGREGATES = AvroBinaryCodec.of(MetricAggregate.class);

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));

    // Under build/, not @TempDir: JUnit deletes a @TempDir when the class finishes, but Spring closes
    // the context (and flushes RocksDB) later, so the flush fails on a directory that is gone.
    static final Path stateDir =
            Path.of("build", "streams-it-state", UUID.randomUUID().toString());

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.streams.state-dir", () -> stateDir.toString());
        // A single broker cannot satisfy the default replication factor of 1 for the transaction
        // log unless told; the container sets the broker side, this keeps internal topics at 1.
        registry.add("spring.kafka.streams.replication-factor", () -> 1);
    }

    @BeforeAll
    static void createTopics() throws Exception {
        try (var admin = AdminClient.create(Map.<String, Object>of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(
                            new NewTopic(RAW, 1, (short) 1),
                            new NewTopic(AGG, 1, (short) 1),
                            new NewTopic(DLT, 1, (short) 1)))
                    .all()
                    .get();
        }
    }

    private static TelemetryEvent event(String eventId, String session, Instant at, EventType type) {
        return TelemetryEvent.newBuilder()
                .setEventId(eventId)
                .setTenantId("streamflix")
                .setSessionId(session)
                .setTitleId("title-1")
                .setDeviceType(DeviceType.SMART_TV)
                .setRegion("us-east-1")
                .setOccurredAt(at)
                .setReceivedAt(at.plusMillis(50))
                .setType(type)
                .setPayload(null)
                .build();
    }

    private static void send(KafkaProducer<String, byte[]> producer, String key, byte[] value, Instant at)
            throws Exception {
        producer.send(new ProducerRecord<>(RAW, null, at.toEpochMilli(), key, value))
                .get();
    }

    private static KafkaProducer<String, byte[]> producer() {
        var props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        return new KafkaProducer<>(props);
    }

    private static List<ConsumerRecord<String, byte[]>> readAll(String topic) {
        var props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        // Mirrors what a downstream consumer of a transactional topic must use: without it,
        // records from aborted transactions are visible.
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        try (var consumer = new KafkaConsumer<String, byte[]>(props)) {
            var tp = new TopicPartition(topic, 0);
            consumer.assign(List.of(tp));
            consumer.seekToBeginning(List.of(tp));
            var out = new ArrayList<ConsumerRecord<String, byte[]>>();
            consumer.poll(Duration.ofMillis(500)).forEach(out::add);
            return out;
        }
    }

    @Test
    @DisplayName(
            "real broker, exactly_once_v2: one final record per metric, duplicates collapsed, garbage dead-lettered")
    void endToEnd() throws Exception {
        // A window comfortably in the past, aligned to the 10 s boundary, and well inside the
        // validator's future-skew limit.
        long epoch = Instant.now().minusSeconds(120).getEpochSecond();
        Instant window = Instant.ofEpochSecond(epoch - (epoch % 10));
        String duplicate = UUID.randomUUID().toString();
        TelemetryEvent first = event(duplicate, "s-1", window.plusSeconds(1), EventType.HEARTBEAT);

        try (var producer = producer()) {
            send(producer, "s-1", EVENTS.encode(first), window.plusSeconds(1));
            send(producer, "s-1", EVENTS.encode(first), window.plusSeconds(1)); // retry duplicate
            send(
                    producer,
                    "s-1",
                    EVENTS.encode(event(UUID.randomUUID().toString(), "s-1", window.plusSeconds(2), EventType.ERROR)),
                    window.plusSeconds(2));
            send(
                    producer,
                    "s-2",
                    EVENTS.encode(
                            event(UUID.randomUUID().toString(), "s-2", window.plusSeconds(3), EventType.HEARTBEAT)),
                    window.plusSeconds(3));
            send(producer, "s-bad", "garbage".getBytes(StandardCharsets.UTF_8), window.plusSeconds(3));
            // Moves stream time past window end + grace, closing the window.
            send(
                    producer,
                    "tick",
                    EVENTS.encode(
                            event(UUID.randomUUID().toString(), "tick", window.plusSeconds(16), EventType.HEARTBEAT)),
                    window.plusSeconds(16));
        }

        await().atMost(Duration.ofSeconds(60))
                .untilAsserted(() -> assertThat(readAll(AGG)).hasSize(2));

        List<MetricAggregate> out =
                readAll(AGG).stream().map(r -> AGGREGATES.decode(r.value())).toList();
        MetricAggregate viewers = out.stream()
                .filter(a -> a.getMetric() == MetricName.CONCURRENT_VIEWERS)
                .findFirst()
                .orElseThrow();
        assertThat(viewers.getWindowStart()).isEqualTo(window);
        assertThat(viewers.getValue()).isEqualTo(2.0);
        // 3 distinct events: the retried duplicate counted once.
        assertThat(viewers.getSampleCount()).isEqualTo(3);

        List<ConsumerRecord<String, byte[]>> dead = readAll(DLT);
        assertThat(dead).hasSize(1);
        assertThat(dead.get(0).value()).isEqualTo("garbage".getBytes(StandardCharsets.UTF_8));
        assertThat(new String(dead.get(0).headers().lastHeader("dlt-reason").value(), StandardCharsets.UTF_8))
                .isEqualTo("UNDECODABLE");
    }
}

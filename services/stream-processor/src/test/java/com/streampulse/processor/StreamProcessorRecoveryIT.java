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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.KafkaStreams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * REQ-SP-11: a processor that dies mid-window neither loses the window nor publishes it twice.
 *
 * <p>"Dies" is a closed Spring context. What survives is only what Kafka holds: the state
 * directory is deleted before the restart, so the open window can come back only from the
 * changelog topics (the aggregate store and the suppression buffer) and the committed offsets.
 * Of those, the suppression buffer's changelog is the load-bearing one: it holds the open window's
 * last value, and the aggregate store is only read again when a new event for that window arrives.
 *
 * <p>Each test gets its own application id and topic names on the shared broker. Stream time,
 * committed offsets and changelogs are per application id, so one test's leftovers cannot make
 * another pass or fail.
 */
@Testcontainers
class StreamProcessorRecoveryIT {

    private static final AvroBinaryCodec<TelemetryEvent> EVENTS = AvroBinaryCodec.of(TelemetryEvent.class);
    private static final AvroBinaryCodec<MetricAggregate> AGGREGATES = AvroBinaryCodec.of(MetricAggregate.class);
    private static final AtomicInteger SCENARIOS = new AtomicInteger();

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));

    private record Scenario(String applicationId, String raw, String agg, String dlt, Path stateDir, Instant window) {

        static Scenario create() {
            String id = "recovery-" + SCENARIOS.incrementAndGet() + "-" + UUID.randomUUID();
            // Aligned to the 10 s window boundary, in the past so the validator's future-skew
            // limit is irrelevant, and recent enough to be a plausible event time.
            long epoch = Instant.now().minusSeconds(120).getEpochSecond();
            return new Scenario(
                    id,
                    "telemetry.raw.v1." + id,
                    "telemetry.agg.v1." + id,
                    "telemetry.raw.v1.dlt." + id,
                    Path.of("build", "streams-recovery-state", id),
                    Instant.ofEpochSecond(epoch - (epoch % 10)));
        }

        TopicPartition rawPartition() {
            return new TopicPartition(raw, 0);
        }

        TopicPartition aggPartition() {
            return new TopicPartition(agg, 0);
        }
    }

    private record Running(ConfigurableApplicationContext context, KafkaStreams streams) {}

    private final List<ConfigurableApplicationContext> started = new ArrayList<>();

    @AfterEach
    void closeLeftoverContexts() {
        started.forEach(ConfigurableApplicationContext::close);
    }

    private static void createTopics(Scenario s) throws Exception {
        try (Admin admin = Admin.create(Map.<String, Object>of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            // One partition per topic: one task, so stream time is deterministic (ADR-0012).
            admin.createTopics(List.of(
                            new NewTopic(s.raw(), 1, (short) 1),
                            new NewTopic(s.agg(), 1, (short) 1),
                            new NewTopic(s.dlt(), 1, (short) 1)))
                    .all()
                    .get();
        }
    }

    private Running start(Scenario s) {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(StreamProcessorApplication.class)
                .run(
                        "--server.port=0",
                        "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                        "--spring.kafka.streams.application-id=" + s.applicationId(),
                        "--spring.kafka.streams.state-dir=" + s.stateDir(),
                        "--spring.kafka.streams.replication-factor=1",
                        "--streampulse.processor.raw-topic=" + s.raw(),
                        "--streampulse.processor.aggregate-topic=" + s.agg(),
                        "--streampulse.processor.raw-dlt-topic=" + s.dlt());
        started.add(context);
        KafkaStreams streams = context.getBean(StreamsBuilderFactoryBean.class).getKafkaStreams();
        assertThat(streams).as("Kafka Streams instance of the started context").isNotNull();
        return new Running(context, streams);
    }

    /** The "crash": closing the context closes Kafka Streams, which must be fully down before a restart. */
    private static void stop(Running running) {
        running.context().close();
        assertThat(running.streams().state()).isEqualTo(KafkaStreams.State.NOT_RUNNING);
    }

    private static void deleteStateDir(Scenario s) throws IOException {
        if (Files.exists(s.stateDir())) {
            try (var paths = Files.walk(s.stateDir())) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
        }
        assertThat(s.stateDir())
                .as("local state must be gone so recovery can only come from Kafka")
                .doesNotExist();
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

    private static KafkaProducer<String, byte[]> producer() {
        var props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        return new KafkaProducer<>(props);
    }

    private static void send(Scenario s, String key, TelemetryEvent event) throws Exception {
        try (var producer = producer()) {
            producer.send(new ProducerRecord<>(
                            s.raw(), null, event.getOccurredAt().toEpochMilli(), key, EVENTS.encode(event)))
                    .get();
        }
    }

    /** Window W: a heartbeat sent twice (producer retry), an error, and a heartbeat from a second session. */
    private static void sendWindowEvents(Scenario s) throws Exception {
        Instant w = s.window();
        TelemetryEvent first = event(UUID.randomUUID().toString(), "s-1", w.plusSeconds(1), EventType.HEARTBEAT);
        send(s, "s-1", first);
        send(s, "s-1", first);
        send(s, "s-1", event(UUID.randomUUID().toString(), "s-1", w.plusSeconds(2), EventType.ERROR));
        send(s, "s-2", event(UUID.randomUUID().toString(), "s-2", w.plusSeconds(3), EventType.HEARTBEAT));
    }

    /** Moves stream time to {@code at}; lands in the window starting at {@code at} rounded down to 10 s. */
    private static void sendTick(Scenario s, Instant at) throws Exception {
        send(s, "tick", event(UUID.randomUUID().toString(), "tick", at, EventType.HEARTBEAT));
    }

    private static boolean consumedEverything(Scenario s) {
        try (Admin admin = Admin.create(Map.<String, Object>of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            long end = admin.listOffsets(Map.of(s.rawPartition(), OffsetSpec.latest()))
                    .partitionResult(s.rawPartition())
                    .get()
                    .offset();
            Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(s.applicationId())
                    .partitionsToOffsetAndMetadata()
                    .get();
            OffsetAndMetadata atRaw = committed.get(s.rawPartition());
            return atRaw != null && atRaw.offset() == end;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Every committed record on the aggregate topic, read until the last stable offset. */
    private static List<MetricAggregate> publishedAggregates(Scenario s) {
        var props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        // Without it records from aborted transactions would be visible, hiding a duplicate-on-restart bug.
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        try (var consumer = new KafkaConsumer<String, byte[]>(props)) {
            TopicPartition tp = s.aggPartition();
            consumer.assign(List.of(tp));
            consumer.seekToBeginning(List.of(tp));
            long end = consumer.endOffsets(List.of(tp)).get(tp);
            var out = new ArrayList<MetricAggregate>();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (consumer.position(tp) < end && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(200)).forEach(r -> out.add(AGGREGATES.decode(r.value())));
            }
            return out;
        }
    }

    private static List<MetricAggregate> forWindow(List<MetricAggregate> all, Instant windowStart) {
        return all.stream().filter(a -> a.getWindowStart().equals(windowStart)).toList();
    }

    private static MetricAggregate only(List<MetricAggregate> aggregates, MetricName metric) {
        List<MetricAggregate> matching =
                aggregates.stream().filter(a -> a.getMetric() == metric).toList();
        assertThat(matching).as("records for %s", metric).hasSize(1);
        return matching.get(0);
    }

    private static void assertWindowAggregates(List<MetricAggregate> windowRecords) {
        assertThat(windowRecords).hasSize(2);
        MetricAggregate viewers = only(windowRecords, MetricName.CONCURRENT_VIEWERS);
        assertThat(viewers.getValue()).isEqualTo(2.0);
        // 3 distinct events: the retried duplicate counted once.
        assertThat(viewers.getSampleCount()).isEqualTo(3);
        MetricAggregate errors = only(windowRecords, MetricName.ERROR_RATE);
        assertThat(errors.getValue()).isEqualTo(1.0 / 3.0);
        assertThat(errors.getSampleCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("REQ-SP-11: crash with the window open, local state deleted: restart emits it once, with the pre-crash"
            + " events")
    void openWindowSurvivesCrashAndIsPublishedOnceAfterRestart() throws Exception {
        Scenario s = Scenario.create();
        createTopics(s);

        Running first = start(s);
        sendWindowEvents(s);
        await().atMost(Duration.ofSeconds(60)).until(() -> consumedEverything(s));
        stop(first);

        assertThat(publishedAggregates(s))
                .as("the window is still open, nothing may be published")
                .isEmpty();
        deleteStateDir(s);

        Running second = start(s);
        try {
            sendTick(s, s.window().plusSeconds(16));

            await().atMost(Duration.ofSeconds(60))
                    .untilAsserted(() -> assertThat(forWindow(publishedAggregates(s), s.window()))
                            .hasSize(2));

            List<MetricAggregate> all = publishedAggregates(s);
            assertThat(all)
                    .as("only window W closed; the tick's own window is still open")
                    .hasSize(2);
            assertWindowAggregates(forWindow(all, s.window()));
        } finally {
            stop(second);
        }
    }

    @Test
    @DisplayName("REQ-SP-11: crash after the window was published, local state deleted: restart does not publish it"
            + " again")
    void publishedWindowIsNotRepublishedAfterRestart() throws Exception {
        Scenario s = Scenario.create();
        createTopics(s);
        Instant tickWindow = s.window().plusSeconds(10);

        Running first = start(s);
        sendWindowEvents(s);
        sendTick(s, s.window().plusSeconds(16));
        await().atMost(Duration.ofSeconds(60))
                .untilAsserted(() -> assertThat(forWindow(publishedAggregates(s), s.window()))
                        .hasSize(2));
        stop(first);
        deleteStateDir(s);

        Running second = start(s);
        try {
            // Closes the tick's own window: its two records prove the restarted processor consumed
            // and published past the point where a re-emission of W would have happened.
            sendTick(s, s.window().plusSeconds(40));

            await().atMost(Duration.ofSeconds(60))
                    .untilAsserted(() -> assertThat(forWindow(publishedAggregates(s), tickWindow))
                            .hasSize(2));

            List<MetricAggregate> all = publishedAggregates(s);
            assertWindowAggregates(forWindow(all, s.window()));
            assertThat(all).as("W once, the tick window once, nothing else").hasSize(4);
        } finally {
            stop(second);
        }
    }
}

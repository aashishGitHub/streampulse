/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.processor.adapter.streams;

import static org.assertj.core.api.Assertions.assertThat;

import com.streampulse.contracts.codec.AvroBinaryCodec;
import com.streampulse.contracts.v1.DeviceType;
import com.streampulse.contracts.v1.EventType;
import com.streampulse.contracts.v1.MetricAggregate;
import com.streampulse.contracts.v1.MetricName;
import com.streampulse.contracts.v1.TelemetryEvent;
import com.streampulse.processor.domain.EventValidator;
import com.streampulse.processor.domain.ProcessorMetrics;
import com.streampulse.processor.domain.RejectionReason;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.test.TestRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The whole topology, in memory, with no broker.
 *
 * <p>Window 1 is [12:00:00, 12:00:10). With a 5 s grace it closes when STREAM TIME reaches
 * 12:00:15. Stream time only advances when a later record arrives, so most tests pipe a
 * "tick" record after 12:00:15 to close the window; without it nothing is ever emitted,
 * which is itself one of the tests.
 */
class TelemetryTopologyTest {

    private static final String RAW = "telemetry.raw.v1";
    private static final String AGG = "telemetry.agg.v1";
    private static final String DLT = "telemetry.raw.v1.dlt";

    private static final Instant T0 = Instant.parse("2026-01-15T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0.plus(Duration.ofMinutes(10)), ZoneOffset.UTC);

    private static final AvroBinaryCodec<TelemetryEvent> EVENTS = AvroBinaryCodec.of(TelemetryEvent.class);
    private static final AvroBinaryCodec<MetricAggregate> AGGREGATES = AvroBinaryCodec.of(MetricAggregate.class);

    private final RecordingMetrics metrics = new RecordingMetrics();

    private TopologyTestDriver driver;
    private TestInputTopic<String, byte[]> input;
    private TestOutputTopic<String, byte[]> aggregates;
    private TestOutputTopic<String, byte[]> deadLetters;

    @BeforeEach
    void start(@TempDir Path stateDir) {
        var config = new TelemetryTopology.Config(RAW, AGG, DLT, Duration.ofSeconds(10), Duration.ofSeconds(5));
        var validator = new EventValidator(CLOCK, Duration.ofMinutes(5));

        var props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "telemetry-topology-test");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:1234");
        props.put(StreamsConfig.STATE_DIR_CONFIG, stateDir.toString());
        // No record cache: with one, a window's updates are held until the next commit and
        // the test would assert on timing the topology does not promise.
        props.put(StreamsConfig.STATESTORE_CACHE_MAX_BYTES_CONFIG, 0);

        driver = new TopologyTestDriver(TelemetryTopology.build(config, validator, CLOCK, metrics), props);
        input = driver.createInputTopic(RAW, new StringSerializer(), new ByteArraySerializer());
        aggregates = driver.createOutputTopic(AGG, new StringDeserializer(), new ByteArrayDeserializer());
        deadLetters = driver.createOutputTopic(DLT, new StringDeserializer(), new ByteArrayDeserializer());
    }

    @AfterEach
    void stop() {
        driver.close();
    }

    // ---------------------------------------------------------------- helpers

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static TelemetryEvent event(String eventId, String session, String region, Instant at, EventType type) {
        return TelemetryEvent.newBuilder()
                .setEventId(eventId)
                .setTenantId("streamflix")
                .setSessionId(session)
                .setTitleId("title-1")
                .setDeviceType(DeviceType.SMART_TV)
                .setRegion(region)
                .setOccurredAt(at)
                .setReceivedAt(at.plusMillis(50))
                .setType(type)
                .setPayload(null)
                .build();
    }

    private void pipe(TelemetryEvent e) {
        input.pipeInput(e.getSessionId().toString(), EVENTS.encode(e), e.getOccurredAt());
    }

    private void heartbeat(String session, Instant at) {
        pipe(event(id(), session, "us-east-1", at, EventType.HEARTBEAT));
    }

    private void error(String session, Instant at) {
        pipe(event(id(), session, "us-east-1", at, EventType.ERROR));
    }

    /** Advances stream time past window 1's close (12:00:15) without touching window 1. */
    private void tickPastWindowOne() {
        heartbeat("tick", T0.plusSeconds(16));
    }

    private List<MetricAggregate> published() {
        return aggregates.readRecordsToList().stream()
                .map(r -> AGGREGATES.decode(r.value()))
                .toList();
    }

    private static MetricAggregate metric(List<MetricAggregate> all, MetricName name) {
        return all.stream().filter(a -> a.getMetric() == name).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("no output before the window closes, even after the next window has started")
    void noOutputBeforeWindowCloses() {
        heartbeat("s-1", T0.plusSeconds(1));
        heartbeat("s-2", T0.plusSeconds(3));
        // 12:00:12 is in window 2 but stream time is still before 12:00:15.
        heartbeat("s-3", T0.plusSeconds(12));

        assertThat(aggregates.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("once the window and its grace have passed, exactly one final record per metric")
    void emitsOneFinalAggregatePerMetricAfterGrace() {
        heartbeat("s-1", T0.plusSeconds(1));
        error("s-1", T0.plusSeconds(2));
        heartbeat("s-2", T0.plusSeconds(3));
        tickPastWindowOne();

        List<MetricAggregate> out = published();

        assertThat(out).hasSize(2);
        MetricAggregate viewers = metric(out, MetricName.CONCURRENT_VIEWERS);
        assertThat(viewers.getValue()).isEqualTo(2.0);
        assertThat(viewers.getSampleCount()).isEqualTo(3);
        assertThat(viewers.getIsFinal()).isTrue();
        assertThat(viewers.getWindowStart()).isEqualTo(T0);
        assertThat(viewers.getWindowEndExclusive()).isEqualTo(T0.plusSeconds(10));
        assertThat(viewers.getTenantId().toString()).isEqualTo("streamflix");
        assertThat(viewers.getTitleId().toString()).isEqualTo("title-1");
        assertThat(viewers.getRegion().toString()).isEqualTo("us-east-1");
        assertThat(viewers.getComputedAt()).isEqualTo(CLOCK.instant());
        assertThat(metric(out, MetricName.ERROR_RATE).getValue()).isEqualTo(1.0 / 3.0);
    }

    @Test
    @DisplayName("the record key is tenant:title:region:metric:windowStart, so a consumer can upsert by it")
    void recordKeyIdentifiesTheWindowAndMetric() {
        heartbeat("s-1", T0.plusSeconds(1));
        tickPastWindowOne();

        List<String> keys =
                aggregates.readRecordsToList().stream().map(TestRecord::key).toList();

        assertThat(keys)
                .containsExactlyInAnyOrder(
                        "streamflix:title-1:us-east-1:CONCURRENT_VIEWERS:" + T0.toEpochMilli(),
                        "streamflix:title-1:us-east-1:ERROR_RATE:" + T0.toEpochMilli());
    }

    @Test
    @DisplayName("an out-of-order event inside the grace period is counted in its own window")
    void outOfOrderWithinGraceIsCounted() {
        heartbeat("s-1", T0.plusSeconds(9));
        heartbeat("s-3", T0.plusSeconds(12));
        // Arrives after a later event, but stream time (12:00:12) is before 12:00:15.
        heartbeat("s-2", T0.plusSeconds(4));
        tickPastWindowOne();

        MetricAggregate viewers = metric(published(), MetricName.CONCURRENT_VIEWERS);

        assertThat(viewers.getWindowStart()).isEqualTo(T0);
        assertThat(viewers.getValue()).isEqualTo(2.0);
        assertThat(viewers.getSampleCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("an event after the grace period is dropped, never re-emitted, and the drop is counted")
    void lateAfterGraceIsDroppedNotEmitted() {
        heartbeat("s-1", T0.plusSeconds(5));
        tickPastWindowOne();
        assertThat(published()).hasSize(2);

        // Stream time is now 12:00:16, past 12:00:15: window 1 is closed.
        heartbeat("s-late", T0.plusSeconds(6));
        heartbeat("tick-2", T0.plusSeconds(40));

        List<MetricAggregate> later = published();
        assertThat(later).noneMatch(a -> a.getWindowStart().equals(T0));
        assertThat(droppedRecords()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("an event id seen twice counts once")
    void duplicateEventIdCountedOnce() {
        String duplicate = id();
        TelemetryEvent e = event(duplicate, "s-1", "us-east-1", T0.plusSeconds(1), EventType.HEARTBEAT);
        pipe(e);
        pipe(e);
        heartbeat("s-2", T0.plusSeconds(2));
        tickPastWindowOne();

        MetricAggregate viewers = metric(published(), MetricName.CONCURRENT_VIEWERS);

        assertThat(viewers.getSampleCount()).isEqualTo(2);
        assertThat(metrics.duplicates).isEqualTo(1);
    }

    @Test
    @DisplayName("bytes that are not an event go to the DLT unchanged, with the reason in a header")
    void undecodableBytesGoToDltWithReasonHeader() {
        byte[] garbage = "definitely not avro".getBytes(StandardCharsets.UTF_8);

        input.pipeInput("s-1", garbage, T0.plusSeconds(1));

        TestRecord<String, byte[]> dead = deadLetters.readRecord();
        assertThat(dead.value()).isEqualTo(garbage);
        assertThat(dead.key()).isEqualTo("s-1");
        assertThat(header(dead, "dlt-reason")).isEqualTo("UNDECODABLE");
        assertThat(header(dead, "dlt-original-topic")).isEqualTo(RAW);
        assertThat(aggregates.isEmpty()).isTrue();
        assertThat(metrics.rejections).containsExactly(RejectionReason.UNDECODABLE);
    }

    @Test
    @DisplayName("a decodable but invalid event goes to the DLT and is excluded from every aggregate")
    void invalidEventGoesToDlt() {
        TelemetryEvent bad = event("not-a-uuid", "s-bad", "us-east-1", T0.plusSeconds(1), EventType.HEARTBEAT);
        pipe(bad);
        heartbeat("s-1", T0.plusSeconds(2));
        tickPastWindowOne();

        TestRecord<String, byte[]> dead = deadLetters.readRecord();
        assertThat(dead.value()).isEqualTo(EVENTS.encode(bad));
        assertThat(header(dead, "dlt-reason")).isEqualTo("INVALID_EVENT_ID");
        MetricAggregate viewers = metric(published(), MetricName.CONCURRENT_VIEWERS);
        assertThat(viewers.getSampleCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("an event dated beyond the permitted skew is rejected, not allowed to push stream time forward")
    void futureEventIsRejected() {
        pipe(event(id(), "s-future", "us-east-1", CLOCK.instant().plus(Duration.ofHours(1)), EventType.HEARTBEAT));

        assertThat(header(deadLetters.readRecord(), "dlt-reason")).isEqualTo("EVENT_TIME_IN_FUTURE");
    }

    @Test
    @DisplayName("an event type this version does not know is counted in the totals but is not an error")
    void unknownTypeCountsTowardTotalsOnly() {
        heartbeat("s-1", T0.plusSeconds(1));
        pipe(event(id(), "s-2", "us-east-1", T0.plusSeconds(2), EventType.EVENT_UNKNOWN));
        tickPastWindowOne();

        List<MetricAggregate> out = published();

        assertThat(metric(out, MetricName.CONCURRENT_VIEWERS).getSampleCount()).isEqualTo(2);
        assertThat(metric(out, MetricName.ERROR_RATE).getValue()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("different regions are separate aggregates")
    void regionsAreSeparate() {
        heartbeat("s-1", T0.plusSeconds(1));
        pipe(event(id(), "s-2", "eu-west-1", T0.plusSeconds(2), EventType.HEARTBEAT));
        tickPastWindowOne();

        List<MetricAggregate> out = published();

        assertThat(out).extracting(a -> a.getRegion().toString()).contains("us-east-1", "eu-west-1");
        assertThat(out.stream().filter(a -> a.getMetric() == MetricName.CONCURRENT_VIEWERS))
                .hasSize(2);
    }

    // ----------------------------------------------------------- test doubles

    private static String header(TestRecord<String, byte[]> record, String name) {
        Header h = record.headers().lastHeader(name);
        assertThat(h).as("header %s", name).isNotNull();
        return new String(h.value(), StandardCharsets.UTF_8);
    }

    private double droppedRecords() {
        return driver.metrics().entrySet().stream()
                .filter(e -> e.getKey().name().equals("dropped-records-total"))
                .mapToDouble(e -> ((Number) e.getValue().metricValue()).doubleValue())
                .sum();
    }

    private static final class RecordingMetrics implements ProcessorMetrics {
        final List<RejectionReason> rejections = new ArrayList<>();
        int duplicates;

        @Override
        public void rejected(RejectionReason reason) {
            rejections.add(reason);
        }

        @Override
        public void duplicateDropped() {
            duplicates++;
        }
    }
}

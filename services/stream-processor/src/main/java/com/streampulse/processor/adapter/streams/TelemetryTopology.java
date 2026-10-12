/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.processor.adapter.streams;

import com.streampulse.contracts.codec.AvroBinaryCodec;
import com.streampulse.contracts.v1.MetricAggregate;
import com.streampulse.contracts.v1.MetricName;
import com.streampulse.contracts.v1.TelemetryEvent;
import com.streampulse.processor.domain.AggregateKey;
import com.streampulse.processor.domain.EventValidator;
import com.streampulse.processor.domain.Metric;
import com.streampulse.processor.domain.MetricMath;
import com.streampulse.processor.domain.MetricValue;
import com.streampulse.processor.domain.ProcessorMetrics;
import com.streampulse.processor.domain.WindowAccumulator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Branched;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Named;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.Suppressed;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.state.Stores;
import org.apache.kafka.streams.state.WindowStore;

/**
 * Raw telemetry in, one final aggregate per metric per window out. See
 * {@code specs/03-stream-processor/design.md} for the flow and the failure table.
 *
 * <p>A pure function of its arguments: no Spring, no wall clock except the injected one. That is
 * what lets {@code TopologyTestDriver} run every behaviour in memory.
 *
 * <p>Every operator and store is named. Kafka Streams derives internal topic and store names
 * from the operator's position in the topology, so an unnamed operator renames its repartition
 * topic and state when an unrelated operator is added, orphaning the existing state.
 */
public final class TelemetryTopology {

    public record Config(
            String rawTopic, String aggregateTopic, String rawDltTopic, Duration windowSize, Duration grace) {

        Duration dedupRange() {
            return windowSize.plus(grace);
        }
    }

    private static final String AGGREGATE_STORE = "window-aggregates";

    private TelemetryTopology() {}

    public static Topology build(Config config, EventValidator validator, Clock clock, ProcessorMetrics metrics) {
        var builder = new StreamsBuilder();
        define(builder, config, validator, clock, metrics);
        return builder.build();
    }

    /** Adds the topology to {@code builder} and returns the aggregate stream. */
    public static KStream<String, MetricAggregate> define(
            StreamsBuilder builder, Config config, EventValidator validator, Clock clock, ProcessorMetrics metrics) {
        var events = AvroBinaryCodec.of(TelemetryEvent.class);
        var eventSerde = new AvroSerde<>(events);
        var aggregateSerde = new AvroSerde<>(AvroBinaryCodec.of(MetricAggregate.class));

        // Retention twice the dedup range: comfortably past the point where a duplicate could
        // still matter, small enough that the store stays bounded.
        Duration retention = config.dedupRange().multipliedBy(2);
        builder.addStateStore(Stores.windowStoreBuilder(
                Stores.persistentWindowStore(EventIdDeduplicator.STORE, retention, retention, false),
                Serdes.String(),
                Serdes.Long()));

        KStream<String, Decoded> decoded = builder.stream(
                        config.rawTopic(), Consumed.with(Serdes.String(), Serdes.ByteArray()))
                .mapValues(raw -> Decoded.of(raw, events, validator), Named.as("decode-and-validate"));

        Map<String, KStream<String, Decoded>> routes = decoded.split(Named.as("route-"))
                .branch((key, d) -> d.rejection() != null, Branched.as("rejected"))
                .defaultBranch(Branched.as("accepted"));

        routes.get("route-rejected")
                .processValues(() -> new DltRecorder(config.rawTopic(), metrics), Named.as("dlt-recorder"))
                .to(config.rawDltTopic(), Produced.with(Serdes.String(), Serdes.ByteArray()));

        KStream<String, MetricAggregate> aggregates = routes.get("route-accepted")
                .mapValues(Decoded::event, Named.as("unwrap"))
                .processValues(
                        () -> new EventIdDeduplicator(config.dedupRange(), metrics),
                        Named.as("dedup-by-event-id"),
                        EventIdDeduplicator.STORE)
                .selectKey(
                        (key, event) -> AggregateKey.of(ObservationMapper.toObservation(event))
                                .asString(),
                        Named.as("key-by-aggregate"))
                .groupByKey(Grouped.with("by-aggregate-key", Serdes.String(), eventSerde))
                .windowedBy(TimeWindows.ofSizeAndGrace(config.windowSize(), config.grace()))
                .aggregate(
                        WindowAccumulator::empty,
                        (key, event, acc) -> acc.add(ObservationMapper.toObservation(event)),
                        Materialized.<String, WindowAccumulator, WindowStore<Bytes, byte[]>>as(AGGREGATE_STORE)
                                .withKeySerde(Serdes.String())
                                .withValueSerde(new WindowAccumulatorSerde()))
                // Emit only when the window AND its grace period have closed. Without this, every
                // late event re-emits the window and a downstream consumer sees a stream of
                // revisions instead of one answer. The cost: output waits for stream time to
                // advance, so a quiet partition holds its last window open.
                .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded())
                        .withName("close-windows"))
                .toStream()
                .flatMap((windowed, acc) -> toAggregates(windowed, acc, clock), Named.as("to-aggregates"));

        aggregates.to(config.aggregateTopic(), Produced.with(Serdes.String(), aggregateSerde));
        return aggregates;
    }

    private static List<KeyValue<String, MetricAggregate>> toAggregates(
            Windowed<String> windowed, WindowAccumulator acc, Clock clock) {
        AggregateKey key = acc.key();
        long start = windowed.window().start();
        long end = windowed.window().end();
        Instant computedAt = clock.instant();

        var out = new ArrayList<KeyValue<String, MetricAggregate>>();
        for (MetricValue value : MetricMath.compute(acc)) {
            MetricAggregate aggregate = MetricAggregate.newBuilder()
                    .setTenantId(key.tenantId())
                    .setTitleId(key.titleId())
                    .setRegion(key.region())
                    .setMetric(toAvro(value.metric()))
                    .setWindowStart(Instant.ofEpochMilli(start))
                    .setWindowEndExclusive(Instant.ofEpochMilli(end))
                    .setValue(value.value())
                    .setSampleCount(value.sampleCount())
                    .setComputedAt(computedAt)
                    .setIsFinal(true)
                    .build();
            out.add(KeyValue.pair(key.asString() + ":" + value.metric().name() + ":" + start, aggregate));
        }
        return out;
    }

    private static MetricName toAvro(Metric metric) {
        return switch (metric) {
            case CONCURRENT_VIEWERS -> MetricName.CONCURRENT_VIEWERS;
            case ERROR_RATE -> MetricName.ERROR_RATE;
        };
    }
}

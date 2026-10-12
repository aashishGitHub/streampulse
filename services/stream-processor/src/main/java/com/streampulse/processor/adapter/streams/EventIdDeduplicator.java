/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.processor.adapter.streams;

import com.streampulse.contracts.v1.TelemetryEvent;
import com.streampulse.processor.domain.ProcessorMetrics;
import java.time.Duration;
import java.util.Locale;
import org.apache.kafka.streams.processor.api.FixedKeyProcessor;
import org.apache.kafka.streams.processor.api.FixedKeyProcessorContext;
import org.apache.kafka.streams.processor.api.FixedKeyRecord;
import org.apache.kafka.streams.state.WindowStore;
import org.apache.kafka.streams.state.WindowStoreIterator;

/**
 * Drops an event whose id has already been seen.
 *
 * <p>Runs BEFORE the repartition, while the key is still the session id. Ingest keys by session
 * and a retried event carries the same session, so a duplicate is always on the same partition
 * as its original and a store local to the task sees both. After the repartition the keys
 * differ, and the duplicate could land on another task.
 *
 * <p>Dedups only within {@code range} of the event's own time. A duplicate arriving beyond the
 * window's grace period would be dropped by the window anyway, so remembering it longer would
 * cost state for no benefit.
 */
final class EventIdDeduplicator implements FixedKeyProcessor<String, TelemetryEvent, TelemetryEvent> {

    static final String STORE = "event-id-dedup";

    private final long rangeMillis;
    private final ProcessorMetrics metrics;
    private FixedKeyProcessorContext<String, TelemetryEvent> context;
    private WindowStore<String, Long> seen;

    EventIdDeduplicator(Duration range, ProcessorMetrics metrics) {
        this.rangeMillis = range.toMillis();
        this.metrics = metrics;
    }

    @Override
    public void init(FixedKeyProcessorContext<String, TelemetryEvent> context) {
        this.context = context;
        this.seen = context.getStateStore(STORE);
    }

    @Override
    public void process(FixedKeyRecord<String, TelemetryEvent> record) {
        // EventId normalises to lower case, so ids differing only by case are the same event.
        String id = record.value().getEventId().toString().toLowerCase(Locale.ROOT);
        long at = record.timestamp();

        try (WindowStoreIterator<Long> earlier = seen.fetch(id, at - rangeMillis, at + rangeMillis)) {
            if (earlier.hasNext()) {
                metrics.duplicateDropped();
                return;
            }
        }
        seen.put(id, at, at);
        context.forward(record);
    }
}

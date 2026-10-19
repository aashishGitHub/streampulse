/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.adapter.in.kafka;

import com.streampulse.alert.application.AlertProcessor;
import com.streampulse.alert.domain.AlertMetrics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

/**
 * Decode, evaluate and deliver, then acknowledge: in that order, on the listener thread.
 *
 * <p>An exception from {@link AlertProcessor#process} propagates without acknowledging, so the
 * container redelivers the record; the engine's idempotence makes the repeat harmless and the outbox
 * finishes any half-done delivery. A record that cannot be decoded is acknowledged and skipped: it
 * would fail identically on every retry, and the sink writer already dead-letters the same bytes
 * from the same topic.
 */
public class AggregateListener {

    public static final String ID = "alert-aggregates";

    private static final Logger log = LoggerFactory.getLogger(AggregateListener.class);

    private final AggregateDecoder decoder;
    private final AlertProcessor processor;
    private final AlertMetrics metrics;

    public AggregateListener(AggregateDecoder decoder, AlertProcessor processor, AlertMetrics metrics) {
        this.decoder = decoder;
        this.processor = processor;
        this.metrics = metrics;
    }

    // idIsGroup=false: by default the listener id becomes the group id and would replace
    // spring.kafka.consumer.group-id. autoStartup=false: the rule book must be loaded first.
    @KafkaListener(id = ID, idIsGroup = false, topics = "${streampulse.alerts.aggregate-topic}", autoStartup = "false")
    public void onRecord(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        switch (decoder.decode(record.value())) {
            case AggregateDecoder.Decoded.Valid valid -> processor.process(valid.observation());
            case AggregateDecoder.Decoded.Rejected rejected -> {
                metrics.rejected("aggregate", rejected.reason());
                log.warn(
                        "skipping aggregate {}-{}@{} key={}: {}",
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        record.key(),
                        rejected.reason());
            }
        }
        ack.acknowledge();
    }
}

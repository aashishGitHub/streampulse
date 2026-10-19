/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.adapter.in.kafka;

import com.streampulse.feed.application.DeadLetterPort;
import com.streampulse.feed.application.FanOutService;
import com.streampulse.feed.application.FeedMetrics;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

/**
 * Poll in, fan out, then acknowledge.
 *
 * <pre>
 *   listener thread:  decode ─▶ fan out (Redis + Postgres reads) ─▶ ... for each record ─▶ ack
 * </pre>
 *
 * The consumer is not thread-safe and the work is synchronous, so the acknowledgement is on the
 * listener thread by construction. Throwing instead of acknowledging makes the container
 * redeliver the whole poll; every store write is idempotent by event id, so that is harmless.
 */
public class WatchEventListener {

    private static final Logger log = LoggerFactory.getLogger(WatchEventListener.class);

    private final FanOutService fanOut;
    private final WatchEventDecoder decoder;
    private final DeadLetterPort deadLetters;
    private final FeedMetrics metrics;

    public WatchEventListener(
            FanOutService fanOut, WatchEventDecoder decoder, DeadLetterPort deadLetters, FeedMetrics metrics) {
        this.fanOut = fanOut;
        this.decoder = decoder;
        this.deadLetters = deadLetters;
        this.metrics = metrics;
    }

    // idIsGroup=false: by default the listener id becomes the consumer group id, which would
    // silently replace spring.kafka.consumer.group-id and orphan the group's committed offsets.
    @KafkaListener(id = "watch-fanout", idIsGroup = false, topics = "${streampulse.feed.watch-topic}")
    public void onBatch(List<ConsumerRecord<String, byte[]>> records, Acknowledgment ack) {
        for (ConsumerRecord<String, byte[]> record : records) {
            switch (decoder.decode(record.value())) {
                case WatchEventDecoder.Decoded.Valid valid -> fanOut.handle(valid.watch());
                case WatchEventDecoder.Decoded.Rejected rejected -> deadLetter(record, rejected.reason());
            }
        }
        ack.acknowledge();
    }

    private void deadLetter(ConsumerRecord<String, byte[]> record, String reason) {
        // Not caught: acknowledging past a record we could not park would lose it.
        deadLetters.deadLetter(record.key(), record.value(), reason);
        metrics.deadLettered();
        log.warn("Dead-lettered {}-{}@{}: {}", record.topic(), record.partition(), record.offset(), reason);
    }
}

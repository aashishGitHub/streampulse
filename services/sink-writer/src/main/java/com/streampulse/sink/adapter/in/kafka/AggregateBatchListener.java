/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.sink.adapter.in.kafka;

import com.streampulse.sink.application.batch.BatchWriter;
import com.streampulse.sink.application.batch.BatchWriterMetrics;
import com.streampulse.sink.application.batch.DeadLetterSink;
import com.streampulse.sink.domain.AggregateRecord;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

/**
 * Poll in, durable write, then acknowledge: the heart of ADR-0013.
 *
 * <pre>
 *   listener thread:  decode ─▶ submit each ─▶ wait for all ─▶ ack      (consumer owner)
 *   flusher thread:                 batch ─▶ Postgres ─▶ Redis ─▶ complete futures
 * </pre>
 *
 * The Kafka consumer is not thread-safe, so the acknowledgement must happen on the listener
 * thread; the flusher only completes futures. Throwing instead of acknowledging makes the
 * container redeliver the whole poll, which the idempotent upsert absorbs.
 */
public class AggregateBatchListener {

    private static final Logger log = LoggerFactory.getLogger(AggregateBatchListener.class);

    private final BatchWriter<AggregateRecord> writer;
    private final AggregateDecoder decoder;
    private final DeadLetterSink<AggregateRecord> deadLetters;
    private final BatchWriterMetrics metrics;
    private final PreAckHook preAck;
    private final Duration offerTimeout;
    private final Duration pollBudget;

    public AggregateBatchListener(
            BatchWriter<AggregateRecord> writer,
            AggregateDecoder decoder,
            DeadLetterSink<AggregateRecord> deadLetters,
            BatchWriterMetrics metrics,
            PreAckHook preAck,
            Duration offerTimeout,
            Duration pollBudget) {
        this.writer = writer;
        this.decoder = decoder;
        this.deadLetters = deadLetters;
        this.metrics = metrics;
        this.preAck = preAck;
        this.offerTimeout = offerTimeout;
        this.pollBudget = pollBudget;
    }

    // idIsGroup=false: by default the listener id becomes the consumer group id, which would
    // silently replace spring.kafka.consumer.group-id and orphan the group's committed offsets.
    @KafkaListener(id = "aggregate-sink", idIsGroup = false, topics = "${streampulse.sink.aggregate-topic}")
    public void onBatch(List<ConsumerRecord<String, byte[]>> records, Acknowledgment ack) {
        // nanoTime: a budget is a duration, and wall-clock time can jump.
        long deadline = System.nanoTime() + pollBudget.toNanos();
        List<CompletableFuture<Void>> pending = new ArrayList<>(records.size());

        for (ConsumerRecord<String, byte[]> record : records) {
            switch (decoder.decode(record.key(), record.value())) {
                case AggregateDecoder.Decoded.Valid valid -> pending.add(writer.submit(valid.record(), offerTimeout));
                case AggregateDecoder.Decoded.Rejected rejected -> deadLetter(record, rejected.reason());
            }
        }

        awaitAll(pending, deadline);
        preAck.beforeAck();
        ack.acknowledge();
    }

    private void deadLetter(ConsumerRecord<String, byte[]> record, String reason) {
        try {
            deadLetters.deadLetter(AggregateRecord.undecodable(record.key(), record.value()), reason);
            metrics.deadLettered();
            log.warn("Dead-lettered {}-{}@{}: {}", record.topic(), record.partition(), record.offset(), reason);
        } catch (Exception e) {
            // Not skipped: acknowledging past a record we could not park would lose it.
            throw new SinkUnavailableException("could not dead-letter a rejected record", e);
        }
    }

    /** Waits for every future, failed or not, so nothing from this poll is still in flight when it is retried. */
    private void awaitAll(List<CompletableFuture<Void>> pending, long deadline) {
        try {
            long remaining = Math.max(0, deadline - System.nanoTime());
            CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).get(remaining, TimeUnit.NANOSECONDS);
        } catch (ExecutionException e) {
            throw new SinkUnavailableException("a record of this poll was not stored", e.getCause());
        } catch (TimeoutException e) {
            throw new SinkUnavailableException("writes did not complete within the poll budget " + pollBudget, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SinkUnavailableException("interrupted while waiting for writes", e);
        }
    }
}

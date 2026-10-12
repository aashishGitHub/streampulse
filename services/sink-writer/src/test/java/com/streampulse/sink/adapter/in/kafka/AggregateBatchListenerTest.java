/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.sink.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.streampulse.sink.application.batch.BatchSink;
import com.streampulse.sink.application.batch.BatchWriter;
import com.streampulse.sink.application.batch.BatchWriterMetrics;
import com.streampulse.sink.application.batch.DeadLetterSink;
import com.streampulse.sink.domain.AggregateRecord;
import com.streampulse.sink.support.Aggregates;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

/**
 * The listener's contract with the consumer: it acknowledges on its own thread, and only once
 * every record of the poll is durably written. A real {@link BatchWriter} with a fake sink, no
 * Kafka.
 */
class AggregateBatchListenerTest {

    private final List<AggregateRecord> written = Collections.synchronizedList(new ArrayList<>());
    private final List<AggregateRecord> deadLettered = new CopyOnWriteArrayList<>();
    private final List<Thread> ackThreads = new CopyOnWriteArrayList<>();
    private final AtomicInteger acks = new AtomicInteger();
    private volatile CountDownLatch gate = new CountDownLatch(0);
    private volatile Exception sinkFailure;
    private volatile Exception deadLetterFailure;
    private volatile RuntimeException preAckFailure;
    private BatchWriter<AggregateRecord> writer;

    private final BatchSink<AggregateRecord> sink = batch -> {
        gate.await();
        if (sinkFailure != null) {
            throw sinkFailure;
        }
        written.addAll(batch);
    };
    private final DeadLetterSink<AggregateRecord> deadLetters = (record, reason) -> {
        if (deadLetterFailure != null) {
            throw deadLetterFailure;
        }
        deadLettered.add(record);
    };
    private final Acknowledgment ack = () -> {
        acks.incrementAndGet();
        ackThreads.add(Thread.currentThread());
    };

    private AggregateBatchListener listener(Duration batchTimeout) {
        var config =
                new BatchWriter.Config(10, Duration.ofMillis(5), 100, 2, Duration.ofMillis(1), Duration.ofSeconds(5));
        writer = BatchWriter.start(config, sink, deadLetters, BatchWriterMetrics.NONE);
        return new AggregateBatchListener(
                writer,
                new AggregateDecoder(),
                deadLetters,
                BatchWriterMetrics.NONE,
                () -> {
                    if (preAckFailure != null) {
                        throw preAckFailure;
                    }
                },
                Duration.ofMillis(200),
                batchTimeout);
    }

    @AfterEach
    void tearDown() {
        gate.countDown();
        if (writer != null) {
            writer.close();
        }
    }

    private static ConsumerRecord<String, byte[]> good(String title, long offset) {
        var aggregate =
                Aggregates.avro("t", title, "r", Aggregates.WINDOW_START).build();
        return new ConsumerRecord<>(
                "telemetry.agg.v1", 0, offset, Aggregates.key(aggregate), Aggregates.bytes(aggregate));
    }

    private static ConsumerRecord<String, byte[]> garbage(long offset) {
        return new ConsumerRecord<>(
                "telemetry.agg.v1", 0, offset, "bad-key", "garbage".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("acknowledges once, after every record is written, and on the thread that called the listener")
    void acksOnlyAfterEveryRecordIsWritten() throws Exception {
        gate = new CountDownLatch(1);
        var listener = listener(Duration.ofSeconds(5));
        var done = new CompletableFuture<Thread>();
        Thread.ofPlatform().name("listener-thread").start(() -> {
            listener.onBatch(List.of(good("a", 0), good("b", 1), good("c", 2)), ack);
            done.complete(Thread.currentThread());
        });

        Thread.sleep(150);
        assertThat(acks).as("no ack while the writes are blocked").hasValue(0);
        gate.countDown();
        Thread listenerThread = done.get(2, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(acks).hasValue(1);
        assertThat(written).hasSize(3);
        assertThat(ackThreads).containsExactly(listenerThread);
        assertThat(listenerThread.getName()).isEqualTo("listener-thread");
    }

    @Test
    @DisplayName("an undecodable record is dead-lettered with its original bytes and key; the others are written")
    void undecodableRecordIsDeadLetteredAndTheRestAreWritten() {
        var listener = listener(Duration.ofSeconds(5));
        var bad = garbage(1);

        listener.onBatch(List.of(good("a", 0), bad, good("b", 2)), ack);

        assertThat(written).hasSize(2);
        assertThat(deadLettered).singleElement().satisfies(r -> {
            assertThat(r.key()).isEqualTo("bad-key");
            assertThat(r.value()).isEqualTo(bad.value());
            assertThat(r.window()).isNull();
        });
        assertThat(acks).hasValue(1);
    }

    @Test
    @DisplayName("when the store is down the listener throws and never acknowledges, so the poll is redelivered")
    void storeFailureMeansNoAck() {
        sinkFailure = new IOException("connection refused");
        var listener = listener(Duration.ofSeconds(5));

        assertThatThrownBy(() -> listener.onBatch(List.of(good("a", 0), good("b", 1)), ack))
                .isInstanceOf(SinkUnavailableException.class);

        assertThat(acks).hasValue(0);
    }

    @Test
    @DisplayName("a write that outlives the poll budget throws instead of holding the consumer past max.poll.interval")
    void budgetExceededMeansNoAck() {
        gate = new CountDownLatch(1);
        var listener = listener(Duration.ofMillis(300));

        assertThatThrownBy(() -> listener.onBatch(List.of(good("a", 0)), ack))
                .isInstanceOf(SinkUnavailableException.class)
                .hasMessageContaining("did not complete");

        assertThat(acks).hasValue(0);
    }

    @Test
    @DisplayName("an undecodable record that cannot be dead-lettered stops the poll: it is not skipped")
    void deadLetterFailureMeansNoAck() {
        deadLetterFailure = new IOException("dlt broker down");
        var listener = listener(Duration.ofSeconds(5));

        assertThatThrownBy(() -> listener.onBatch(List.of(garbage(0)), ack))
                .isInstanceOf(SinkUnavailableException.class);

        assertThat(acks).hasValue(0);
    }

    @Test
    @DisplayName("a failure after the durable write but before the ack leaves the poll unacknowledged")
    void preAckFailureMeansNoAck() {
        preAckFailure = new IllegalStateException("injected between write and ack");
        var listener = listener(Duration.ofSeconds(5));

        assertThatThrownBy(() -> listener.onBatch(List.of(good("a", 0)), ack)).isSameAs(preAckFailure);

        assertThat(written).hasSize(1);
        assertThat(acks).hasValue(0);
    }

    @Test
    @DisplayName("an empty poll is acknowledged without touching the writer")
    void emptyPollIsAcknowledged() {
        var listener = listener(Duration.ofSeconds(5));

        listener.onBatch(List.of(), ack);

        assertThat(acks).hasValue(1);
        await().untilAsserted(() -> assertThat(written).isEmpty());
    }
}

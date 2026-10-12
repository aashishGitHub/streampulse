/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.sink.application.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * The batch writer against a fake sink: no containers, tiny waits. The concurrency tests are
 * the point; they are what this class is for.
 */
class BatchWriterTest {

    private static final Duration OFFER = Duration.ofMillis(200);

    private final List<BatchWriter<String>> toClose = new ArrayList<>();
    private final FakeSink sink = new FakeSink();
    private final RecordingDeadLetters deadLetters = new RecordingDeadLetters();
    private final RecordingMetrics metrics = new RecordingMetrics();

    @AfterEach
    void closeWriters() {
        sink.release();
        toClose.forEach(BatchWriter::close);
    }

    private BatchWriter<String> writer(int batchSize, Duration maxWait, int capacity) {
        var config =
                new BatchWriter.Config(batchSize, maxWait, capacity, 3, Duration.ofMillis(1), Duration.ofSeconds(5));
        var writer = BatchWriter.start(config, sink, deadLetters, metrics);
        toClose.add(writer);
        return writer;
    }

    @Test
    @DisplayName("a batch is flushed as soon as it reaches the size threshold")
    void flushesWhenBatchSizeIsReached() {
        var writer = writer(3, Duration.ofSeconds(30), 100);

        var futures = List.of(writer.submit("a", OFFER), writer.submit("b", OFFER), writer.submit("c", OFFER));

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(futures)
                .allSatisfy(f -> assertThat(f).isCompleted()));
        assertThat(sink.batches()).containsExactly(List.of("a", "b", "c"));
    }

    @Test
    @DisplayName("a batch below the threshold is flushed once the max wait has passed")
    void flushesAfterMaxWaitWhenBatchIsNotFull() {
        var writer = writer(100, Duration.ofMillis(200), 100);

        var future = writer.submit("lonely", OFFER);

        assertThat(future).isNotDone();
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(future).isCompleted());
        assertThat(sink.batches()).containsExactly(List.of("lonely"));
    }

    @Test
    @DisplayName("a full queue rejects the submission after the offer timeout, not before and not never")
    void fullQueueRejectsWithinTheOfferTimeout() throws Exception {
        sink.blockWrites();
        var writer = writer(1, Duration.ofMillis(1), 2);
        writer.submit("in-flight", OFFER);
        sink.awaitWriteEntered();
        writer.submit("queued-1", OFFER);
        writer.submit("queued-2", OFFER);

        long start = System.nanoTime();
        var rejected = writer.submit("one-too-many", Duration.ofMillis(100));
        Duration waited = Duration.ofNanos(System.nanoTime() - start);

        assertThat(rejected).isCompletedExceptionally();
        assertThatThrownBy(rejected::get)
                .hasCauseInstanceOf(SubmissionRejectedException.class)
                .cause()
                .extracting(c -> ((SubmissionRejectedException) c).reason())
                .isEqualTo(SubmissionRejectedException.Reason.BUFFER_FULL);
        assertThat(waited).isBetween(Duration.ofMillis(90), Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("close writes everything that was accepted, without waiting out the max wait")
    void closeDrainsEverythingAccepted() {
        var writer = writer(10, Duration.ofSeconds(30), 500);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < 95; i++) {
            futures.add(writer.submit("r" + i, OFFER));
        }

        writer.close();

        assertThat(futures).allSatisfy(f -> assertThat(f).isCompleted());
        assertThat(sink.written()).hasSize(95);
    }

    @Test
    @DisplayName("close flushes a partial batch that is waiting for its max wait, and returns promptly")
    void closeFlushesAPartialBatchWithoutWaitingForMaxWait() {
        var writer = writer(100, Duration.ofSeconds(30), 100);
        var futures = List.of(writer.submit("a", OFFER), writer.submit("b", OFFER), writer.submit("c", OFFER));
        await().atMost(Duration.ofSeconds(1)).until(() -> writer.queueDepth() == 0);

        long start = System.nanoTime();
        writer.close();
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(futures).allSatisfy(f -> assertThat(f).isCompleted());
        assertThat(sink.written()).containsExactlyInAnyOrder("a", "b", "c");
        assertThat(took).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("a submission after close is a failed future, not an exception and not a silent drop")
    void submitAfterCloseFails() {
        var writer = writer(10, Duration.ofMillis(10), 10);
        writer.close();

        var future = writer.submit("late", OFFER);

        assertThat(future).isCompletedExceptionally();
        assertThatThrownBy(future::get)
                .cause()
                .isInstanceOfSatisfying(SubmissionRejectedException.class, e -> assertThat(e.reason())
                        .isEqualTo(SubmissionRejectedException.Reason.CLOSED));
    }

    /**
     * Accepted implies written, under the race that matters: submit passing the open-check while
     * close runs. A record accepted after the flusher has exited would sit in the queue forever.
     */
    @RepeatedTest(100)
    @DisplayName("every accepted record is written, however submit and close interleave")
    void acceptedImpliesWrittenUnderConcurrentSubmitAndClose() throws Exception {
        var writer = writer(8, Duration.ofMillis(1), 64);
        int threads = 48;
        int perThread = 40;
        var start = new CountDownLatch(1);
        var submitted = new CopyOnWriteArrayList<Submission>();
        var pool = new ArrayList<Thread>();
        for (int t = 0; t < threads; t++) {
            int id = t;
            pool.add(Thread.ofPlatform().name("submitter-" + t).start(() -> {
                awaitLatch(start);
                for (int i = 0; i < perThread; i++) {
                    String item = id + "-" + i;
                    submitted.add(new Submission(item, writer.submit(item, Duration.ofMillis(20))));
                }
            }));
        }
        var closer = Thread.ofPlatform().name("closer").start(() -> {
            awaitLatch(start);
            spin(ThreadLocalRandom.current().nextInt(0, 400));
            writer.close();
        });

        start.countDown();
        for (Thread t : pool) {
            t.join();
        }
        closer.join();
        writer.close();

        Set<String> written = Set.copyOf(sink.written());
        for (Submission s : submitted) {
            assertThat(s.future().isDone())
                    .as("%s must be settled once close has returned", s.item())
                    .isTrue();
            if (s.future().isCompletedExceptionally()) {
                assertThatThrownBy(s.future()::get).cause().isInstanceOf(SubmissionRejectedException.class);
            } else {
                assertThat(written).as("accepted %s must be written", s.item()).contains(s.item());
            }
        }
    }

    /**
     * The same race as above, made deterministic: the queue holds one submit between its
     * open-check and its enqueue while close() is called. close() must wait for it, because once
     * it has returned the flusher is gone and a late enqueue would never be written.
     */
    @Test
    @DisplayName("close waits for a submit that has passed the open-check, so that record is still written")
    void closeWaitsForASubmitThatIsAlreadyPastTheOpenCheck() throws Exception {
        var inOffer = new CountDownLatch(1);
        var releaseOffer = new CountDownLatch(1);
        BlockingQueue<BatchWriter.Pending<String>> stallingQueue = new ArrayBlockingQueue<>(10) {
            @Override
            public boolean offer(BatchWriter.Pending<String> e, long timeout, TimeUnit unit)
                    throws InterruptedException {
                inOffer.countDown();
                releaseOffer.await();
                return super.offer(e, timeout, unit);
            }
        };
        var config =
                new BatchWriter.Config(10, Duration.ofMillis(5), 10, 3, Duration.ofMillis(1), Duration.ofSeconds(5));
        var writer = BatchWriter.start(config, sink, deadLetters, metrics, stallingQueue);
        toClose.add(writer);
        var submitted = new CompletableFuture<CompletableFuture<Void>>();
        Thread.ofPlatform().start(() -> submitted.complete(writer.submit("late", OFFER)));
        assertThat(inOffer.await(2, TimeUnit.SECONDS)).isTrue();

        var closer = Thread.ofPlatform().name("closer").start(writer::close);
        closer.join(Duration.ofMillis(300));
        assertThat(closer.isAlive())
                .as("close must wait for the in-flight submit")
                .isTrue();
        releaseOffer.countDown();
        closer.join(Duration.ofSeconds(5));

        CompletableFuture<Void> accepted = submitted.get(2, TimeUnit.SECONDS);
        assertThat(accepted.isCompletedExceptionally())
                .as("an accepted record must not fail")
                .isFalse();
        assertThat(accepted.isDone())
                .as("accepted implies written: settled once close returned")
                .isTrue();
        assertThat(sink.written()).containsExactly("late");
    }

    @Test
    @DisplayName("a transient failure is retried and the batch then succeeds, with nothing dead-lettered")
    void transientFailureIsRetriedThenSucceeds() {
        sink.failNextWrites(2, new IOException("connection reset"));
        var writer = writer(2, Duration.ofSeconds(30), 10);

        var futures = List.of(writer.submit("a", OFFER), writer.submit("b", OFFER));

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(futures)
                .allSatisfy(f -> assertThat(f).isCompleted()));
        assertThat(sink.writeAttempts()).isEqualTo(3);
        assertThat(sink.written()).containsExactlyInAnyOrder("a", "b");
        assertThat(deadLetters.records()).isEmpty();
    }

    @Test
    @DisplayName("a poison record is isolated to the dead-letter sink while the rest of its batch is written")
    void poisonRecordIsIsolatedToTheDeadLetterSinkWhileOthersAreWritten() {
        sink.rejectDeterministically("poison");
        var writer = writer(4, Duration.ofSeconds(30), 10);

        var futures = List.of(
                writer.submit("a", OFFER),
                writer.submit("poison", OFFER),
                writer.submit("b", OFFER),
                writer.submit("c", OFFER));

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(futures)
                .allSatisfy(f -> assertThat(f).isCompleted()));
        assertThat(sink.written()).containsExactlyInAnyOrder("a", "b", "c");
        assertThat(deadLetters.records()).containsExactly("poison");
        assertThat(deadLetters.reasons().get(0)).contains("rejected");
        assertThat(metrics.deadLetters()).hasValue(1);
    }

    @Test
    @DisplayName("when every record fails the store is down: futures fail and nothing is dead-lettered")
    void outageFailsFuturesWithoutDeadLettering() {
        sink.failAllWrites(new IOException("connection refused"));
        var writer = writer(3, Duration.ofSeconds(30), 10);

        var futures = List.of(writer.submit("a", OFFER), writer.submit("b", OFFER), writer.submit("c", OFFER));

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(futures)
                .allSatisfy(f -> assertThat(f).isCompletedExceptionally()));
        assertThat(futures).allSatisfy(f -> assertThatThrownBy(f::get)
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(BatchWriteFailedException.class)
                .hasRootCauseInstanceOf(IOException.class));
        assertThat(deadLetters.records()).isEmpty();
        assertThat(metrics.deadLetters()).hasValue(0);
    }

    @Test
    @DisplayName("a dead-letter sink that is down fails only the poison record, not its healthy neighbours")
    void deadLetterSinkFailureFailsOnlyThatRecord() {
        sink.rejectDeterministically("poison");
        deadLetters.failWith(new IOException("dlt broker down"));
        var writer = writer(3, Duration.ofSeconds(30), 10);

        var good = writer.submit("a", OFFER);
        var poison = writer.submit("poison", OFFER);
        var good2 = writer.submit("b", OFFER);

        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(poison).isCompletedExceptionally());
        assertThat(good).isCompleted();
        assertThat(good2).isCompleted();
    }

    @Test
    @DisplayName("queue depth is reported while records wait, and flush latency once a batch is written")
    void metricsReportQueueDepthAndFlushLatency() throws Exception {
        sink.blockWrites();
        sink.delay(Duration.ofMillis(30));
        var writer = writer(1, Duration.ofMillis(1), 10);
        writer.submit("in-flight", OFFER);
        sink.awaitWriteEntered();
        writer.submit("waiting-1", OFFER);
        writer.submit("waiting-2", OFFER);

        IntSupplier depth = metrics.queueDepth();
        assertThat(depth).isNotNull();
        assertThat(depth.getAsInt()).isEqualTo(2);

        sink.release();
        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(sink.written()).hasSize(3));
        assertThat(depth.getAsInt()).isZero();
        assertThat(metrics.flushLatencies()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(metrics.flushLatencies())
                .allSatisfy(l -> assertThat(l).isGreaterThanOrEqualTo(Duration.ofMillis(25)));
    }

    private record Submission(String item, CompletableFuture<Void> future) {}

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void spin(int iterations) {
        for (int i = 0; i < iterations; i++) {
            Thread.onSpinWait();
        }
    }

    /** Records what it was asked to write; can be told to block, delay or fail. */
    private static final class FakeSink implements BatchSink<String> {
        private final List<List<String>> batches = new CopyOnWriteArrayList<>();
        private final List<String> written = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger attempts = new AtomicInteger();
        private volatile CountDownLatch gate = new CountDownLatch(0);
        private final CountDownLatch entered = new CountDownLatch(1);
        private volatile Duration delay = Duration.ZERO;
        private volatile int failuresLeft;
        private volatile Exception transientFailure;
        private volatile Exception permanentFailure;
        private volatile String poison;

        void blockWrites() {
            gate = new CountDownLatch(1);
        }

        void release() {
            gate.countDown();
        }

        void awaitWriteEntered() throws InterruptedException {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        }

        void delay(Duration d) {
            delay = d;
        }

        void failNextWrites(int n, Exception cause) {
            failuresLeft = n;
            transientFailure = cause;
        }

        void failAllWrites(Exception cause) {
            permanentFailure = cause;
        }

        void rejectDeterministically(String record) {
            poison = record;
        }

        @Override
        public void write(List<String> batch) throws Exception {
            attempts.incrementAndGet();
            entered.countDown();
            gate.await();
            if (!delay.isZero()) {
                Thread.sleep(delay);
            }
            if (permanentFailure != null) {
                throw permanentFailure;
            }
            if (failuresLeft > 0) {
                failuresLeft--;
                throw transientFailure;
            }
            if (poison != null && batch.contains(poison)) {
                throw new IllegalArgumentException("rejected: " + poison);
            }
            batches.add(List.copyOf(batch));
            written.addAll(batch);
        }

        List<List<String>> batches() {
            return batches;
        }

        List<String> written() {
            synchronized (written) {
                return new ArrayList<>(written);
            }
        }

        int writeAttempts() {
            return attempts.get();
        }
    }

    private static final class RecordingDeadLetters implements DeadLetterSink<String> {
        private final List<String> records = new CopyOnWriteArrayList<>();
        private final List<String> reasons = new CopyOnWriteArrayList<>();
        private volatile Exception failure;

        void failWith(Exception e) {
            failure = e;
        }

        @Override
        public void deadLetter(String record, String reason) throws Exception {
            if (failure != null) {
                throw failure;
            }
            records.add(record);
            reasons.add(reason);
        }

        List<String> records() {
            return records;
        }

        List<String> reasons() {
            return reasons;
        }
    }

    private static final class RecordingMetrics implements BatchWriterMetrics {
        private volatile IntSupplier depth;
        private final List<Duration> latencies = new CopyOnWriteArrayList<>();
        private final AtomicInteger deadLetters = new AtomicInteger();

        @Override
        public void bindQueueDepth(IntSupplier queueDepth) {
            this.depth = queueDepth;
        }

        @Override
        public void flushed(int records, Duration latency) {
            latencies.add(latency);
        }

        @Override
        public void flushFailed() {}

        @Override
        public void deadLettered() {
            deadLetters.incrementAndGet();
        }

        IntSupplier queueDepth() {
            return depth;
        }

        List<Duration> flushLatencies() {
            return latencies;
        }

        AtomicInteger deadLetters() {
            return deadLetters;
        }
    }
}

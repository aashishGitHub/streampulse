/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.sink.application.batch;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Groups submitted records into batches and writes each batch with one call to a {@link
 * BatchSink}. The guarantee: a record whose {@link #submit} did not fail is written, or its
 * future fails; it is never lost silently.
 *
 * <pre>
 *   submit() ──▶ bounded queue ──▶ one "batch-flusher" thread ──▶ sink.write(batch)
 *      │                                   │
 *      └─ future completes after ◀─────────┘   on failure: retry, then one record at a time
 * </pre>
 *
 * Framework-free on purpose: no Kafka, Spring or JDBC type appears here, so every interleaving
 * can be tested with a fake sink.
 */
public final class BatchWriter<T> implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BatchWriter.class);

    /** How often an idle flusher looks at the closed flag. Bounds the idle part of close(). */
    private static final long IDLE_POLL_MILLIS = 50;

    /** How often a flusher holding a partial batch looks at the closed flag. */
    private static final long CLOSE_CHECK_NANOS = TimeUnit.MILLISECONDS.toNanos(5);

    public record Config(
            int maxBatchSize,
            Duration maxWait,
            int queueCapacity,
            int flushAttempts,
            Duration retryBackoff,
            Duration closeTimeout) {

        public Config {
            if (maxBatchSize < 1 || queueCapacity < 1 || flushAttempts < 1) {
                throw new IllegalArgumentException("batch size, queue capacity and attempts must be at least 1");
            }
            Objects.requireNonNull(maxWait);
            Objects.requireNonNull(retryBackoff);
            Objects.requireNonNull(closeTimeout);
        }
    }

    record Pending<T>(T value, CompletableFuture<Void> future) {}

    private record Failed<T>(Pending<T> pending, Exception cause) {}

    private final Config config;
    private final BatchSink<T> sink;
    private final DeadLetterSink<T> deadLetters;
    private final BatchWriterMetrics metrics;
    private final BlockingQueue<Pending<T>> queue;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile boolean closed;
    private Thread flusher;

    private BatchWriter(
            Config config,
            BatchSink<T> sink,
            DeadLetterSink<T> deadLetters,
            BatchWriterMetrics metrics,
            BlockingQueue<Pending<T>> queue) {
        this.config = config;
        this.sink = sink;
        this.deadLetters = deadLetters;
        this.metrics = metrics;
        this.queue = queue;
    }

    /** A factory, not a constructor: starting a thread from a constructor lets {@code this} escape. */
    public static <T> BatchWriter<T> start(
            Config config, BatchSink<T> sink, DeadLetterSink<T> deadLetters, BatchWriterMetrics metrics) {
        return start(config, sink, deadLetters, metrics, new ArrayBlockingQueue<>(config.queueCapacity()));
    }

    /**
     * Package-private so a test can hand in a queue that stalls {@code offer}: the only way to
     * hold a submit between its open-check and its enqueue, deterministically, is to own the queue.
     */
    static <T> BatchWriter<T> start(
            Config config,
            BatchSink<T> sink,
            DeadLetterSink<T> deadLetters,
            BatchWriterMetrics metrics,
            BlockingQueue<Pending<T>> queue) {
        var writer = new BatchWriter<T>(config, sink, deadLetters, metrics, queue);
        metrics.bindQueueDepth(writer.queue::size);
        // Daemon: the lifecycle bean owns shutdown, and a forgotten writer must not keep a JVM alive.
        writer.flusher = Thread.ofPlatform().name("batch-flusher").daemon(true).unstarted(writer::runFlusher);
        writer.flusher.start();
        return writer;
    }

    public int queueDepth() {
        return queue.size();
    }

    /**
     * The returned future completes when the record's batch is durably written, and fails if the
     * buffer stayed full for {@code offerTimeout} (backpressure), the writer is closed, or the
     * sink could not store it. Never blocks longer than {@code offerTimeout}.
     */
    public CompletableFuture<Void> submit(T value, Duration offerTimeout) {
        var future = new CompletableFuture<Void>();
        // The read lock makes "is it open?" and "enqueue" one step with respect to close().
        // Without it: submit sees open, close() sets closed, the flusher sees an empty queue and
        // exits, then submit enqueues: an accepted record that nothing will ever write.
        // ReentrantReadWriteLock rather than synchronized: synchronized pins a virtual thread's
        // carrier while it blocks, and submit blocks in offer().
        lock.readLock().lock();
        try {
            if (closed) {
                return rejected(SubmissionRejectedException.Reason.CLOSED);
            }
            if (!queue.offer(new Pending<>(value, future), offerTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
                return rejected(SubmissionRejectedException.Reason.BUFFER_FULL);
            }
            return future;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return rejected(SubmissionRejectedException.Reason.INTERRUPTED);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Stops intake, writes everything already accepted, and waits for the flusher, up to the
     * configured close timeout. Idempotent.
     */
    @Override
    public void close() {
        lock.writeLock().lock();
        try {
            closed = true;
        } finally {
            lock.writeLock().unlock();
        }
        try {
            flusher.join(config.closeTimeout());
            if (flusher.isAlive()) {
                log.error("batch-flusher did not finish within {}; interrupting", config.closeTimeout());
                flusher.interrupt();
                flusher.join(Duration.ofSeconds(1));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static CompletableFuture<Void> rejected(SubmissionRejectedException.Reason reason) {
        return CompletableFuture.failedFuture(new SubmissionRejectedException(reason));
    }

    private void runFlusher() {
        try {
            while (true) {
                Pending<T> first = queue.poll(IDLE_POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (first == null) {
                    if (closed && queue.isEmpty()) {
                        return;
                    }
                    continue;
                }
                flush(collect(first));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            failLeftovers();
        }
    }

    /** Takes records until the batch is full or maxWait has passed since the first one. */
    private List<Pending<T>> collect(Pending<T> first) throws InterruptedException {
        var batch = new ArrayList<Pending<T>>(config.maxBatchSize());
        batch.add(first);
        // nanoTime, not the injected Clock: this is a duration, and wall-clock time can jump
        // (NTP step, manual change) while a monotonic clock cannot.
        long deadline = System.nanoTime() + config.maxWait().toNanos();
        queue.drainTo(batch, config.maxBatchSize() - batch.size());
        while (batch.size() < config.maxBatchSize() && !closed) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            // Wait in short slices, not for the whole remaining time: nothing wakes a poll when
            // close() sets the flag, and a partial batch must not sit out its max wait at shutdown.
            Pending<T> next = queue.poll(Math.min(remaining, CLOSE_CHECK_NANOS), TimeUnit.NANOSECONDS);
            if (next != null) {
                batch.add(next);
                queue.drainTo(batch, config.maxBatchSize() - batch.size());
            }
        }
        return batch;
    }

    private void flush(List<Pending<T>> batch) {
        List<T> values = batch.stream().map(Pending::value).toList();
        Exception last = null;
        for (int attempt = 1; attempt <= config.flushAttempts(); attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                failAll(batch, new InterruptedException("flusher interrupted"));
                return;
            }
            long start = System.nanoTime();
            try {
                sink.write(values);
                metrics.flushed(batch.size(), Duration.ofNanos(System.nanoTime() - start));
                batch.forEach(p -> p.future().complete(null));
                return;
            } catch (Exception e) {
                last = e;
                metrics.flushFailed();
                log.warn(
                        "Batch of {} failed (attempt {}/{}): {}",
                        batch.size(),
                        attempt,
                        config.flushAttempts(),
                        e.toString());
                if (attempt < config.flushAttempts() && !backOff(attempt)) {
                    failAll(batch, e);
                    return;
                }
            }
        }
        isolate(batch, last);
    }

    private boolean backOff(int attempt) {
        try {
            Thread.sleep(config.retryBackoff().multipliedBy(1L << (attempt - 1)));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * One record at a time. If some succeed the store is up, so the ones that failed are the
     * faulty records. If none succeed we cannot tell a bad record from a dead store, and dead-
     * lettering healthy records because the store was down would lose data: treat it as an outage.
     */
    private void isolate(List<Pending<T>> batch, Exception batchFailure) {
        var failed = new ArrayList<Failed<T>>();
        for (Pending<T> pending : batch) {
            long start = System.nanoTime();
            try {
                sink.write(List.of(pending.value()));
                metrics.flushed(1, Duration.ofNanos(System.nanoTime() - start));
                pending.future().complete(null);
            } catch (Exception e) {
                metrics.flushFailed();
                failed.add(new Failed<>(pending, e));
            }
        }
        if (failed.size() == batch.size()) {
            failAll(batch, batchFailure);
            return;
        }
        for (Failed<T> f : failed) {
            deadLetter(f);
        }
    }

    private void deadLetter(Failed<T> failed) {
        try {
            deadLetters.deadLetter(failed.pending().value(), failed.cause().toString());
            metrics.deadLettered();
            failed.pending().future().complete(null);
        } catch (Exception e) {
            log.error("Dead-lettering failed; the record stays unhandled", e);
            failed.pending()
                    .future()
                    .completeExceptionally(new BatchWriteFailedException("dead-letter sink failed", e));
        }
    }

    private void failAll(List<Pending<T>> batch, Exception cause) {
        var failure = new BatchWriteFailedException("sink unavailable", cause);
        batch.forEach(p -> p.future().completeExceptionally(failure));
    }

    /** Only reached if the flusher dies or is interrupted: nothing left in the queue may hang. */
    private void failLeftovers() {
        var leftovers = new ArrayList<Pending<T>>();
        queue.drainTo(leftovers);
        var failure = new SubmissionRejectedException(SubmissionRejectedException.Reason.CLOSED);
        leftovers.forEach(p -> p.future().completeExceptionally(failure));
    }
}

/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.loadgen;

import java.io.IOException;
import java.time.Duration;
import java.util.OptionalDouble;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/**
 * Flow: one scheduler thread builds a batch at each fixed-rate tick, takes an in-flight permit,
 * and hands the POST to a virtual thread. Latency is measured from the moment the request is
 * sent, so when the server is slow the offered rate drops rather than the latency growing; that
 * is why offered events/s and max send lag are reported next to the percentiles.
 */
public final class Runner {

    private static final Duration RETRY_AFTER_FALLBACK = Duration.ofSeconds(1);
    private static final Duration RETRY_AFTER_CAP = Duration.ofSeconds(30);

    private final LoadGenConfig config;
    private final TokenProvider tokens;
    private final IngestClient ingest;
    private final BatchBuilder batches;
    private final int maxInFlight;
    private final boolean honourRetryAfter;

    private final LongAdder batchesSent = new LongAdder();
    private final LongAdder eventsAccepted = new LongAdder();
    private final LongAdder status202 = new LongAdder();
    private final LongAdder status429 = new LongAdder();
    private final LongAdder status503 = new LongAdder();
    private final LongAdder statusOther = new LongAdder();
    private final LongAdder transportFailures = new LongAdder();
    private final AtomicLong pausedUntil = new AtomicLong(Long.MIN_VALUE);
    private final LongAccumulator maxLagNanos = new LongAccumulator(Math::max, 0);

    public Runner(
            LoadGenConfig config, TokenProvider tokens, IngestClient ingest, BatchBuilder batches, int maxInFlight) {
        this(config, tokens, ingest, batches, maxInFlight, true);
    }

    /** A saturation search passes false: obeying Retry-After would lower the offered rate it is measuring. */
    public Runner(
            LoadGenConfig config,
            TokenProvider tokens,
            IngestClient ingest,
            BatchBuilder batches,
            int maxInFlight,
            boolean honourRetryAfter) {
        this.config = config;
        this.tokens = tokens;
        this.ingest = ingest;
        this.batches = batches;
        this.maxInFlight = maxInFlight;
        this.honourRetryAfter = honourRetryAfter;
    }

    public RunSummary run() throws IOException, InterruptedException {
        tokens.current();

        long interval = Pacing.intervalNanos(config.rate(), config.batch());
        LatencyRecorder latencies = new LatencyRecorder(
                (int) Pacing.maxBatches(config.rate(), config.durationSeconds(), config.batch()) + 2);
        Semaphore permits = new Semaphore(maxInFlight);

        long start = System.nanoTime();
        long deadline = start + Duration.ofSeconds(config.durationSeconds()).toNanos();
        long scheduled = start;

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            while (true) {
                long target = Pacing.nextDue(scheduled, pausedUntil.get());
                if (target >= deadline) {
                    break;
                }
                long now = System.nanoTime();
                if (now < target) {
                    LockSupport.parkNanos(target - now);
                    if (Thread.interrupted()) {
                        throw new InterruptedException();
                    }
                    continue;
                }
                byte[] body = batches.buildJson(config.batch());
                permits.acquire();
                maxLagNanos.accumulate(System.nanoTime() - target);
                batchesSent.increment();
                executor.submit(() -> send(body, latencies, permits));
                scheduled = target + interval;
            }
        }
        long wallNanos = System.nanoTime() - start;

        long[] sorted = latencies.sortedSnapshot();
        return new RunSummary(
                Duration.ofNanos(wallNanos),
                batchesSent.sum(),
                batchesSent.sum() * config.batch(),
                eventsAccepted.sum(),
                status202.sum(),
                status429.sum(),
                status503.sum(),
                statusOther.sum(),
                transportFailures.sum(),
                Percentiles.of(sorted, 50),
                Percentiles.of(sorted, 95),
                Percentiles.of(sorted, 99),
                latencies.dropped(),
                Duration.ofNanos(maxLagNanos.get()),
                OptionalDouble.empty(),
                "");
    }

    private void send(byte[] body, LatencyRecorder latencies, Semaphore permits) {
        try {
            IngestClient.Outcome outcome = ingest.post(body, tokens.current(), config.batch());
            switch (outcome.status()) {
                case 202 -> {
                    status202.increment();
                    eventsAccepted.add(outcome.accepted());
                    latencies.record(outcome.latencyNanos());
                }
                case 429 -> {
                    status429.increment();
                    pause(outcome.retryAfter());
                }
                case 503 -> {
                    status503.increment();
                    pause(outcome.retryAfter());
                }
                case IngestClient.TRANSPORT_FAILURE -> transportFailures.increment();
                default -> statusOther.increment();
            }
        } catch (IOException e) {
            transportFailures.increment();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            permits.release();
        }
    }

    private void pause(String retryAfterHeader) {
        if (!honourRetryAfter) {
            return;
        }
        long until =
                System.nanoTime() + Pacing.retryAfterNanos(retryAfterHeader, RETRY_AFTER_FALLBACK, RETRY_AFTER_CAP);
        pausedUntil.accumulateAndGet(until, Math::max);
    }
}

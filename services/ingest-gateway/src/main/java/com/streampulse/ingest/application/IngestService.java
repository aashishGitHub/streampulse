/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.application;

import com.streampulse.ingest.domain.BackpressureException;
import com.streampulse.ingest.domain.EventPublisher;
import com.streampulse.ingest.domain.IdempotencyStore;
import com.streampulse.ingest.domain.IngestCommand;
import com.streampulse.ingest.domain.IngestResult;
import com.streampulse.ingest.domain.QuotaExceededException;
import com.streampulse.ingest.domain.RateLimiter;
import com.streampulse.platform.domain.EventId;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * The ingest use case: deduplicate, publish, report.
 *
 * <h2>This class has no Spring annotations</h2>
 *
 * It is constructed explicitly in {@code config/IngestConfig}, which means it can be unit
 * tested with plain {@code new} and fakes — no context, no reflection, milliseconds. The
 * ArchUnit rule that keeps the domain framework-free is what makes this stay true.
 *
 * <h2>Flow of control</h2>
 *
 * <pre>
 *   ingest(command)
 *     │
 *     ├─ 1. ACQUIRE a permit ────────────► none within permitWait? → BackpressureException → 503
 *     │      (bounds in-flight batches)
 *     │
 *     ├─ 2. CLAIM event ids atomically ──► store unavailable? → fail OPEN, publish anyway
 *     │      (Redis SET NX EX)             all already claimed? → return, publish nothing
 *     │
 *     ├─ 3. PUBLISH the new ones ────────► wait for the broker ack. 202 must not be
 *     │                                     returned before the data is durable.
 *     │
 *     └─ 4. RELEASE the permit (finally)
 * </pre>
 *
 * <h2>Why a Semaphore rather than a queue</h2>
 *
 * A bounded queue still accepts work and defers the decision; a semaphore <em>refuses</em> it
 * immediately and tells the client when to come back. Under a spike that is the difference
 * between a fast, honest rejection and a slow death with a full heap.
 *
 * <p>It also works correctly with virtual threads: a virtual thread blocked in
 * {@code tryAcquire} unmounts from its carrier, so waiting costs memory rather than an OS
 * thread. This is exactly the shape of backpressure gRPC applies with {@code request(n)}
 * and Reactor applies with its request protocol — same idea, three APIs.
 */
public class IngestService implements IngestUseCase {

    private final EventPublisher publisher;
    private final IdempotencyStore idempotencyStore;
    private final RateLimiter rateLimiter;
    private final IngestMetrics metrics;

    /**
     * How long a client should wait after a 429. One second is a starting point, not a
     * measurement: the client MUST add jitter, or every throttled client returns at the
     * same instant and the next spike is worse than the first.
     */
    private static final Duration RETRY_AFTER_QUOTA = Duration.ofSeconds(1);

    /** Bounds how many batches may be in flight. THE backpressure mechanism (REQ-IG-19). */
    private final Semaphore inFlight;

    private final Duration permitWait;
    private final boolean failOpenOnIdempotencyError;

    public IngestService(
            EventPublisher publisher,
            IdempotencyStore idempotencyStore,
            RateLimiter rateLimiter,
            IngestMetrics metrics,
            int maxInFlightBatches,
            Duration permitWait,
            boolean failOpenOnIdempotencyError) {
        this.publisher = publisher;
        this.idempotencyStore = idempotencyStore;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
        // Fair = true: permits are granted in arrival order. Slightly slower than an unfair
        // semaphore, but it prevents a request being starved indefinitely under sustained
        // load, which would show up as a long tail nobody can explain.
        this.inFlight = new Semaphore(maxInFlightBatches, true);
        this.permitWait = permitWait;
        this.failOpenOnIdempotencyError = failOpenOnIdempotencyError;
    }

    @Override
    public IngestResult ingest(IngestCommand command) {
        // ---- 0. QUOTA, before anything expensive ----
        // Checked first, deliberately. A tenant over its limit should cost us a single
        // Redis round trip, not a permit, a dedup claim and a publish. Admission control
        // that runs late is admission control that does not protect you.
        if (!rateLimiter.tryConsume(command.tenantId(), command.size())) {
            metrics.recordRejectedForQuota(command.size());
            throw new QuotaExceededException(
                    "tenant " + command.tenantId() + " exceeded its ingest quota", RETRY_AFTER_QUOTA);
        }

        boolean acquired;
        try {
            acquired = inFlight.tryAcquire(permitWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // Restore the flag. Swallowing an interrupt makes a thread un-cancellable, and
            // during shutdown that is exactly the thread you need to stop.
            Thread.currentThread().interrupt();
            throw new BackpressureException("interrupted while waiting for capacity", permitWait);
        }

        if (!acquired) {
            metrics.recordRejectedForCapacity(command.size());
            throw new BackpressureException(
                    "ingest saturated: no capacity within " + permitWait.toMillis() + "ms", permitWait);
        }

        try {
            return doIngest(command);
        } finally {
            // ALWAYS in a finally. A leaked permit is a permanent capacity reduction that
            // only shows up as gradually rising latency days later.
            inFlight.release();
        }
    }

    private IngestResult doIngest(IngestCommand command) {
        List<EventId> ids = command.events().stream()
                .map(IngestCommand.IngestEvent::eventId)
                .toList();

        IdempotencyStore.ClaimResult claim = idempotencyStore.claimAll(command.tenantId(), ids);

        // ---- The store is down. Deliberate decision: fail OPEN. ----
        // At-least-once with some duplicates beats losing telemetry. Downstream aggregation
        // tolerates duplicates; a gap in the data is unrecoverable.
        // For a payments system the correct choice would be the opposite. See ADR-0007.
        if (!claim.storeAvailable()) {
            if (!failOpenOnIdempotencyError) {
                throw new IllegalStateException("idempotency store unavailable and fail-open is disabled");
            }
            metrics.recordIdempotencyUnavailable();
            publishAndWait(command, command.events());
            return IngestResult.degraded(command.size());
        }

        if (claim.claimed().isEmpty()) {
            // Everything was seen before — the client retried a batch we already have.
            // Still a 202: from the client's point of view the outcome is identical, and
            // telling them otherwise would invite a pointless retry.
            metrics.recordDuplicates(claim.alreadySeen().size());
            return IngestResult.of(0, claim.alreadySeen().size());
        }

        Set<EventId> toPublish = new HashSet<>(claim.claimed());
        List<IngestCommand.IngestEvent> newEvents = command.events().stream()
                .filter(e -> toPublish.contains(e.eventId()))
                .toList();

        publishAndWait(command, newEvents);

        metrics.recordPublished(newEvents.size());
        metrics.recordDuplicates(claim.alreadySeen().size());
        return IngestResult.of(newEvents.size(), claim.alreadySeen().size());
    }

    /**
     * Publishes and waits for the broker acknowledgement.
     *
     * <p>Blocking here is correct, not a compromise. {@code 202 Accepted} promises the events
     * are durable; returning before the ack would make that a lie the client acts on by
     * discarding its local copy. On a virtual thread the block is cheap — the thread unmounts
     * rather than occupying an OS thread.
     */
    private void publishAndWait(IngestCommand command, List<IngestCommand.IngestEvent> events) {
        var scoped = new EventPublisher.TenantScopedEvents(command.tenantId().value(), events);
        try {
            publisher.publishAll(scoped).join();
        } catch (CompletionException e) {
            // Unwrap: CompletionException's cause is the real failure, and leaving it wrapped
            // makes every stack trace one level less useful.
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            metrics.recordPublishFailure();
            throw new IllegalStateException("failed to publish batch to the event stream", cause);
        }
    }

    /** Exposed for the readiness probe and for the Phase 8 saturation metric. */
    public int availablePermits() {
        return inFlight.availablePermits();
    }
}

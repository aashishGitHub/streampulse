/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.application;

import com.streampulse.ingest.domain.EventPublisher;
import com.streampulse.ingest.domain.IdempotencyStore;
import com.streampulse.ingest.domain.IngestCommand;
import com.streampulse.ingest.domain.RateLimiter;
import com.streampulse.platform.domain.EventId;
import com.streampulse.platform.domain.TenantId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hand-written test doubles.
 *
 * <h2>Why fakes and not Mockito</h2>
 *
 * These have real behaviour. {@link FakeIdempotencyStore} is genuinely idempotent — the
 * second claim of an id returns "already seen" because a {@code ConcurrentHashMap.Set}
 * actually behaves that way, including under concurrency.
 *
 * <p>A mock would have to be <em>told</em> that, which means the test asserts what you
 * believed rather than what happens. {@code when(store.claim(any())).thenReturn(true)} makes
 * the duplicate path untestable, and the concurrency test in particular would prove nothing.
 *
 * <p>See interview-qa/14-testing.md Q3.
 */
final class FakeAdapters {

    private FakeAdapters() {}

    /** Records what was published, so tests can assert on it. */
    static final class FakePublisher implements EventPublisher {

        final List<IngestCommand.IngestEvent> published = new ArrayList<>();
        final AtomicInteger calls = new AtomicInteger();

        /** Set to make publishing fail, for the error path. */
        volatile RuntimeException failWith;

        /** Set to block publishing until released — used by the backpressure test. */
        volatile CountDownLatch blockUntil;

        @Override
        public synchronized CompletableFuture<Void> publishAll(TenantScopedEvents events) {
            calls.incrementAndGet();
            if (blockUntil != null) {
                try {
                    blockUntil.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failWith != null) {
                return CompletableFuture.failedFuture(failWith);
            }
            published.addAll(events.events());
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * In-memory idempotency store with genuine atomic claim semantics.
     *
     * <p>{@code Set.add} on a {@code ConcurrentHashMap.newKeySet()} is atomic and returns
     * false if the element was already present — exactly the contract Redis {@code SET NX}
     * provides. That is what makes the concurrent-duplicate test meaningful.
     */
    static final class FakeIdempotencyStore implements IdempotencyStore {

        private final Set<String> seen = ConcurrentHashMap.newKeySet();

        /** Set true to simulate Redis being down, for the fail-open path. */
        volatile boolean unavailable;

        @Override
        public ClaimResult claimAll(TenantId tenant, List<EventId> eventIds) {
            if (unavailable) {
                return ClaimResult.unavailable();
            }
            List<EventId> claimed = new ArrayList<>();
            List<EventId> already = new ArrayList<>();
            for (EventId id : eventIds) {
                // Namespaced by tenant, like the real key layout — so the cross-tenant test
                // exercises the same property the production key does.
                if (seen.add(tenant.value() + ":" + id.value())) {
                    claimed.add(id);
                } else {
                    already.add(id);
                }
            }
            return ClaimResult.of(claimed, already);
        }

        @Override
        public void release(TenantId tenant, List<EventId> eventIds) {
            if (releaseFails) {
                return; // models a Redis DEL that did not land
            }
            eventIds.forEach(id -> seen.remove(tenant.value() + ":" + id.value()));
        }

        /** Set true to model the release itself failing, so the retry is still swallowed. */
        volatile boolean releaseFails;
    }

    /**
     * Rate limiter with a real, finite budget.
     *
     * <p>A fake rather than a mock again: it genuinely runs out, so the test exercises the
     * rejection path instead of asserting that a stub returned what it was told to.
     */
    static final class FakeRateLimiter implements RateLimiter {
        private final AtomicInteger remaining;

        FakeRateLimiter(int budget) {
            this.remaining = new AtomicInteger(budget);
        }

        @Override
        public boolean tryConsume(TenantId tenant, int permits) {
            // Atomic compare-and-set, matching the real Lua script's semantics: it must be
            // impossible for two callers to both spend the last token.
            while (true) {
                int current = remaining.get();
                if (current < permits) {
                    return false;
                }
                if (remaining.compareAndSet(current, current - permits)) {
                    return true;
                }
            }
        }
    }

    /** Counting metrics, so tests can assert a degradation was actually reported. */
    static final class CountingMetrics implements IngestMetrics {
        final AtomicInteger published = new AtomicInteger();
        final AtomicInteger duplicates = new AtomicInteger();
        final AtomicInteger rejected = new AtomicInteger();
        final AtomicInteger throttled = new AtomicInteger();
        final AtomicInteger idempotencyUnavailable = new AtomicInteger();
        final AtomicInteger publishFailures = new AtomicInteger();

        @Override
        public void recordPublished(int count) {
            published.addAndGet(count);
        }

        @Override
        public void recordDuplicates(int count) {
            duplicates.addAndGet(count);
        }

        @Override
        public void recordRejectedForCapacity(int count) {
            rejected.addAndGet(count);
        }

        @Override
        public void recordRejectedForQuota(int count) {
            throttled.addAndGet(count);
        }

        @Override
        public void recordIdempotencyUnavailable() {
            idempotencyUnavailable.incrementAndGet();
        }

        @Override
        public void recordPublishFailure() {
            publishFailures.incrementAndGet();
        }
    }
}

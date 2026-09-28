/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streampulse.ingest.application.FakeAdapters.CountingMetrics;
import com.streampulse.ingest.application.FakeAdapters.FakeIdempotencyStore;
import com.streampulse.ingest.application.FakeAdapters.FakePublisher;
import com.streampulse.ingest.application.FakeAdapters.FakeRateLimiter;
import com.streampulse.ingest.domain.BackpressureException;
import com.streampulse.ingest.domain.IngestCommand;
import com.streampulse.ingest.domain.IngestResult;
import com.streampulse.ingest.domain.QuotaExceededException;
import com.streampulse.ingest.domain.RateLimiter;
import com.streampulse.platform.domain.EventId;
import com.streampulse.platform.domain.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link IngestService}.
 *
 * <p>No Spring context, no Docker, no network — plain {@code new} plus fakes, so the whole
 * class runs in milliseconds. That is the payoff of keeping the application layer free of
 * framework annotations, and it is why these tests can afford to be exhaustive.
 */
class IngestServiceTest {

    private FakePublisher publisher;
    private FakeIdempotencyStore store;
    private CountingMetrics metrics;

    private static final TenantId TENANT = TenantId.of("streamflix");

    @BeforeEach
    void setUp() {
        publisher = new FakePublisher();
        store = new FakeIdempotencyStore();
        metrics = new CountingMetrics();
    }

    private IngestService service(int maxInFlight, Duration permitWait) {
        return service(maxInFlight, permitWait, RateLimiter.allowAll());
    }

    private IngestService service(int maxInFlight, Duration permitWait, RateLimiter limiter) {
        return new IngestService(publisher, store, limiter, metrics, maxInFlight, permitWait, true);
    }

    private IngestService service() {
        return service(8, Duration.ofMillis(200));
    }

    private static IngestCommand batchOf(int n) {
        var events = IntStream.range(0, n)
                .mapToObj(i -> new IngestCommand.IngestEvent(
                        EventId.random(),
                        "sess-" + i,
                        "title-1",
                        "SMART_TV",
                        "us-east-1",
                        Instant.parse("2026-01-15T12:00:00Z"),
                        "HEARTBEAT",
                        null))
                .toList();
        return new IngestCommand(TENANT, events, Instant.parse("2026-01-15T12:00:01Z"));
    }

    @Nested
    @DisplayName("the happy path")
    class HappyPath {

        @Test
        void publishesEveryEventAndReportsTheCount() {
            IngestResult result = service().ingest(batchOf(5));

            assertThat(result.published()).isEqualTo(5);
            assertThat(result.duplicates()).isZero();
            assertThat(result.idempotencyDegraded()).isFalse();
            assertThat(publisher.published).hasSize(5);
        }

        @Test
        @DisplayName("events are keyed by sessionId, preserving per-session ordering")
        void partitionKeyIsSessionId() {
            service().ingest(batchOf(3));

            assertThat(publisher.published)
                    .extracting(IngestCommand.IngestEvent::partitionKey)
                    .containsExactly("sess-0", "sess-1", "sess-2");
        }
    }

    @Nested
    @DisplayName("idempotency — the property the whole pipeline depends on")
    class Idempotency {

        @Test
        @DisplayName("resubmitting the same batch publishes nothing new")
        void duplicateBatchIsNotRepublished() {
            IngestCommand batch = batchOf(3);
            IngestService svc = service();

            IngestResult first = svc.ingest(batch);
            IngestResult second = svc.ingest(batch); // the client retried

            assertThat(first.published()).isEqualTo(3);
            assertThat(second.published()).isZero();
            assertThat(second.duplicates()).isEqualTo(3);

            // THE assertion that matters: the broker saw each event exactly once.
            assertThat(publisher.published).hasSize(3);
        }

        @Test
        @DisplayName("a partially-overlapping batch publishes only the new events")
        void partialOverlapPublishesOnlyTheNew() {
            // The realistic retry: a client resends events 0-2 plus two it has since buffered.
            IngestCommand first = batchOf(3);
            IngestService svc = service();
            svc.ingest(first);

            var mixed = new java.util.ArrayList<>(first.events());
            mixed.addAll(batchOf(2).events());
            IngestResult result = svc.ingest(new IngestCommand(TENANT, mixed, Instant.parse("2026-01-15T12:00:02Z")));

            assertThat(result.published()).isEqualTo(2);
            assertThat(result.duplicates()).isEqualTo(3);
            assertThat(publisher.published).hasSize(5);
        }

        @Test
        @DisplayName("two IDENTICAL batches sent concurrently still publish each event once")
        void concurrentDuplicateBatchesPublishOnce() throws Exception {
            // REQ-IG-12. This proves the claim is ATOMIC rather than check-then-set. With a
            // `contains()` followed by an `add()`, two threads can both see "absent" and both
            // publish — a bug that only appears in production, under load, as quietly
            // doubled metrics.
            //
            // WHY THIS LOOPS 200 TIMES.
            // An earlier single-shot version of this test PASSED against a deliberately
            // broken check-then-set store. That is the whole lesson about concurrency tests:
            // a race is a SCHEDULE, and one run samples one schedule. The window between
            // `contains` and `add` is a few nanoseconds wide, so a single attempt almost
            // never lands inside it.
            //
            // Repetition does not prove correctness — nothing here can — but it raises the
            // hit probability from "essentially never" to "reliably". Verified: with the
            // atomic claim replaced by check-then-set, this version fails.
            // See interview-qa/14-testing.md Q8.
            final int iterations = 200;
            final int threads = 8;

            for (int iteration = 0; iteration < iterations; iteration++) {
                // A FRESH store and publisher per iteration, so each is an independent trial
                // rather than 200 attempts against an already-populated set.
                publisher = new FakePublisher();
                store = new FakeIdempotencyStore();
                IngestCommand batch = batchOf(10);
                IngestService svc = service(16, Duration.ofSeconds(2));

                var startTogether = new CountDownLatch(1); // force genuine simultaneity
                var done = new CountDownLatch(threads);
                var totalPublished = new AtomicInteger();

                try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                    for (int i = 0; i < threads; i++) {
                        pool.submit(() -> {
                            try {
                                startTogether.await();
                                totalPublished.addAndGet(svc.ingest(batch).published());
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            } finally {
                                done.countDown();
                            }
                        });
                    }
                    startTogether.countDown(); // release all 8 at the same instant
                    assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
                }

                // The INVARIANT: whatever the interleaving, exactly 10 events reach Kafka.
                // An invariant holds under every schedule, which is far stronger evidence
                // than any single scripted scenario.
                assertThat(totalPublished.get())
                        .as("iteration %d: each event must be published exactly once", iteration)
                        .isEqualTo(10);
                assertThat(publisher.published)
                        .as("iteration %d: broker must see 10 records", iteration)
                        .hasSize(10);
            }
        }

        @Test
        @DisplayName("the same eventId under a different tenant is NOT a duplicate")
        void idempotencyIsNamespacedByTenant() {
            // Without tenant namespacing, one tenant's event id could suppress another's
            // event — silent cross-tenant data loss, and almost impossible to diagnose.
            IngestCommand forStreamflix = batchOf(2);
            IngestService svc = service();
            svc.ingest(forStreamflix);

            var forSportsnow =
                    new IngestCommand(TenantId.of("sportsnow"), forStreamflix.events(), forStreamflix.receivedAt());

            assertThat(svc.ingest(forSportsnow).published()).isEqualTo(2);
            assertThat(publisher.published).hasSize(4);
        }
    }

    @Nested
    @DisplayName("failing open when the dedup store is down")
    class FailOpen {

        @Test
        @DisplayName("Redis down: publish anyway and flag the degradation")
        void publishesAnywayAndReportsDegraded() {
            // REQ-IG-14. A deliberate trade: at-least-once with some duplicates beats losing
            // telemetry, because downstream aggregation tolerates duplicates but a gap in the
            // data is unrecoverable. For a payments system the correct choice is the
            // opposite — which is why this is configurable and documented in an ADR.
            store.unavailable = true;

            IngestResult result = service().ingest(batchOf(4));

            assertThat(result.published()).isEqualTo(4);
            assertThat(result.idempotencyDegraded()).isTrue();
            assertThat(publisher.published).hasSize(4);

            // The degradation must be VISIBLE. A silent one is a silent one forever.
            assertThat(metrics.idempotencyUnavailable.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("fail-closed mode refuses instead, when configured")
        void failClosedRefuses() {
            store.unavailable = true;
            var strict = new IngestService(
                    publisher,
                    store,
                    RateLimiter.allowAll(),
                    metrics,
                    8,
                    Duration.ofMillis(200),
                    /* failOpen= */ false);

            assertThatThrownBy(() -> strict.ingest(batchOf(2)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("fail-open is disabled");
            assertThat(publisher.published).isEmpty();
        }
    }

    @Nested
    @DisplayName("per-tenant quota — 429, distinct from 503")
    class Quota {

        @Test
        @DisplayName("exceeding the quota throws before anything is published")
        void quotaRejectionHappensFirst() {
            // The rejection must cost ONE cheap check, not a permit, a dedup claim and a
            // publish. Admission control that runs late does not protect you.
            IngestService svc = service(8, Duration.ofMillis(200), new FakeRateLimiter(3));

            svc.ingest(batchOf(3)); // spends the whole budget

            assertThatThrownBy(() -> svc.ingest(batchOf(1)))
                    .isInstanceOf(QuotaExceededException.class)
                    .hasMessageContaining("quota");

            assertThat(publisher.published).hasSize(3); // nothing extra reached the stream
            assertThat(metrics.throttled.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("a rejected batch leaves the in-flight permits untouched")
        void quotaRejectionDoesNotConsumeAPermit() {
            // If the quota check ran AFTER acquiring a permit, a throttled tenant would eat
            // capacity that healthy tenants need — turning their problem into everyone's.
            IngestService svc = service(4, Duration.ofMillis(200), new FakeRateLimiter(0));

            assertThatThrownBy(() -> svc.ingest(batchOf(1))).isInstanceOf(QuotaExceededException.class);

            assertThat(svc.availablePermits()).isEqualTo(4);
        }

        @Test
        @DisplayName("QuotaExceededException carries a Retry-After")
        void carriesRetryAfter() {
            IngestService svc = service(8, Duration.ofMillis(200), new FakeRateLimiter(0));

            assertThatThrownBy(() -> svc.ingest(batchOf(1)))
                    .isInstanceOf(QuotaExceededException.class)
                    .extracting(e -> ((QuotaExceededException) e).retryAfter())
                    .isNotNull();
        }
    }

    @Nested
    @DisplayName("backpressure — refusing work we cannot do")
    class Backpressure {

        @Test
        @DisplayName("saturating the in-flight bound throws rather than queueing")
        void rejectsWhenSaturated() throws Exception {
            // REQ-IG-19. One permit, and we hold it with a blocked publish; the second
            // request must be refused quickly rather than waiting indefinitely.
            var release = new CountDownLatch(1);
            publisher.blockUntil = release;
            IngestService svc = service(1, Duration.ofMillis(100));

            var occupied = new CountDownLatch(1);
            Thread holder = Thread.ofVirtual().start(() -> {
                occupied.countDown();
                svc.ingest(batchOf(1));
            });
            assertThat(occupied.await(2, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(50); // let the holder actually reach the blocking publish

            assertThatThrownBy(() -> svc.ingest(batchOf(1)))
                    .isInstanceOf(BackpressureException.class)
                    .hasMessageContaining("saturated");

            assertThat(metrics.rejected.get()).isEqualTo(1);

            release.countDown();
            holder.join(java.time.Duration.ofSeconds(5));
        }

        @Test
        @DisplayName("BackpressureException carries a Retry-After the client can use")
        void carriesRetryAfter() throws Exception {
            var release = new CountDownLatch(1);
            publisher.blockUntil = release;
            IngestService svc = service(1, Duration.ofMillis(50));

            Thread holder = Thread.ofVirtual().start(() -> svc.ingest(batchOf(1)));
            Thread.sleep(50);

            assertThatThrownBy(() -> svc.ingest(batchOf(1)))
                    .isInstanceOf(BackpressureException.class)
                    .extracting(e -> ((BackpressureException) e).retryAfter())
                    .isNotNull();

            release.countDown();
            holder.join(java.time.Duration.ofSeconds(5));
        }

        @Test
        @DisplayName("permits are released even when publishing fails")
        void permitIsReleasedOnFailure() {
            // A leaked permit is a PERMANENT capacity reduction that surfaces days later as
            // unexplained latency. The `finally` block is what prevents it; this test is
            // what proves the finally block is there.
            publisher.failWith = new RuntimeException("broker unreachable");
            IngestService svc = service(2, Duration.ofMillis(100));

            for (int i = 0; i < 5; i++) {
                assertThatThrownBy(() -> svc.ingest(batchOf(1))).isInstanceOf(IllegalStateException.class);
            }

            assertThat(svc.availablePermits())
                    .as("all permits must be back after repeated failures")
                    .isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("publish failures")
    class PublishFailures {

        @Test
        @DisplayName("the broker's exception is preserved as the cause")
        void unwrapsAndPreservesTheCause() {
            // CompletableFuture wraps failures in CompletionException. Leaving it wrapped
            // makes every stack trace one level less useful at 3am.
            publisher.failWith = new IllegalArgumentException("record too large");

            assertThatThrownBy(() -> service().ingest(batchOf(1)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("failed to publish")
                    .hasCauseInstanceOf(IllegalArgumentException.class);

            assertThat(metrics.publishFailures.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("REQ-IG-22: a failed publish leaves no claim behind, so the retry works")
        void aFailedPublishCanBeRetried() {
            IngestService service = service();
            IngestCommand batch = batchOf(3);

            // 1. The broker is down. The client is told, and still holds its events.
            publisher.failWith = new IllegalStateException("kinesis unavailable");
            assertThatThrownBy(() -> service.ingest(batch)).isInstanceOf(IllegalStateException.class);
            assertThat(publisher.published).isEmpty();

            // 2. The broker recovers and the client retries the SAME batch -- exactly what a
            //    well-behaved client does, and what our own Retry-After tells it to do.
            publisher.failWith = null;
            IngestResult retry = service.ingest(batch);

            // 3. All three events must reach the broker.
            //
            //    This is where telemetry disappears if step 1 left its claims behind:
            //    claimAll() reports all three as already-seen, the service returns 202 having
            //    published nothing, and the client drops its copy. A silent, permanent hole
            //    in the data, reported to the caller as success.
            //
            //    The claim is a RESERVATION, and a reservation that is never used has to be
            //    given back. Publishing failed, so the work was not done.
            assertThat(retry.published()).isEqualTo(3);
            assertThat(publisher.published).hasSize(3);
        }

        @Test
        @DisplayName("without the release, that retry is silently swallowed -- the bug being guarded")
        void withoutTheReleaseTheRetryIsSwallowed() {
            // Executable documentation of the failure mode, so nobody deletes release() as
            // "defensive code that never fires". releaseFails models exactly the pre-fix
            // behaviour: the claim is taken and never given back.
            store.releaseFails = true;

            IngestService service = service();
            IngestCommand batch = batchOf(3);

            publisher.failWith = new IllegalStateException("kinesis unavailable");
            assertThatThrownBy(() -> service.ingest(batch)).isInstanceOf(IllegalStateException.class);

            publisher.failWith = null;
            IngestResult retry = service.ingest(batch);

            // Here is the damage: HTTP 202, zero events published, nothing thrown, no metric
            // moved that says "loss". The client sees success and drops its copy.
            //
            // A reservation that is never released is indistinguishable from work completed.
            assertThat(retry.published()).isZero();
            assertThat(publisher.published).isEmpty();
            assertThat(retry.duplicates()).isEqualTo(3);
        }
    }
}

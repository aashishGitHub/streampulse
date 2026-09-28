/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.adapter.out.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streampulse.ingest.domain.EventPublisher;
import com.streampulse.ingest.domain.PublishUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Drives the circuit breaker through its whole state machine (REQ-RS-02, REQ-IG-21).
 *
 * <pre>
 *                    failure rate ≥ threshold
 *        ┌────────┐ ─────────────────────────▶ ┌──────┐
 *        │ CLOSED │                            │ OPEN │
 *        └────────┘ ◀───────────────────────── └──────┘
 *             ▲        trial calls succeeded        │  waitDurationInOpenState elapsed,
 *             │                                     │  and a call is attempted
 *             │           ┌───────────┐ ◀───────────┘
 *             └────────── │ HALF_OPEN │
 *                         └───────────┘ ──────────┐
 *                               ▲                 │ trial calls failed
 *                               └─────────────────┘
 * </pre>
 *
 * <p>No Spring, no Docker, no broker — a controllable fake delegate and real Resilience4j.
 * Testing against the real library rather than a mock of it is the point: the thing under
 * test is the <em>configuration</em>, and a mock would happily confirm whatever the test
 * already assumed.
 */
class ResilientEventPublisherTest {

    /** Tiny window so a handful of calls can drive a full transition. */
    private static final int WINDOW = 4;

    private static final Duration OPEN_WAIT = Duration.ofMillis(200);
    private static final Duration SLOW_THRESHOLD = Duration.ofMillis(100);
    private static final Duration CALL_TIMEOUT = Duration.ofMillis(300);

    private static final EventPublisher.TenantScopedEvents BATCH =
            new EventPublisher.TenantScopedEvents("streamflix", List.of());

    private ControllableDelegate delegate;
    private CircuitBreaker breaker;
    private ScheduledExecutorService scheduler;
    private ResilientEventPublisher publisher;

    @BeforeEach
    void setUp() {
        delegate = new ControllableDelegate();

        breaker = CircuitBreaker.of(
                "test",
                CircuitBreakerConfig.custom()
                        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(WINDOW)
                        .minimumNumberOfCalls(WINDOW)
                        .failureRateThreshold(50)
                        .slowCallRateThreshold(50)
                        .slowCallDurationThreshold(SLOW_THRESHOLD)
                        .waitDurationInOpenState(OPEN_WAIT)
                        .permittedNumberOfCallsInHalfOpenState(2)
                        .build());

        TimeLimiter timeLimiter = TimeLimiter.of(TimeLimiterConfig.custom()
                .timeoutDuration(CALL_TIMEOUT)
                .cancelRunningFuture(true)
                .build());

        scheduler = Executors.newSingleThreadScheduledExecutor();
        publisher = new ResilientEventPublisher(delegate, breaker, timeLimiter, scheduler);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
        delegate.releaseAll();
    }

    // ---------------------------------------------------------------- helpers

    /** Publishes and waits, returning the exception or null. */
    private Throwable publishAndCatch() {
        try {
            publisher.publishAll(BATCH).join();
            return null;
        } catch (CompletionException e) {
            return e.getCause() != null ? e.getCause() : e;
        } catch (RuntimeException e) {
            return e;
        }
    }

    private void drivePastTheThreshold() {
        delegate.mode = Mode.FAIL;
        for (int i = 0; i < WINDOW; i++) {
            publishAndCatch();
        }
    }

    // ------------------------------------------------------------------ tests

    @Nested
    @DisplayName("CLOSED — the normal state")
    class Closed {

        @Test
        @DisplayName("a healthy publish passes straight through")
        void passesThrough() {
            delegate.mode = Mode.SUCCEED;

            assertThat(publishAndCatch()).isNull();
            assertThat(delegate.calls).hasValue(1);
            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        }

        @Test
        @DisplayName("a rejection of THIS batch is passed through, not dressed up as transient")
        void brokerRejectionIsNotTransient() {
            // "Record too large" will fail identically forever. Reporting it as 503 +
            // Retry-After would send the client into a retry loop that cannot succeed;
            // it has to surface as a 500 so somebody looks at the batch.
            delegate.mode = Mode.FAIL;
            delegate.failure = new IllegalArgumentException("record too large");

            assertThat(publishAndCatch())
                    .isInstanceOf(IllegalArgumentException.class)
                    .isNotInstanceOf(PublishUnavailableException.class);
        }
    }

    @Nested
    @DisplayName("CLOSED → OPEN")
    class Opening {

        @Test
        @DisplayName("crossing the failure rate opens the breaker")
        void failuresOpenIt() {
            drivePastTheThreshold();

            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }

        @Test
        @DisplayName("calls that are merely SLOW open it too, though every one succeeded")
        void slowSuccessesOpenIt() {
            // The subtle failure mode. Every call returns 200 and the failure rate is 0%,
            // so a failure-rate-only breaker stays closed while each request holds an
            // in-flight permit for ages. Saturation arrives with a perfect error rate.
            delegate.mode = Mode.SLOW;
            delegate.delay = SLOW_THRESHOLD.plusMillis(60);

            for (int i = 0; i < WINDOW; i++) {
                assertThat(publishAndCatch()).isNull(); // all succeeded...
            }

            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN); // ...and it opened
        }

        @Test
        @DisplayName("THE ORDERING PROOF: a hanging broker times out, and the breaker counts it")
        void timeoutsAreCountedByTheBreaker() {
            // This is the test that justifies CircuitBreaker( TimeLimiter( call ) ).
            //
            // The delegate never completes. The time limiter turns each hang into a
            // TimeoutException, and because the breaker sits OUTSIDE it, the breaker sees
            // those timeouts and opens.
            //
            // Swap the order and the breaker would sit inside a timeout that kills the call
            // before the breaker learns anything: a permanently hanging dependency would
            // never trip it, and every caller would keep parking on it forever.
            delegate.mode = Mode.HANG;

            for (int i = 0; i < WINDOW; i++) {
                assertThat(publishAndCatch())
                        .isInstanceOf(PublishUnavailableException.class)
                        .satisfies(e -> assertThat(((PublishUnavailableException) e).callWasRejected())
                                .as("the call WAS attempted; we just do not know its outcome")
                                .isFalse());
            }

            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }
    }

    @Nested
    @DisplayName("OPEN — failing fast")
    class Open {

        @Test
        @DisplayName("the broker is not called at all")
        void failsFastWithoutTouchingTheBroker() {
            drivePastTheThreshold();
            int callsWhileClosed = delegate.calls.get();

            Throwable thrown = publishAndCatch();

            assertThat(thrown).isInstanceOf(PublishUnavailableException.class);
            assertThat(((PublishUnavailableException) thrown).callWasRejected())
                    .as("nothing was attempted, so this cannot have produced a duplicate")
                    .isTrue();
            assertThat(delegate.calls)
                    .as("failing fast means no new load on a dependency that is already struggling")
                    .hasValue(callsWhileClosed);
        }

        @Test
        @DisplayName("Retry-After is the breaker's own wait, so the two cannot drift apart")
        void retryAfterMatchesTheConfiguredWait() {
            drivePastTheThreshold();

            Throwable thrown = publishAndCatch();

            // Hard-coding "5s" here while the breaker stays open for 30 would send every
            // client back five times to be refused. Deriving it makes that impossible.
            assertThat(((PublishUnavailableException) thrown).retryAfter()).isEqualTo(OPEN_WAIT);
        }
    }

    @Nested
    @DisplayName("HALF_OPEN — probing for recovery")
    class HalfOpen {

        @Test
        @DisplayName("successful trial calls close the breaker")
        void trialSuccessesCloseIt() {
            drivePastTheThreshold();
            breaker.transitionToHalfOpenState(); // deterministic; the clock is tested below

            delegate.mode = Mode.SUCCEED;
            assertThat(publishAndCatch()).isNull();
            assertThat(publishAndCatch()).isNull(); // permittedNumberOfCallsInHalfOpenState = 2

            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        }

        @Test
        @DisplayName("failing trial calls send it back to OPEN, without a full window")
        void trialFailuresReopenIt() {
            // The point of HALF_OPEN: probe with a couple of calls rather than reopening the
            // floodgates and re-breaking whatever is still recovering.
            drivePastTheThreshold();
            breaker.transitionToHalfOpenState();

            delegate.mode = Mode.FAIL;
            publishAndCatch();
            publishAndCatch();

            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }

        @Test
        @DisplayName("the breaker reopens itself for traffic once the wait has elapsed")
        void recoversOnItsOwnAfterTheWait() {
            drivePastTheThreshold();
            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

            delegate.mode = Mode.SUCCEED;

            // No Thread.sleep: poll until the breaker lets traffic through again. The
            // transition is lazy in Resilience4j -- it happens when a call is ATTEMPTED
            // after the wait, not on a timer -- so only a real call can observe it.
            await().atMost(Duration.ofSeconds(5))
                    .pollInterval(Duration.ofMillis(25))
                    .until(() -> publishAndCatch() == null);

            await().atMost(Duration.ofSeconds(5))
                    .pollInterval(Duration.ofMillis(25))
                    .until(() -> {
                        publishAndCatch();
                        return breaker.getState() == CircuitBreaker.State.CLOSED;
                    });

            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        }
    }

    // ------------------------------------------------------------- the delegate

    private enum Mode {
        SUCCEED,
        FAIL,
        SLOW,
        HANG
    }

    /** A publisher whose behaviour the test dictates. */
    private static final class ControllableDelegate implements EventPublisher {

        final AtomicInteger calls = new AtomicInteger();
        volatile Mode mode = Mode.SUCCEED;
        volatile RuntimeException failure = new IllegalStateException("broker unreachable");
        volatile Duration delay = Duration.ZERO;

        private final List<CompletableFuture<Void>> hanging = new java.util.ArrayList<>();

        @Override
        public CompletableFuture<Void> publishAll(TenantScopedEvents events) {
            calls.incrementAndGet();
            return switch (mode) {
                case SUCCEED -> CompletableFuture.completedFuture(null);
                case FAIL -> CompletableFuture.failedFuture(failure);
                case SLOW ->
                    CompletableFuture.supplyAsync(
                            () -> null, CompletableFuture.delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS));
                case HANG -> {
                    // Never completes. Held so tearDown can release it and the test JVM does
                    // not leak a future nobody will ever finish.
                    CompletableFuture<Void> never = new CompletableFuture<>();
                    synchronized (hanging) {
                        hanging.add(never);
                    }
                    yield never;
                }
            };
        }

        void releaseAll() {
            synchronized (hanging) {
                hanging.forEach(f -> f.complete(null));
                hanging.clear();
            }
        }
    }
}

/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.adapter.out.resilience;

import com.streampulse.ingest.domain.EventPublisher;
import com.streampulse.ingest.domain.PublishUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.timelimiter.TimeLimiter;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps any {@link EventPublisher} in a circuit breaker and a timeout (REQ-IG-21, REQ-RS-02,
 * REQ-RS-06).
 *
 * <h2>Why a decorator and not {@code @CircuitBreaker} on a method</h2>
 *
 * The annotation needs a Spring proxy, and a proxy only intercepts calls that arrive from
 * outside the bean — the same self-invocation trap that catches people with
 * {@code @Transactional}. {@link com.streampulse.ingest.application.IngestService} is built
 * with plain {@code new} precisely so it stays unit-testable, so there is no proxy to rely on.
 *
 * <p>A decorator is explicit instead of magic: the composition is visible in
 * {@code IngestConfig}, the order is code rather than an aspect-ordering property, and this
 * class can be unit tested without a Spring context. The Decorator pattern earns its place
 * here — same interface, added behaviour, caller none the wiser.
 *
 * <h2>The ordering, which is the whole point</h2>
 *
 * <pre>
 *   CircuitBreaker ( TimeLimiter ( publish ) )
 *   └── outer ──────┘└── inner ──┘
 * </pre>
 *
 * The time limiter is <b>inside</b>, so the breaker observes its {@code TimeoutException} as
 * a failure and counts it.
 *
 * <p>Reverse them and the breaker sits inside a timeout that will cut the call off before the
 * breaker ever learns anything. A dependency that hangs would then never trip it — and
 * hanging is the most dangerous failure mode there is, because every caller keeps a thread
 * (or a virtual thread, plus its permit) parked while the service still looks alive to a
 * health check.
 *
 * <p>Ordering is the part people get wrong. "Which patterns did you use" is easy; "in what
 * order, and what breaks if you swap them" is the real question.
 *
 * <pre>
 *   failure modes this guards against
 *   ─────────────────────────────────────────────────────────────────────────────
 *   broker slow (still answering)   → slowCallDurationThreshold counts it as failure
 *   broker hanging (no answer)      → TimeLimiter cuts it, breaker counts the timeout
 *   broker down (fast rejects)      → failureRateThreshold opens the breaker
 *   breaker open                    → fail fast, 503 + Retry-After, no thread parked
 * </pre>
 *
 * <h2>What happens to the batch</h2>
 *
 * Nothing is published, and the caller is told so. {@code IngestService} then releases the
 * idempotency claims and rethrows, so the client's retry is treated as new work rather than
 * a duplicate. The breaker and that compensating release are a pair: a breaker without it
 * would swallow every batch for its entire open window.
 */
public class ResilientEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ResilientEventPublisher.class);

    private final EventPublisher delegate;
    private final CircuitBreaker circuitBreaker;
    private final TimeLimiter timeLimiter;
    private final ScheduledExecutorService timeoutScheduler;

    /**
     * How long to tell a client to wait when the breaker is open. Derived from the breaker's
     * own configured wait so the two can never drift apart: telling a client to come back in
     * 5s when the breaker stays open for 30 guarantees five wasted round trips.
     */
    private final Duration retryAfterWhenOpen;

    /** Shorter, because a timeout says nothing about how long the trouble will last. */
    private static final Duration RETRY_AFTER_ON_TIMEOUT = Duration.ofSeconds(2);

    public ResilientEventPublisher(
            EventPublisher delegate,
            CircuitBreaker circuitBreaker,
            TimeLimiter timeLimiter,
            ScheduledExecutorService timeoutScheduler) {
        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
        this.timeLimiter = timeLimiter;
        this.timeoutScheduler = timeoutScheduler;
        // There is no getWaitDurationInOpenState(): Resilience4j models the open-state wait
        // as an IntervalFunction (attempt number -> millis), because it also supports a wait
        // that grows each time the breaker reopens. For a fixed wait every input returns the
        // same value, so attempt 1 is the configured duration.
        long openMillis = circuitBreaker
                .getCircuitBreakerConfig()
                .getWaitIntervalFunctionInOpenState()
                .apply(1);
        this.retryAfterWhenOpen = Duration.ofMillis(openMillis);
    }

    @Override
    public CompletableFuture<Void> publishAll(TenantScopedEvents events) {
        Supplier<CompletionStage<Void>> call = () -> delegate.publishAll(events);

        // INNER: turn "hanging forever" into a TimeoutException the breaker can count.
        //
        // The scheduler only runs the timeout callback -- the publish itself still runs on
        // the SDK's own threads. It therefore needs one thread, not a pool sized to load.
        Supplier<CompletionStage<Void>> timed =
                TimeLimiter.decorateCompletionStage(timeLimiter, timeoutScheduler, call);

        // OUTER: count failures, and reject outright once the rate is past the threshold.
        Supplier<CompletionStage<Void>> guarded = CircuitBreaker.decorateCompletionStage(circuitBreaker, timed);

        try {
            return guarded.get()
                    .toCompletableFuture()
                    // Translate infrastructure exceptions into the domain's vocabulary at the
                    // boundary, which is this class's reason to exist.
                    .exceptionallyCompose(t -> CompletableFuture.failedFuture(translate(t, events.tenantId())));
        } catch (CallNotPermittedException e) {
            // Defensive: depending on the decorator, an open breaker may surface either as a
            // synchronous throw from get() or as a failed future. Both paths are covered so
            // the port's contract ("completes exceptionally") holds either way -- callers
            // must never have to handle a publish method that throws AND returns futures.
            return CompletableFuture.failedFuture(rejectedByBreaker(e));
        }
    }

    /** Maps infrastructure failures onto the domain's exception vocabulary. */
    private Throwable translate(Throwable thrown, String tenantId) {
        Throwable cause =
                thrown instanceof CompletionException && thrown.getCause() != null ? thrown.getCause() : thrown;

        if (cause instanceof CallNotPermittedException e) {
            return rejectedByBreaker(e);
        }

        if (cause instanceof TimeoutException e) {
            // The call was ATTEMPTED and we do not know the outcome. Cancelling a future is a
            // request, not a guarantee: the record may still be landing at the broker as we
            // give up on it. So this may produce a duplicate on retry -- deliberately
            // accepted, for the same reason as failing open. ADR-0007.
            log.warn("Publish for tenant {} timed out; a retry may duplicate these events", tenantId);
            return new PublishUnavailableException(
                    "publish timed out before the broker acknowledged", RETRY_AFTER_ON_TIMEOUT, false, e);
        }

        // Anything else is the broker genuinely rejecting THIS batch -- too large, bad key.
        // Retrying it unchanged will fail identically, so it must not be dressed up as a
        // transient problem. Passed through untouched, wrapper and all, so the caller's
        // existing unwrapping still finds the real cause.
        return thrown;
    }

    private PublishUnavailableException rejectedByBreaker(CallNotPermittedException e) {
        // No log line per rejection on purpose: the breaker is open precisely because calls
        // are failing at volume, and a log per rejected batch would flood the very logs
        // someone is reading to work out what broke. The state TRANSITION is logged once,
        // in IngestConfig's event listener. Metrics carry the rate.
        return new PublishUnavailableException(
                "event stream unavailable: circuit breaker is " + circuitBreaker.getState(),
                retryAfterWhenOpen,
                true,
                e);
    }
}

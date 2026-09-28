/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.domain;

import java.time.Duration;

/**
 * Thrown when the event stream could not be reached, and we know it.
 *
 * <h2>Why this is separate from a generic publish failure</h2>
 *
 * A publish can fail in two very different ways, and the client should treat them
 * differently:
 *
 * <ul>
 *   <li><b>The broker rejected this batch</b> — a record too large, a malformed key. Retrying
 *       unchanged will fail again forever. That stays an {@code IllegalStateException} → 500.
 *   <li><b>The broker is unreachable or too slow</b> — the circuit breaker is open, or the
 *       call timed out. Nothing is wrong with the batch; retrying later will work. That is
 *       this exception → 503 with {@code Retry-After}.
 * </ul>
 *
 * Collapsing both into one status makes a well-behaved client either give up on recoverable
 * data or hammer a broker that will never accept it.
 *
 * <h2>Why the domain owns it rather than letting Resilience4j's exception through</h2>
 *
 * {@code CallNotPermittedException} and {@code TimeoutException} are infrastructure details.
 * If the application layer caught them it would know its publisher has a circuit breaker,
 * and swapping the resilience library would become a change to business logic. The adapter
 * translates them at the boundary — which is the boundary's whole job.
 */
public class PublishUnavailableException extends RuntimeException {

    private final transient Duration retryAfter;

    /**
     * True when the call was never attempted (the breaker was open), false when it was
     * attempted with an unknown outcome (a timeout).
     *
     * <p>The distinction matters for reasoning about duplicates: a rejected call cannot have
     * produced one, a timed-out call might have. It is recorded rather than acted on, because
     * the response is the same either way — retry, and let idempotency sort it out.
     */
    private final boolean callWasRejected;

    public PublishUnavailableException(String message, Duration retryAfter, boolean callWasRejected, Throwable cause) {
        super(message, cause);
        this.retryAfter = retryAfter;
        this.callWasRejected = callWasRejected;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    public boolean callWasRejected() {
        return callWasRejected;
    }
}

/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.domain;

import java.time.Duration;

/**
 * Thrown when the gateway has no capacity right now.
 *
 * <p>This is <b>not</b> an error in the usual sense — it is the system correctly refusing
 * work it cannot do. The alternative is accepting it, queueing without bound, and dying with
 * the events still in memory. Unbounded buffering does not prevent failure; it relocates the
 * failure to the heap and makes it worse.
 *
 * <p>Maps to HTTP 503 with {@code Retry-After}. Deliberately distinct from a quota rejection
 * (429), so a client can tell whose problem it is: 429 means "you are over your limit, slow
 * down"; 503 means "we are saturated, another instance may cope".
 */
public class BackpressureException extends RuntimeException {

    private final transient Duration retryAfter;

    public BackpressureException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}

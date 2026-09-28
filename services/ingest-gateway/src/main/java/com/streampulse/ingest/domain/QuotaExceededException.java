/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.domain;

import java.time.Duration;

/**
 * The tenant has exceeded its own rate limit.
 *
 * <p>Deliberately distinct from {@link BackpressureException}, and the distinction is not
 * pedantry — a good client reacts differently to each:
 *
 * <ul>
 *   <li><b>This (429)</b> — <em>your</em> quota is exhausted. Retrying against another
 *       instance will not help, because the quota is global. Slow down.
 *   <li><b>Backpressure (503)</b> — <em>we</em> are saturated. Another instance may well
 *       cope, so an immediate retry elsewhere is reasonable.
 * </ul>
 *
 * Collapsing them into one status throws away the information the client needs to behave
 * correctly under load.
 */
public class QuotaExceededException extends RuntimeException {

    private final transient Duration retryAfter;

    public QuotaExceededException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}

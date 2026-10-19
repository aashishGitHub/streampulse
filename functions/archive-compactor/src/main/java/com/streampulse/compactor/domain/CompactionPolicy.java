/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.compactor.domain;

import java.time.Duration;

/**
 * @param quietPeriod how long a partition must have gone without a new object before it is touched
 * @param minInputObjects fewer raw objects than this are left alone: one object into one gains nothing
 */
public record CompactionPolicy(Duration quietPeriod, int minInputObjects) {

    public CompactionPolicy {
        if (quietPeriod.isNegative()) {
            throw new IllegalArgumentException("quietPeriod must not be negative");
        }
        if (minInputObjects < 1) {
            throw new IllegalArgumentException("minInputObjects must be at least 1");
        }
    }
}

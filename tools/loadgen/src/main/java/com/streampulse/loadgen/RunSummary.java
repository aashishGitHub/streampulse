/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.loadgen;

import java.time.Duration;
import java.util.OptionalDouble;
import java.util.OptionalLong;

public record RunSummary(
        Duration wallTime,
        long batchesSent,
        long eventsOffered,
        long eventsAccepted,
        long status202,
        long status429,
        long status503,
        long statusOther,
        long transportFailures,
        OptionalLong p50Nanos,
        OptionalLong p95Nanos,
        OptionalLong p99Nanos,
        long latencySamplesDropped,
        Duration maxSendLag,
        OptionalDouble freshnessP99Seconds,
        String freshnessNote) {

    public RunSummary withFreshness(OptionalDouble p99Seconds, String note) {
        return new RunSummary(
                wallTime,
                batchesSent,
                eventsOffered,
                eventsAccepted,
                status202,
                status429,
                status503,
                statusOther,
                transportFailures,
                p50Nanos,
                p95Nanos,
                p99Nanos,
                latencySamplesDropped,
                maxSendLag,
                p99Seconds,
                note);
    }
}

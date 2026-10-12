/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.loadgen;

import java.util.Locale;
import java.util.OptionalLong;

public final class ReportFormatter {

    private ReportFormatter() {}

    public static String format(RunSummary s) {
        double seconds = s.wallTime().toNanos() / 1e9;
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT, "wall time: %.2f s%n", seconds));
        out.append(String.format(
                Locale.ROOT,
                "batches sent: %d   offered events/s: %.1f   accepted events/s: %.1f%n",
                s.batchesSent(),
                s.eventsOffered() / seconds,
                s.eventsAccepted() / seconds));
        out.append(String.format(
                Locale.ROOT,
                "responses: 202=%d 429=%d 503=%d other=%d io-errors=%d%n",
                s.status202(),
                s.status429(),
                s.status503(),
                s.statusOther(),
                s.transportFailures()));
        if (s.p50Nanos().isPresent()) {
            out.append(String.format(
                    Locale.ROOT,
                    "ingest latency (202 only): p50=%s p95=%s p99=%s%n",
                    millis(s.p50Nanos()),
                    millis(s.p95Nanos()),
                    millis(s.p99Nanos())));
        } else {
            out.append("ingest latency: no 202 responses to measure\n");
        }
        out.append(String.format(
                Locale.ROOT,
                "max send lag behind schedule: %d ms%s%n",
                s.maxSendLag().toMillis(),
                s.latencySamplesDropped() > 0
                        ? "   (latency samples dropped: " + s.latencySamplesDropped() + ")"
                        : ""));
        if (s.freshnessP99Seconds().isPresent()) {
            out.append(String.format(
                    Locale.ROOT,
                    "freshness p99: %.2f s (estimated from histogram buckets%s)%n",
                    s.freshnessP99Seconds().getAsDouble(),
                    s.freshnessNote().isEmpty() ? "" : "; " + s.freshnessNote()));
        } else {
            out.append("freshness: unavailable");
            out.append(s.freshnessNote().isEmpty() ? "" : " (" + s.freshnessNote() + ")");
            out.append('\n');
        }
        return out.toString();
    }

    private static String millis(OptionalLong nanos) {
        return String.format(Locale.ROOT, "%.1f ms", nanos.orElse(0) / 1e6);
    }
}

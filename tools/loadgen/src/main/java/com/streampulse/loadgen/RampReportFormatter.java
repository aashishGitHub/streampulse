/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.loadgen;

import java.util.Locale;

public final class RampReportFormatter {

    private RampReportFormatter() {}

    public static String format(SaturationReport report) {
        StringBuilder out = new StringBuilder();
        out.append(String.format(
                Locale.ROOT,
                "%8s %10s %6s %6s %6s %6s %8s  %s%n",
                "target/s",
                "offered/s",
                "202",
                "429",
                "503",
                "other",
                "errors",
                "latency (202 only)"));
        for (StepResult step : report.steps()) {
            RunSummary s = step.summary();
            out.append(String.format(
                    Locale.ROOT,
                    "%8d %10.1f %6d %6d %6d %6d %7.1f%%  %s%n",
                    step.targetRate(),
                    step.offeredRate(),
                    s.status202(),
                    s.status429(),
                    s.status503(),
                    s.statusOther() + s.transportFailures(),
                    step.errorRate() * 100,
                    s.p99Nanos().isPresent()
                            ? String.format(
                                    Locale.ROOT, "p99=%.1f ms", s.p99Nanos().getAsLong() / 1e6)
                            : "no 202 responses"));
        }

        if (report.saturatedAtRate().isPresent()) {
            out.append(String.format(
                    Locale.ROOT,
                    "saturated at %d events/s (error rate over %.0f%%), mostly %s%n",
                    report.saturatedAtRate().getAsLong(),
                    SaturationFinder.ERROR_RATE_LIMIT * 100,
                    describe(report.cause().orElseThrow())));
            out.append(
                    report.lastCleanRate().isPresent()
                            ? String.format(
                                    Locale.ROOT,
                                    "last clean rate %d events/s%n",
                                    report.lastCleanRate().getAsLong())
                            : "no clean rate: the first step already breached\n");
        } else {
            out.append(String.format(
                    Locale.ROOT,
                    "no saturation found; highest rate tried: %d events/s%n",
                    report.lastCleanRate().orElseThrow()));
        }
        if (report.firstClientLimitedRate().isPresent()) {
            out.append(String.format(
                    Locale.ROOT,
                    "WARNING: the client could not offer the asked-for rate from %d events/s up, so results at"
                            + " and above that step describe this load generator, not the server%n",
                    report.firstClientLimitedRate().getAsLong()));
        }
        return out.toString();
    }

    private static String describe(SaturationReport.Cause cause) {
        return switch (cause) {
            case QUOTA -> "429 (per-tenant quota working as designed)";
            case CAPACITY -> "503 (the service running out of capacity)";
            case OTHER -> "other errors (unexpected statuses or transport failures)";
        };
    }
}

/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.loadgen;

public record StepResult(long targetRate, RunSummary summary) {

    /** Below this share of the asked-for rate, the client, not the server, is what ran out. */
    static final double CLIENT_LIMITED_BELOW = 0.9;

    public long errors() {
        return summary.status429() + summary.status503() + summary.statusOther() + summary.transportFailures();
    }

    public double errorRate() {
        return summary.batchesSent() == 0 ? 0 : (double) errors() / summary.batchesSent();
    }

    public double offeredRate() {
        return summary.eventsOffered() / (summary.wallTime().toNanos() / 1e9);
    }

    public boolean clientLimited() {
        return offeredRate() < targetRate * CLIENT_LIMITED_BELOW;
    }
}

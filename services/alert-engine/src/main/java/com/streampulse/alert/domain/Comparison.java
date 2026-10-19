/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.domain;

/** Named after the control-plane contract's comparator values. */
public enum Comparison {
    GT,
    GTE,
    LT,
    LTE;

    public boolean holds(double value, double limit) {
        return switch (this) {
            case GT -> value > limit;
            case GTE -> value >= limit;
            case LT -> value < limit;
            case LTE -> value <= limit;
        };
    }
}

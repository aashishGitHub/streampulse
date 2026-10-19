/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.domain;

import java.util.Objects;

/** What a rule tests. Closed on purpose: adding a kind forces every switch over it to be revisited. */
public sealed interface Condition {

    /** Stable lowercase name, used as a metric tag. */
    String kind();

    String describe();

    /** The current window's value compared with a fixed limit. */
    record Threshold(Comparison comparison, double limit) implements Condition {

        public Threshold {
            Objects.requireNonNull(comparison, "comparison");
            if (!Double.isFinite(limit)) {
                throw new IllegalArgumentException("limit must be finite");
            }
        }

        @Override
        public String kind() {
            return "threshold";
        }

        @Override
        public String describe() {
            return "value " + comparison + " " + limit;
        }
    }

    /**
     * Relative change against the immediately preceding window: {@code (current - previous) /
     * |previous|}. Breaching when that reaches {@code fraction} in the given direction.
     */
    record RateOfChange(Direction direction, double fraction) implements Condition {

        public enum Direction {
            RISE,
            FALL,
            EITHER
        }

        public RateOfChange {
            Objects.requireNonNull(direction, "direction");
            if (!(fraction > 0) || !Double.isFinite(fraction)) {
                throw new IllegalArgumentException("fraction must be positive and finite, was " + fraction);
            }
        }

        @Override
        public String kind() {
            return "rate_of_change";
        }

        @Override
        public String describe() {
            return "change " + direction + " by at least " + fraction;
        }
    }

    /** No window for this series for {@code windows} consecutive windows. */
    record Absence(int windows) implements Condition {

        public Absence {
            if (windows < 1) {
                throw new IllegalArgumentException("windows must be at least 1, was " + windows);
            }
        }

        @Override
        public String kind() {
            return "absence";
        }

        @Override
        public String describe() {
            return "no data for " + windows + " windows";
        }
    }
}

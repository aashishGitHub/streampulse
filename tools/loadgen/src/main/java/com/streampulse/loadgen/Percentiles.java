/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.loadgen;

import java.util.OptionalLong;

public final class Percentiles {

    private Percentiles() {}

    /** Nearest-rank percentile of an ascending-sorted array: the value at rank ceil(p/100 * n). */
    public static OptionalLong of(long[] sortedAscending, double percentile) {
        if (!(percentile >= 0 && percentile <= 100)) {
            throw new IllegalArgumentException("percentile must be within 0..100, got " + percentile);
        }
        int n = sortedAscending.length;
        if (n == 0) {
            return OptionalLong.empty();
        }
        int rank = (int) Math.ceil(percentile * n / 100.0);
        return OptionalLong.of(sortedAscending[Math.max(rank, 1) - 1]);
    }
}

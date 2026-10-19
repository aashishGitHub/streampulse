/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.domain;

import java.util.Comparator;

public record RankedEntry(FeedEntry entry, double score) {

    /** Score descending, then entry id ascending. Total, because an entry id is unique. */
    public static final Comparator<RankedEntry> ORDER = Comparator.comparingDouble(RankedEntry::score)
            .reversed()
            .thenComparing(r -> r.entry().member());
}

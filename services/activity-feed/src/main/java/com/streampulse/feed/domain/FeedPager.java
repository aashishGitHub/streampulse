/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.domain;

import java.util.List;
import java.util.Optional;

public final class FeedPager {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    /** {@code ordered} must be in {@link RankedEntry#ORDER}. */
    public FeedPage page(List<RankedEntry> ordered, Optional<Cursor> after, int limit, long asOfMillis) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, got " + limit);
        }
        int start = after.map(c -> firstAfter(ordered, c)).orElse(0);
        int end = Math.min(ordered.size(), start + limit);
        List<RankedEntry> items = List.copyOf(ordered.subList(start, end));
        boolean more = end < ordered.size();
        Optional<Cursor> next = more
                ? Optional.of(new Cursor(
                        asOfMillis,
                        items.getLast().score(),
                        items.getLast().entry().member()))
                : Optional.empty();
        return new FeedPage(items, next);
    }

    private static int firstAfter(List<RankedEntry> ordered, Cursor cursor) {
        for (int i = 0; i < ordered.size(); i++) {
            RankedEntry item = ordered.get(i);
            int byScore = Double.compare(cursor.score(), item.score());
            if (byScore > 0 || (byScore == 0 && item.entry().member().compareTo(cursor.entryId()) > 0)) {
                return i;
            }
        }
        return ordered.size();
    }
}

/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The read path's de-duplication and filtering, with no store behind it. */
public final class FeedMerger {

    private record AuthorTitle(UserId author, TitleId title) {}

    private static final Comparator<FeedEntry> NEWEST_FIRST =
            Comparator.comparingLong(FeedEntry::score).thenComparing(FeedEntry::member);

    public List<FeedEntry> merge(
            Collection<FeedEntry> timeline,
            Collection<FeedEntry> celebrityEntries,
            Set<UserId> followed,
            Instant asOf) {
        Set<String> seen = new HashSet<>();
        Map<AuthorTitle, FeedEntry> newestPerAuthorAndTitle = new HashMap<>();
        List<FeedEntry> all = new ArrayList<>(timeline.size() + celebrityEntries.size());
        all.addAll(timeline);
        all.addAll(celebrityEntries);

        for (FeedEntry entry : all) {
            // Stale after an unfollow, and "newer than the snapshot" belongs to the next fresh fetch.
            if (!followed.contains(entry.author()) || entry.occurredAt().isAfter(asOf)) {
                continue;
            }
            if (!seen.add(entry.member())) {
                continue;
            }
            newestPerAuthorAndTitle.merge(
                    new AuthorTitle(entry.author(), entry.title()),
                    entry,
                    (a, b) -> NEWEST_FIRST.compare(a, b) >= 0 ? a : b);
        }
        return List.copyOf(newestPerAuthorAndTitle.values());
    }
}

/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Per-user timelines and per-author outboxes. Every write is idempotent: writing the same entry
 * twice leaves one entry.
 */
public interface TimelineStore {

    /** Fan-out on write: the entry into each follower's timeline, grouped rather than one round trip each. */
    void pushToTimelines(Collection<UserId> followers, FeedEntry entry);

    void appendToOutbox(FeedEntry entry);

    /** Newest first, no newer than {@code asOf}. */
    List<FeedEntry> timeline(UserId user, Instant asOf, int limit);

    /** The recent entries of each author, no newer than {@code asOf}, up to {@code perAuthorLimit} each. */
    List<FeedEntry> outboxes(Collection<UserId> authors, Instant asOf, int perAuthorLimit);
}

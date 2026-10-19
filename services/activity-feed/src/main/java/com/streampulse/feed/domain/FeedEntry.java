/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * One "X is watching T" line. The {@linkplain #member() member} is the whole entry in a form a
 * sorted set can hold, and it contains the event id, which is what makes writing it idempotent:
 * a redelivered event produces the same member and the same score.
 */
public record FeedEntry(UUID eventId, UserId author, TitleId title, Instant occurredAt) {

    public FeedEntry {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(title, "title");
        // A score is a millisecond count; anything finer would not survive a round trip.
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt").truncatedTo(ChronoUnit.MILLIS);
    }

    public static FeedEntry of(WatchStarted watch) {
        return new FeedEntry(watch.eventId(), watch.userId(), watch.titleId(), watch.occurredAt());
    }

    public static FeedEntry fromMember(String member, long epochMillis) {
        String[] parts = member == null ? new String[0] : member.split(":", -1);
        if (parts.length != 3) {
            throw new InvalidFeedRequestException("not a feed member");
        }
        try {
            UUID eventId = UUID.fromString(parts[0]);
            if (!eventId.toString().equals(parts[0])) {
                throw new InvalidFeedRequestException("not a feed member");
            }
            return new FeedEntry(
                    eventId, new UserId(parts[1]), new TitleId(parts[2]), Instant.ofEpochMilli(epochMillis));
        } catch (IllegalArgumentException e) {
            throw new InvalidFeedRequestException("not a feed member");
        }
    }

    /** Also the entry's identity everywhere: dedupe key, cursor tiebreak. */
    public String member() {
        return eventId + ":" + author.value() + ":" + title.value();
    }

    public long score() {
        return occurredAt.toEpochMilli();
    }
}

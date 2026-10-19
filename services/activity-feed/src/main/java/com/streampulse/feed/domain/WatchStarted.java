/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A user started watching a title. {@code eventId} is the idempotency key. */
public record WatchStarted(UUID eventId, UserId userId, TitleId titleId, Instant occurredAt) {

    public WatchStarted {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(titleId, "titleId");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}

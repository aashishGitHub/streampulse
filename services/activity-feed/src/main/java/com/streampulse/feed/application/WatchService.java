/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.application;

import com.streampulse.feed.domain.TitleId;
import com.streampulse.feed.domain.UserId;
import com.streampulse.feed.domain.WatchStarted;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** The synchronous half of the write path: validate, stamp, record. Fan-out happens later, elsewhere. */
public final class WatchService {

    private final WatchPublisher publisher;
    private final Clock clock;
    private final Supplier<UUID> eventIds;

    public WatchService(WatchPublisher publisher, Clock clock, Supplier<UUID> eventIds) {
        this.publisher = publisher;
        this.clock = clock;
        this.eventIds = eventIds;
    }

    /** Returns the event id, the idempotency key: the client's if it sent one, so its retry is deduplicated. */
    public UUID watch(UserId user, TitleId title, Optional<UUID> clientEventId) {
        UUID eventId = clientEventId.orElseGet(eventIds);
        publisher.publish(new WatchStarted(eventId, user, title, clock.instant()));
        return eventId;
    }
}

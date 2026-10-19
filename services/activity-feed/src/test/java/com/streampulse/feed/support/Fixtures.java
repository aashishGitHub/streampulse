/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.support;

import com.streampulse.feed.domain.FeedEntry;
import com.streampulse.feed.domain.TitleId;
import com.streampulse.feed.domain.UserId;
import com.streampulse.feed.domain.WatchStarted;
import java.time.Instant;
import java.util.UUID;

public final class Fixtures {

    /** A fixed "now" for every pure test. */
    public static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");

    private Fixtures() {}

    public static UserId user(String id) {
        return new UserId(id);
    }

    public static TitleId title(String id) {
        return new TitleId(id);
    }

    public static FeedEntry entry(String author, String title, Instant at) {
        return new FeedEntry(UUID.randomUUID(), user(author), title(title), at);
    }

    public static FeedEntry entry(UUID eventId, String author, String title, Instant at) {
        return new FeedEntry(eventId, user(author), title(title), at);
    }

    public static WatchStarted watch(String author, String title, Instant at) {
        return new WatchStarted(UUID.randomUUID(), user(author), title(title), at);
    }
}

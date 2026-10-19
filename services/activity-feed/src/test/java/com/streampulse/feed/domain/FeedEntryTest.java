/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FeedEntryTest {

    private static final UUID EVENT = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final Instant AT = Instant.parse("2026-10-13T10:00:00.123Z");

    private static FeedEntry entry(UUID event) {
        return new FeedEntry(event, new UserId("alice"), new TitleId("dune"), AT);
    }

    @Test
    @DisplayName("a member round-trips every field")
    void memberRoundTripsEveryField() {
        FeedEntry original = entry(EVENT);

        FeedEntry parsed = FeedEntry.fromMember(original.member(), original.score());

        assertThat(parsed).isEqualTo(original);
        assertThat(original.member()).isEqualTo("11111111-2222-3333-4444-555555555555:alice:dune");
        assertThat(original.score()).isEqualTo(AT.toEpochMilli());
    }

    @Test
    @DisplayName("the member carries the event id, so a different event is a different member")
    void entryIdChangesWithTheEventId() {
        assertThat(entry(EVENT).member()).isEqualTo(entry(EVENT).member());
        assertThat(entry(UUID.randomUUID()).member()).isNotEqualTo(entry(EVENT).member());
    }

    @Test
    @DisplayName("time is held at millisecond precision, which is all a score preserves")
    void timeIsTruncatedToMilliseconds() {
        var precise = new FeedEntry(
                EVENT, new UserId("a"), new TitleId("t"), Instant.parse("2026-10-13T10:00:00.123456789Z"));

        assertThat(precise.occurredAt()).isEqualTo(Instant.parse("2026-10-13T10:00:00.123Z"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "nope", "a:b", "not-a-uuid:alice:dune", "11111111-2222-3333-4444-555555555555:alice"})
    @DisplayName("a malformed member is rejected")
    void aMalformedMemberIsRejected(String member) {
        assertThatThrownBy(() -> FeedEntry.fromMember(member, 1L)).isInstanceOf(InvalidFeedRequestException.class);
    }

    @Test
    @DisplayName("an entry is built from a watch event")
    void builtFromAWatchEvent() {
        var watch = new WatchStarted(EVENT, new UserId("alice"), new TitleId("dune"), AT);

        assertThat(FeedEntry.of(watch)).isEqualTo(entry(EVENT));
    }
}

/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.domain;

import static com.streampulse.feed.support.Fixtures.NOW;
import static com.streampulse.feed.support.Fixtures.entry;
import static com.streampulse.feed.support.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FeedMergerTest {

    private final FeedMerger merger = new FeedMerger();
    private final Set<UserId> everyone = Set.of(user("a"), user("b"), user("celeb"));

    @Test
    @DisplayName("an entry present in both the timeline and a celebrity outbox is listed once")
    void dropsADuplicateEntryId() {
        // An author who crossed the threshold has older entries in followers' timelines AND in the outbox.
        UUID event = UUID.randomUUID();
        var inTimeline = entry(event, "celeb", "dune", NOW.minusSeconds(60));
        var inOutbox = entry(event, "celeb", "dune", NOW.minusSeconds(60));

        List<FeedEntry> merged = merger.merge(List.of(inTimeline), List.of(inOutbox), everyone, NOW);

        assertThat(merged).containsExactly(inTimeline);
    }

    @Test
    @DisplayName("an author the reader no longer follows is dropped, though the timeline still holds the entry")
    void dropsEntriesOfAuthorsNoLongerFollowed() {
        var kept = entry("a", "dune", NOW.minusSeconds(10));
        var stale = entry("ex", "dune", NOW.minusSeconds(5));

        List<FeedEntry> merged = merger.merge(List.of(kept, stale), List.of(), everyone, NOW);

        assertThat(merged).containsExactly(kept);
    }

    @Test
    @DisplayName("an entry newer than the snapshot is dropped: it belongs to the next fresh fetch")
    void dropsEntriesNewerThanTheSnapshot() {
        var inside = entry("a", "dune", NOW);
        var after = entry("b", "dune", NOW.plusMillis(1));

        List<FeedEntry> merged = merger.merge(List.of(inside, after), List.of(), everyone, NOW);

        assertThat(merged).containsExactly(inside);
    }

    @Test
    @DisplayName("repeated watches of one title by one author collapse to the newest")
    void keepsOnlyTheNewestWatchOfATitleByTheSameAuthor() {
        var older = entry("a", "dune", NOW.minus(Duration.ofMinutes(30)));
        var newer = entry("a", "dune", NOW.minus(Duration.ofMinutes(1)));
        var otherTitle = entry("a", "arrival", NOW.minus(Duration.ofMinutes(40)));
        var otherAuthor = entry("b", "dune", NOW.minus(Duration.ofMinutes(50)));

        List<FeedEntry> merged = merger.merge(List.of(older, otherAuthor), List.of(newer, otherTitle), everyone, NOW);

        assertThat(merged).containsExactlyInAnyOrder(newer, otherTitle, otherAuthor);
    }

    @Test
    @DisplayName(
            "an exact tie on time keeps the entry with the greater id, so the choice does not depend on input order")
    void aTieIsBrokenDeterministically() {
        var low = entry(UUID.fromString("00000000-0000-0000-0000-000000000001"), "a", "dune", NOW);
        var high = entry(UUID.fromString("ffffffff-0000-0000-0000-000000000001"), "a", "dune", NOW);

        assertThat(merger.merge(List.of(low, high), List.of(), everyone, NOW)).containsExactly(high);
        assertThat(merger.merge(List.of(high, low), List.of(), everyone, NOW)).containsExactly(high);
    }

    @Test
    @DisplayName("nothing in, nothing out")
    void emptyInputsGiveAnEmptyResult() {
        assertThat(merger.merge(List.of(), List.of(), everyone, NOW)).isEmpty();
    }
}

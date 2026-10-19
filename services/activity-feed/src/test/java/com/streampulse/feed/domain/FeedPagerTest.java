/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.domain;

import static com.streampulse.feed.support.Fixtures.NOW;
import static com.streampulse.feed.support.Fixtures.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FeedPagerTest {

    private static final long AS_OF = NOW.toEpochMilli();
    private final FeedPager pager = new FeedPager();
    private final Ranker ranker = new Ranker(Duration.ofMinutes(30));

    private List<RankedEntry> ranked(int count, long seed) {
        var random = new Random(seed);
        var entries = new ArrayList<FeedEntry>();
        for (int i = 0; i < count; i++) {
            entries.add(entry("author" + i, "title" + random.nextInt(8), NOW.minusSeconds(random.nextInt(7_200))));
        }
        return ranker.rank(entries, NOW);
    }

    private List<RankedEntry> walk(List<RankedEntry> ordered, int limit) {
        var seen = new ArrayList<RankedEntry>();
        Optional<Cursor> cursor = Optional.empty();
        do {
            FeedPage page = pager.page(ordered, cursor, limit, AS_OF);
            seen.addAll(page.items());
            cursor = page.next();
        } while (cursor.isPresent());
        return seen;
    }

    @Test
    @DisplayName("the first page is the head of the ranking and carries a cursor when more remain")
    void firstPageStartsAtTheTop() {
        var ordered = ranked(10, 1);

        FeedPage page = pager.page(ordered, Optional.empty(), 4, AS_OF);

        assertThat(page.items()).isEqualTo(ordered.subList(0, 4));
        assertThat(page.next()).isPresent();
        assertThat(page.next().get().asOfMillis()).isEqualTo(AS_OF);
    }

    @Test
    @DisplayName("the next page starts strictly after the cursor's entry")
    void nextPageStartsStrictlyAfterTheCursor() {
        var ordered = ranked(10, 2);
        FeedPage first = pager.page(ordered, Optional.empty(), 4, AS_OF);

        FeedPage second = pager.page(ordered, first.next(), 4, AS_OF);

        assertThat(second.items()).isEqualTo(ordered.subList(4, 8));
    }

    @Test
    @DisplayName("the last page has no cursor, and a page that exactly empties the list has none either")
    void lastPageHasNoCursor() {
        var ordered = ranked(8, 3);

        assertThat(pager.page(ordered, Optional.empty(), 8, AS_OF).next()).isEmpty();
        assertThat(pager.page(ordered, Optional.empty(), 20, AS_OF).next()).isEmpty();
        assertThat(pager.page(List.of(), Optional.empty(), 5, AS_OF).items()).isEmpty();
    }

    @Test
    @DisplayName("walking the cursors returns every entry exactly once, in order, for any page size")
    void pagesPartitionTheListWithoutRepeatOrSkip() {
        for (int limit : new int[] {1, 2, 3, 7, 19, 50}) {
            var ordered = ranked(50, 40L + limit);

            assertThat(walk(ordered, limit)).as("limit %d", limit).isEqualTo(ordered);
        }
    }

    @Test
    @DisplayName(
            "entries with identical scores are paged by entry id, so a cursor inside a tie neither repeats nor skips")
    void pagesThroughATieWithoutRepeatOrSkip() {
        var tied = new ArrayList<FeedEntry>();
        for (int i = 0; i < 12; i++) {
            tied.add(entry(new UUID(0, i), "author" + i, "title" + i, NOW));
        }
        var ordered = ranker.rank(tied, NOW);
        assertThat(ordered).extracting(RankedEntry::score).containsOnly(1.0);

        assertThat(walk(ordered, 5)).isEqualTo(ordered);
    }

    @Test
    @DisplayName("a cursor whose entry is no longer in the list still resumes at the right place")
    void aCursorSurvivesItsEntryDisappearing() {
        var ordered = ranked(12, 5);
        FeedPage first = pager.page(ordered, Optional.empty(), 5, AS_OF);
        var withoutTheCursorEntry = new ArrayList<>(ordered);
        withoutTheCursorEntry.remove(4);

        FeedPage second = pager.page(withoutTheCursorEntry, first.next(), 5, AS_OF);

        assertThat(second.items()).isEqualTo(ordered.subList(5, 10));
    }

    @Test
    @DisplayName("a non-positive page size is a programming error")
    void rejectsANonPositiveLimit() {
        assertThatThrownBy(() -> pager.page(List.of(), Optional.empty(), 0, AS_OF))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

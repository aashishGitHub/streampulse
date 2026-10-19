/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.domain;

import static com.streampulse.feed.support.Fixtures.NOW;
import static com.streampulse.feed.support.Fixtures.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RankerTest {

    private static final Duration HALF_LIFE = Duration.ofMinutes(30);
    private final Ranker ranker = new Ranker(HALF_LIFE);

    @Test
    @DisplayName("of two entries on the same title, the newer ranks first")
    void aNewerEntryOutranksAnOlderOneOfTheSameTitle() {
        var older = entry("a", "dune", NOW.minus(Duration.ofMinutes(20)));
        var newer = entry("b", "dune", NOW.minus(Duration.ofMinutes(5)));

        List<RankedEntry> ranked = ranker.rank(List.of(older, newer), NOW);

        assertThat(ranked).extracting(RankedEntry::entry).containsExactly(newer, older);
    }

    @Test
    @DisplayName("an entry loses exactly half its score every half-life, and a fresh lone entry scores 1")
    void anEntryLosesHalfItsScoreEveryHalfLife() {
        var fresh = entry("a", "t1", NOW);
        var oneHalfLife = entry("b", "t2", NOW.minus(HALF_LIFE));
        var twoHalfLives = entry("c", "t3", NOW.minus(HALF_LIFE.multipliedBy(2)));

        var scores = ranker.rank(List.of(fresh, oneHalfLife, twoHalfLives), NOW).stream()
                .collect(java.util.stream.Collectors.toMap(
                        r -> r.entry().author().value(), RankedEntry::score));

        assertThat(scores.get("a")).isEqualTo(1.0);
        assertThat(scores.get("b")).isEqualTo(0.5);
        assertThat(scores.get("c")).isEqualTo(0.25);
    }

    @Test
    @DisplayName("affinity is 1 + ln(n), n being the followed authors watching the same title")
    void affinityIsOnePlusTheNaturalLogOfTheAuthorsOnTheTitle() {
        var one = entry("a", "solo", NOW);
        var t1 = entry("b", "crowd", NOW);
        var t2 = entry("c", "crowd", NOW);
        var t3 = entry("d", "crowd", NOW);

        var byAuthor = ranker.rank(List.of(one, t1, t2, t3), NOW).stream()
                .collect(java.util.stream.Collectors.toMap(
                        r -> r.entry().author().value(), RankedEntry::score));

        assertThat(byAuthor.get("a")).isEqualTo(1.0);
        assertThat(byAuthor.get("b")).isCloseTo(1 + Math.log(3), within(1e-12));
        assertThat(byAuthor.get("c")).isEqualTo(byAuthor.get("b"));
    }

    @Test
    @DisplayName("several followed users on one title can outrank a fresher entry on a title only one is watching")
    void moreFolloweesOnTheSameTitleOutrankFewer() {
        var crowd1 = entry("a", "crowd", NOW.minus(Duration.ofMinutes(10)));
        var crowd2 = entry("b", "crowd", NOW.minus(Duration.ofMinutes(10)));
        var crowd3 = entry("c", "crowd", NOW.minus(Duration.ofMinutes(10)));
        var lonelyButFresh = entry("d", "solo", NOW);

        List<RankedEntry> ranked = ranker.rank(List.of(lonelyButFresh, crowd1, crowd2, crowd3), NOW);

        assertThat(ranked.get(ranked.size() - 1).entry()).isEqualTo(lonelyButFresh);
    }

    @Test
    @DisplayName("equal scores are ordered by entry id, so the order is total and deterministic")
    void ordersTiesByEntryId() {
        var low = entry(UUID.fromString("00000000-0000-0000-0000-000000000001"), "a", "t1", NOW);
        var high = entry(UUID.fromString("ffffffff-0000-0000-0000-000000000001"), "b", "t2", NOW);

        assertThat(ranker.rank(List.of(high, low), NOW))
                .extracting(RankedEntry::entry)
                .containsExactly(low, high);
        assertThat(ranker.rank(List.of(low, high), NOW))
                .extracting(RankedEntry::entry)
                .containsExactly(low, high);
    }

    @Test
    @DisplayName("an entry newer than the snapshot is not boosted above 1: its age is clamped to zero")
    void aFutureEntryIsClampedToTheSnapshot() {
        var future = entry("a", "t", NOW.plus(Duration.ofHours(1)));

        assertThat(ranker.rank(List.of(future), NOW).get(0).score()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("ranking is a pure function of the candidates and the snapshot instant")
    void rankingIsRepeatable() {
        var entries = List.of(
                entry("a", "x", NOW.minusSeconds(61)),
                entry("b", "x", NOW.minusSeconds(333)),
                entry("c", "y", NOW.minusSeconds(7)));

        assertThat(ranker.rank(entries, NOW)).isEqualTo(ranker.rank(entries.reversed(), NOW));
    }

    @Test
    @DisplayName("a non-positive half-life is rejected")
    void aNonPositiveHalfLifeIsRejected() {
        assertThatThrownBy(() -> new Ranker(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Ranker(Duration.ofMinutes(-1))).isInstanceOf(IllegalArgumentException.class);
    }
}

/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.application;

import static com.streampulse.feed.support.Fixtures.NOW;
import static com.streampulse.feed.support.Fixtures.entry;
import static com.streampulse.feed.support.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streampulse.feed.domain.CursorCodec;
import com.streampulse.feed.domain.FanOutPolicy;
import com.streampulse.feed.domain.FeedEntry;
import com.streampulse.feed.domain.FeedMerger;
import com.streampulse.feed.domain.FeedPager;
import com.streampulse.feed.domain.InvalidCursorException;
import com.streampulse.feed.domain.RankedEntry;
import com.streampulse.feed.domain.Ranker;
import com.streampulse.feed.domain.UserId;
import com.streampulse.feed.support.InMemoryFollowGraph;
import com.streampulse.feed.support.InMemoryTimelineStore;
import com.streampulse.feed.support.MutableClock;
import com.streampulse.feed.support.RecordingMetrics;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FeedServiceTest {

    private static final int THRESHOLD = 5;
    private static final UserId READER = user("reader");

    private final InMemoryFollowGraph graph = new InMemoryFollowGraph();
    private final InMemoryTimelineStore store = new InMemoryTimelineStore();
    private final RecordingMetrics metrics = new RecordingMetrics();
    private final MutableClock clock = new MutableClock(NOW);

    private FeedService service(int maxCelebrities) {
        return new FeedService(
                graph,
                store,
                new FanOutPolicy(THRESHOLD),
                new FeedMerger(),
                new Ranker(Duration.ofMinutes(30)),
                new FeedPager(),
                new CursorCodec("a-test-secret-that-is-long-enough".getBytes(StandardCharsets.UTF_8)),
                metrics,
                clock,
                new FeedService.Limits(200, 50, maxCelebrities, 5_000));
    }

    private FeedService service() {
        return service(100);
    }

    private FeedEntry followAndPutInTimeline(String author, String title, Duration age) {
        graph.follow(READER, user(author));
        FeedEntry entry = entry(author, title, NOW.minus(age));
        store.putInTimeline(READER, entry);
        return entry;
    }

    @Test
    @DisplayName("the feed merges the precomputed timeline with the outbox of each celebrity the reader follows")
    void mergesTheTimelineWithEachCelebrityOutbox() {
        FeedEntry fromTimeline = followAndPutInTimeline("friend", "dune", Duration.ofMinutes(10));
        graph.follow(READER, user("star"));
        graph.addFollowers(user("star"), "fan", THRESHOLD - 1);
        FeedEntry fromOutbox = entry("star", "arrival", NOW.minus(Duration.ofMinutes(5)));
        store.appendToOutbox(fromOutbox);

        FeedService.Result result = service().read(READER, Optional.empty(), 20);

        assertThat(result.items()).extracting(RankedEntry::entry).containsExactlyInAnyOrder(fromTimeline, fromOutbox);
        assertThat(metrics.celebrityMerges).containsExactly(1);
        assertThat(store.outboxLookups()).containsExactly(List.of(user("star")));
    }

    @Test
    @DisplayName("an author whose follower count is below the threshold is never read from an outbox")
    void aNormalAuthorsOutboxIsNotConsulted() {
        followAndPutInTimeline("friend", "dune", Duration.ofMinutes(10));
        store.appendToOutbox(entry("friend", "never-shown-twice", NOW.minus(Duration.ofMinutes(1))));

        FeedService.Result result = service().read(READER, Optional.empty(), 20);

        assertThat(result.items()).hasSize(1);
        assertThat(store.outboxLookups()).isEmpty();
    }

    @Test
    @DisplayName("an entry left in the timeline by an author the reader has since unfollowed is not returned")
    void anUnfollowedAuthorsStaleEntryIsNotReturned() {
        FeedEntry kept = followAndPutInTimeline("friend", "dune", Duration.ofMinutes(10));
        FeedEntry stale = followAndPutInTimeline("ex", "dune", Duration.ofMinutes(2));
        graph.unfollow(READER, user("ex"));

        FeedService.Result result = service().read(READER, Optional.empty(), 20);

        assertThat(store.timelineOf(READER)).as("the store still holds it").contains(stale);
        assertThat(result.items()).extracting(RankedEntry::entry).containsExactly(kept);
    }

    @Test
    @DisplayName("entries that arrive between pages never repeat an item or skip one, and appear on a fresh fetch")
    void pagesAcrossConcurrentInsertsWithoutRepeatOrSkip() {
        for (int i = 0; i < 30; i++) {
            followAndPutInTimeline("author" + i, "title" + (i % 6), Duration.ofSeconds(60L * (i + 1)));
        }
        List<RankedEntry> everything =
                service().read(READER, Optional.empty(), 100).items();
        assertThat(everything).hasSize(30);

        var seen = new ArrayList<RankedEntry>();
        FeedService.Result page = service().read(READER, Optional.empty(), 7);
        seen.addAll(page.items());
        int round = 0;
        while (page.nextCursor().isPresent()) {
            // Arrivals after the first page: newer than the snapshot, on titles that would reshuffle affinities.
            clock.advance(Duration.ofSeconds(5));
            for (int k = 0; k < 3; k++) {
                graph.follow(READER, user("late" + round + "-" + k));
                store.putInTimeline(
                        READER, entry("late" + round + "-" + k, "title" + (k % 6), NOW.plusSeconds(1 + round)));
            }
            round++;
            page = service().read(READER, page.nextCursor(), 7);
            seen.addAll(page.items());
        }

        assertThat(seen)
                .as("exactly what the first page promised, in the same order")
                .isEqualTo(everything);
        assertThat(new HashSet<>(seen.stream().map(r -> r.entry().member()).toList()))
                .as("no repeat")
                .hasSize(seen.size());

        List<RankedEntry> fresh = service().read(READER, Optional.empty(), 100).items();
        assertThat(fresh).as("a fresh fetch sees the arrivals").hasSizeGreaterThan(30);
    }

    @Test
    @DisplayName("a garbage cursor is rejected, not treated as the first page")
    void anInvalidCursorIsRejected() {
        followAndPutInTimeline("friend", "dune", Duration.ofMinutes(10));

        assertThatThrownBy(() -> service().read(READER, Optional.of("garbage"), 20))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    @DisplayName("a cursor issued to another reader is rejected")
    void aForeignCursorIsRejected() {
        for (int i = 0; i < 5; i++) {
            followAndPutInTimeline("a" + i, "t" + i, Duration.ofMinutes(i + 1));
        }
        String token = service().read(READER, Optional.empty(), 2).nextCursor().orElseThrow();

        assertThatThrownBy(() -> service().read(user("someone-else"), Optional.of(token), 2))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    @DisplayName("only the most followed celebrities are merged when the reader follows more than the cap")
    void onlyTheMostFollowedCelebritiesAreMerged() {
        int[] followers = {THRESHOLD, THRESHOLD + 4, THRESHOLD + 2};
        for (int i = 0; i < followers.length; i++) {
            graph.follow(READER, user("star" + i));
            graph.addFollowers(user("star" + i), "fan" + i + "-", followers[i] - 1);
            store.appendToOutbox(entry("star" + i, "t" + i, NOW.minusSeconds(60)));
        }

        service(2).read(READER, Optional.empty(), 20);

        assertThat(store.outboxLookups()).hasSize(1);
        assertThat(store.outboxLookups().getFirst()).containsExactlyInAnyOrder(user("star1"), user("star2"));
        assertThat(metrics.celebrityMerges).containsExactly(2);
    }

    @Test
    @DisplayName("a reader who follows no celebrity triggers no outbox lookup and no merge")
    void noCelebrityNoLookup() {
        followAndPutInTimeline("friend", "dune", Duration.ofMinutes(1));

        service().read(READER, Optional.empty(), 20);

        assertThat(store.outboxLookups()).isEmpty();
        assertThat(metrics.celebrityMerges).containsExactly(0);
    }

    @Test
    @DisplayName("an empty feed has no cursor")
    void anEmptyFeedHasNoCursor() {
        FeedService.Result result = service().read(READER, Optional.empty(), 20);

        assertThat(result.items()).isEmpty();
        assertThat(result.nextCursor()).isEmpty();
    }

    @Test
    @DisplayName("each read reports its latency")
    void eachReadReportsItsLatency() {
        service().read(READER, Optional.empty(), 20);

        assertThat(metrics.feedReads).hasSize(1);
    }
}

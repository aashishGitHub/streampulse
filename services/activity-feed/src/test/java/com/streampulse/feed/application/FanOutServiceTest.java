/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.application;

import static com.streampulse.feed.support.Fixtures.NOW;
import static com.streampulse.feed.support.Fixtures.user;
import static com.streampulse.feed.support.Fixtures.watch;
import static org.assertj.core.api.Assertions.assertThat;

import com.streampulse.feed.domain.FanOutPolicy;
import com.streampulse.feed.domain.FanOutStrategy;
import com.streampulse.feed.domain.FeedEntry;
import com.streampulse.feed.domain.WatchStarted;
import com.streampulse.feed.support.InMemoryFollowGraph;
import com.streampulse.feed.support.InMemoryTimelineStore;
import com.streampulse.feed.support.RecordingMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FanOutServiceTest {

    private static final int THRESHOLD = 5;

    private final InMemoryFollowGraph graph = new InMemoryFollowGraph();
    private final InMemoryTimelineStore store = new InMemoryTimelineStore();
    private final RecordingMetrics metrics = new RecordingMetrics();
    private final FanOutService service = new FanOutService(graph, store, new FanOutPolicy(THRESHOLD), metrics, 500);

    @Test
    @DisplayName("below the threshold the entry reaches every follower's timeline and the author's outbox")
    void normalAuthorIsWrittenToEveryFollowerAndTheOutbox() {
        graph.addFollowers(user("alice"), "f", THRESHOLD - 1);
        WatchStarted watch = watch("alice", "dune", NOW);

        FanOutService.Outcome outcome = service.handle(watch);

        assertThat(outcome).isEqualTo(new FanOutService.Outcome(FanOutStrategy.ON_WRITE, THRESHOLD - 1));
        for (int i = 0; i < THRESHOLD - 1; i++) {
            assertThat(store.timelineOf(user("f" + i))).containsExactly(FeedEntry.of(watch));
        }
        assertThat(store.outboxOf(user("alice"))).containsExactly(FeedEntry.of(watch));
        assertThat(store.hasTimeline(user("alice")))
                .as("never the author's own timeline")
                .isFalse();
    }

    @Test
    @DisplayName("at exactly the threshold only the outbox is written: no follower timeline exists")
    void authorAtTheThresholdIsWrittenToTheOutboxOnly() {
        graph.addFollowers(user("star"), "f", THRESHOLD);
        WatchStarted watch = watch("star", "dune", NOW);

        FanOutService.Outcome outcome = service.handle(watch);

        assertThat(outcome).isEqualTo(new FanOutService.Outcome(FanOutStrategy.ON_READ, 0));
        for (int i = 0; i < THRESHOLD; i++) {
            assertThat(store.hasTimeline(user("f" + i))).as("follower f%d", i).isFalse();
        }
        assertThat(store.outboxOf(user("star"))).containsExactly(FeedEntry.of(watch));
        assertThat(store.pushSizes()).isEmpty();
    }

    @Test
    @DisplayName("an author with no followers still gets an outbox entry and writes no timeline")
    void anAuthorWithNoFollowersOnlyHasAnOutbox() {
        service.handle(watch("loner", "dune", NOW));

        assertThat(store.outboxOf(user("loner"))).hasSize(1);
        assertThat(store.pushSizes()).isEmpty();
    }

    @Test
    @DisplayName("the same event delivered twice leaves exactly one entry per follower and one in the outbox")
    void redeliveredEventLeavesOneEntryPerFollower() {
        graph.addFollowers(user("alice"), "f", 3);
        WatchStarted watch = watch("alice", "dune", NOW);

        service.handle(watch);
        service.handle(watch);

        for (int i = 0; i < 3; i++) {
            assertThat(store.timelineOf(user("f" + i))).hasSize(1);
        }
        assertThat(store.outboxOf(user("alice"))).hasSize(1);
    }

    @Test
    @DisplayName("two separate events by one author are two entries: only the event id is the identity")
    void twoDistinctEventsAreTwoEntries() {
        graph.addFollowers(user("alice"), "f", 2);

        service.handle(watch("alice", "dune", NOW));
        service.handle(watch("alice", "dune", NOW.plusSeconds(60)));

        assertThat(store.timelineOf(user("f0"))).hasSize(2);
    }

    @Test
    @DisplayName("followers are read and written one page at a time, so memory is bounded by the page")
    void followersAreWrittenInPages() {
        var paged = new FanOutService(graph, store, new FanOutPolicy(100), metrics, 2);
        graph.addFollowers(user("alice"), "f", 5);

        paged.handle(watch("alice", "dune", NOW));

        assertThat(store.pushSizes()).containsExactly(2, 2, 1);
        for (int i = 0; i < 5; i++) {
            assertThat(store.timelineOf(user("f" + i))).hasSize(1);
        }
    }

    @Test
    @DisplayName("metrics record the strategy used and the number of timeline writes")
    void metricsCountWritesAndStrategy() {
        graph.addFollowers(user("alice"), "f", 3);
        graph.addFollowers(user("star"), "g", THRESHOLD);

        service.handle(watch("alice", "dune", NOW));
        service.handle(watch("star", "dune", NOW));

        assertThat(metrics.fanOuts)
                .containsExactly(
                        new RecordingMetrics.FanOut(FanOutStrategy.ON_WRITE, 3),
                        new RecordingMetrics.FanOut(FanOutStrategy.ON_READ, 0));
    }
}

/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.application;

import com.streampulse.feed.domain.FanOutPolicy;
import com.streampulse.feed.domain.FanOutStrategy;
import com.streampulse.feed.domain.FeedEntry;
import com.streampulse.feed.domain.FollowGraph;
import com.streampulse.feed.domain.TimelineStore;
import com.streampulse.feed.domain.UserId;
import com.streampulse.feed.domain.WatchStarted;
import java.util.List;
import java.util.Optional;

/**
 * The write side of the hybrid. Idempotent as a whole: every store write is, so the caller may
 * run it again after any failure.
 */
public final class FanOutService {

    public record Outcome(FanOutStrategy strategy, int timelineWrites) {}

    private final FollowGraph graph;
    private final TimelineStore store;
    private final FanOutPolicy policy;
    private final FeedMetrics metrics;
    private final int followerPageSize;

    public FanOutService(
            FollowGraph graph, TimelineStore store, FanOutPolicy policy, FeedMetrics metrics, int followerPageSize) {
        this.graph = graph;
        this.store = store;
        this.policy = policy;
        this.metrics = metrics;
        this.followerPageSize = followerPageSize;
    }

    public Outcome handle(WatchStarted watch) {
        FeedEntry entry = FeedEntry.of(watch);
        FanOutStrategy strategy = policy.strategyFor(graph.followerCount(watch.userId()));

        // Always, whichever strategy applies: an author who crosses the threshold later still has
        // the entries the read path now has to find.
        store.appendToOutbox(entry);

        int written = strategy == FanOutStrategy.ON_WRITE ? pushToFollowers(watch.userId(), entry) : 0;
        metrics.fanOut(strategy, written);
        return new Outcome(strategy, written);
    }

    /**
     * Keyset pages, not one query for "all followers": the count that chose this strategy can be
     * stale by the time followers are read, and a page bounds memory whatever the real number is.
     */
    private int pushToFollowers(UserId author, FeedEntry entry) {
        int written = 0;
        Optional<UserId> after = Optional.empty();
        while (true) {
            List<UserId> page = graph.followersAfter(author, after, followerPageSize);
            if (page.isEmpty()) {
                return written;
            }
            store.pushToTimelines(page, entry);
            written += page.size();
            if (page.size() < followerPageSize) {
                return written;
            }
            after = Optional.of(page.getLast());
        }
    }
}

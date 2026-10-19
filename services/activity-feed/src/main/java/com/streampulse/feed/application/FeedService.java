/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.application;

import com.streampulse.feed.domain.Cursor;
import com.streampulse.feed.domain.CursorCodec;
import com.streampulse.feed.domain.FanOutPolicy;
import com.streampulse.feed.domain.FeedEntry;
import com.streampulse.feed.domain.FeedMerger;
import com.streampulse.feed.domain.FeedPage;
import com.streampulse.feed.domain.FeedPager;
import com.streampulse.feed.domain.FollowGraph;
import com.streampulse.feed.domain.Followee;
import com.streampulse.feed.domain.RankedEntry;
import com.streampulse.feed.domain.Ranker;
import com.streampulse.feed.domain.TimelineStore;
import com.streampulse.feed.domain.UserId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** The read side of the hybrid: precomputed timeline plus celebrity outboxes, merged, ranked, paged. */
public final class FeedService {

    public record Limits(int timelineWindow, int celebrityWindow, int maxCelebrities, int maxFollowees) {}

    public record Result(List<RankedEntry> items, Optional<String> nextCursor) {}

    private final FollowGraph graph;
    private final TimelineStore store;
    private final FanOutPolicy policy;
    private final FeedMerger merger;
    private final Ranker ranker;
    private final FeedPager pager;
    private final CursorCodec cursors;
    private final FeedMetrics metrics;
    private final Clock clock;
    private final Limits limits;

    public FeedService(
            FollowGraph graph,
            TimelineStore store,
            FanOutPolicy policy,
            FeedMerger merger,
            Ranker ranker,
            FeedPager pager,
            CursorCodec cursors,
            FeedMetrics metrics,
            Clock clock,
            Limits limits) {
        this.graph = graph;
        this.store = store;
        this.policy = policy;
        this.merger = merger;
        this.ranker = ranker;
        this.pager = pager;
        this.cursors = cursors;
        this.metrics = metrics;
        this.clock = clock;
        this.limits = limits;
    }

    public Result read(UserId reader, Optional<String> cursorToken, int limit) {
        long started = System.nanoTime();
        // Decoded first: a bad cursor is a 400 before any store is touched.
        Optional<Cursor> cursor = cursorToken.map(token -> cursors.decode(reader, token));
        // The snapshot instant. Pinned by the cursor so every page ranks the same candidates at the same time.
        Instant asOf = cursor.map(c -> Instant.ofEpochMilli(c.asOfMillis())).orElseGet(clock::instant);

        List<Followee> followees = graph.followeesOf(reader, limits.maxFollowees());
        Set<UserId> followed = new HashSet<>();
        followees.forEach(f -> followed.add(f.id()));

        List<UserId> celebrities = followees.stream()
                .filter(f -> policy.isCelebrity(f.followers()))
                .sorted(Comparator.comparingLong(Followee::followers).reversed())
                .limit(limits.maxCelebrities())
                .map(Followee::id)
                .toList();

        List<FeedEntry> timeline = store.timeline(reader, asOf, limits.timelineWindow());
        List<FeedEntry> fromCelebrities =
                celebrities.isEmpty() ? List.of() : store.outboxes(celebrities, asOf, limits.celebrityWindow());
        metrics.celebrityOutboxesMerged(celebrities.size());

        List<RankedEntry> ranked = ranker.rank(merger.merge(timeline, fromCelebrities, followed, asOf), asOf);
        FeedPage page = pager.page(ranked, cursor, limit, asOf.toEpochMilli());

        metrics.feedRead(Duration.ofNanos(System.nanoTime() - started));
        return new Result(page.items(), page.next().map(next -> cursors.encode(reader, next)));
    }
}

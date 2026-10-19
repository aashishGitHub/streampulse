/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.application;

import com.streampulse.feed.domain.FanOutPolicy;
import com.streampulse.feed.domain.FanOutStrategy;
import com.streampulse.feed.domain.FollowGraph;
import com.streampulse.feed.domain.InvalidFeedRequestException;
import com.streampulse.feed.domain.UserId;

public final class FollowService {

    public record Result(boolean changed, long followerCount) {}

    public record Summary(UserId user, long followers, FanOutStrategy strategy) {}

    private final FollowGraph graph;
    private final FanOutPolicy policy;

    public FollowService(FollowGraph graph, FanOutPolicy policy) {
        this.graph = graph;
        this.policy = policy;
    }

    public Result follow(UserId follower, UserId followee) {
        if (follower.equals(followee)) {
            throw new InvalidFeedRequestException("a user cannot follow themselves");
        }
        boolean changed = graph.follow(follower, followee);
        return new Result(changed, graph.followerCount(followee));
    }

    public Result unfollow(UserId follower, UserId followee) {
        boolean changed = graph.unfollow(follower, followee);
        return new Result(changed, graph.followerCount(followee));
    }

    public Summary summary(UserId user) {
        long followers = graph.followerCount(user);
        return new Summary(user, followers, policy.strategyFor(followers));
    }
}

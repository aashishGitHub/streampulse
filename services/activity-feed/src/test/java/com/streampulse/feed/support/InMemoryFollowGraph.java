/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.support;

import com.streampulse.feed.domain.FollowGraph;
import com.streampulse.feed.domain.Followee;
import com.streampulse.feed.domain.UserId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

public final class InMemoryFollowGraph implements FollowGraph {

    private static final Comparator<UserId> BY_ID = Comparator.comparing(UserId::value);

    private final Map<UserId, TreeSet<UserId>> followersOf = new HashMap<>();
    private final Map<UserId, TreeSet<UserId>> followeesOf = new HashMap<>();
    private final List<Integer> followerPageSizes = new ArrayList<>();

    @Override
    public boolean follow(UserId follower, UserId followee) {
        followeesOf.computeIfAbsent(follower, k -> new TreeSet<>(BY_ID)).add(followee);
        return followersOf.computeIfAbsent(followee, k -> new TreeSet<>(BY_ID)).add(follower);
    }

    @Override
    public boolean unfollow(UserId follower, UserId followee) {
        var followees = followeesOf.get(follower);
        if (followees != null) {
            followees.remove(followee);
        }
        var followers = followersOf.get(followee);
        return followers != null && followers.remove(follower);
    }

    @Override
    public long followerCount(UserId user) {
        return followersOf.getOrDefault(user, new TreeSet<>(BY_ID)).size();
    }

    @Override
    public List<UserId> followersAfter(UserId author, Optional<UserId> after, int limit) {
        List<UserId> page = followersOf.getOrDefault(author, new TreeSet<>(BY_ID)).stream()
                .filter(f -> after.isEmpty() || BY_ID.compare(f, after.get()) > 0)
                .limit(limit)
                .toList();
        followerPageSizes.add(page.size());
        return page;
    }

    @Override
    public List<Followee> followeesOf(UserId reader, int max) {
        return followeesOf.getOrDefault(reader, new TreeSet<>(BY_ID)).stream()
                .limit(max)
                .map(id -> new Followee(id, followerCount(id)))
                .toList();
    }

    /** The size of each page of followers handed out, in order. */
    public List<Integer> followerPageSizes() {
        return List.copyOf(followerPageSizes);
    }

    public void addFollowers(UserId author, String prefix, int count) {
        for (int i = 0; i < count; i++) {
            follow(new UserId(prefix + i), author);
        }
    }
}

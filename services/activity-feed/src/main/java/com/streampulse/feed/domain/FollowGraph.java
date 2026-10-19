/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.domain;

import java.util.List;
import java.util.Optional;

/** The follow graph. Every method is one indexed query. */
public interface FollowGraph {

    /** True when this call created the follow; false when it already existed. */
    boolean follow(UserId follower, UserId followee);

    /** True when this call removed the follow; false when there was none. */
    boolean unfollow(UserId follower, UserId followee);

    long followerCount(UserId user);

    /** Followers in id order, strictly after {@code after}: keyset paging, so a large author never needs an offset. */
    List<UserId> followersAfter(UserId author, Optional<UserId> after, int limit);

    /** Whom the reader follows, each with their follower count, at most {@code max}. */
    List<Followee> followeesOf(UserId reader, int max);
}

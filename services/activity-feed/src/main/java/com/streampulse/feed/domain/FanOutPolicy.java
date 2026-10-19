/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.domain;

/** The one rule that decides between pushing and pulling, per author. */
public record FanOutPolicy(long celebrityThreshold) {

    public FanOutPolicy {
        if (celebrityThreshold <= 0) {
            throw new IllegalArgumentException("celebrityThreshold must be positive, got " + celebrityThreshold);
        }
    }

    public FanOutStrategy strategyFor(long followerCount) {
        return isCelebrity(followerCount) ? FanOutStrategy.ON_READ : FanOutStrategy.ON_WRITE;
    }

    /** {@code >=}: at the threshold the push would already cost as many writes as the threshold says is too many. */
    public boolean isCelebrity(long followerCount) {
        return followerCount >= celebrityThreshold;
    }
}

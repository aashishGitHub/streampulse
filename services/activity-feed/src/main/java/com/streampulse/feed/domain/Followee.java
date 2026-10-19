/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.domain;

/** Someone a reader follows, with the follower count that decides how their entries reach the reader. */
public record Followee(UserId id, long followers) {}

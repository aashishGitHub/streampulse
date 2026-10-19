/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.domain;

public enum FanOutStrategy {
    /** Push the entry into every follower's timeline when the author watches. */
    ON_WRITE,
    /** Leave it in the author's outbox; followers pull it when they read. */
    ON_READ
}

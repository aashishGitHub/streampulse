/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.application;

import com.streampulse.feed.domain.FanOutStrategy;
import java.time.Duration;

public interface FeedMetrics {

    /** One watch event fanned out: the strategy chosen and how many follower timelines were written. */
    void fanOut(FanOutStrategy strategy, int timelineWrites);

    /** One feed read merged this many celebrity outboxes. */
    void celebrityOutboxesMerged(int outboxes);

    void feedRead(Duration elapsed);

    void deadLettered();
}

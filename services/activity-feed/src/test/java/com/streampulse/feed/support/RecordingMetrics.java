/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.support;

import com.streampulse.feed.application.FeedMetrics;
import com.streampulse.feed.domain.FanOutStrategy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class RecordingMetrics implements FeedMetrics {

    public record FanOut(FanOutStrategy strategy, int timelineWrites) {}

    public final List<FanOut> fanOuts = new ArrayList<>();
    public final List<Integer> celebrityMerges = new ArrayList<>();
    public final List<Duration> feedReads = new ArrayList<>();
    public int deadLetters;

    @Override
    public void fanOut(FanOutStrategy strategy, int timelineWrites) {
        fanOuts.add(new FanOut(strategy, timelineWrites));
    }

    @Override
    public void celebrityOutboxesMerged(int outboxes) {
        celebrityMerges.add(outboxes);
    }

    @Override
    public void feedRead(Duration elapsed) {
        feedReads.add(elapsed);
    }

    @Override
    public void deadLettered() {
        deadLetters++;
    }
}

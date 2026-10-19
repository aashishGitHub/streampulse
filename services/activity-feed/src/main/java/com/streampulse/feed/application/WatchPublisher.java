/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.application;

import com.streampulse.feed.domain.WatchStarted;

public interface WatchPublisher {

    /** Returns only once the event is durably recorded; throws {@link WatchUnavailableException} otherwise. */
    void publish(WatchStarted watch);
}

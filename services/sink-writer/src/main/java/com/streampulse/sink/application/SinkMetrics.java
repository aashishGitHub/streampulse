/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.sink.application;

import java.time.Duration;

public interface SinkMetrics {

    /** Window end to durable write: how stale the stored value was when it landed. */
    void freshness(Duration lag);

    void projectionFailed();
}

/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.sink.domain;

import java.util.List;

/** The fast-read view, derived from the durable store and rebuildable from it. */
public interface MetricProjection {

    void project(List<MetricWindow> windows);
}

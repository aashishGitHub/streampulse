/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.query.application;

import com.streampulse.query.domain.LatestMetric;
import java.util.List;

/** Blocking by design: the caller runs it on the bounded executor, never on an event loop. */
public interface LatestMetricsPort {

    List<LatestMetric> latest(String tenantId, String titleId);
}

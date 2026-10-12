/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.query.application;

import com.streampulse.query.domain.MetricPoint;
import java.util.List;

/** Blocking by design; windows with {@code from <= windowStart < to}, oldest first. */
public interface MetricHistoryPort {

    List<MetricPoint> range(HistoryQuery query);
}

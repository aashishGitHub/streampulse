/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.query.application;

import com.streampulse.query.domain.MetricPoint;
import java.util.List;

/** {@code source} says which tier answered, so a slow response can be explained rather than guessed at. */
public record MetricQueryResult(List<MetricPoint> windows, String source) {

    public static final String STORE = "store";
}

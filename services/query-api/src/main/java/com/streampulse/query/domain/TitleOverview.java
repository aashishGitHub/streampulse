/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.query.domain;

import java.util.List;

/**
 * {@code degraded} names the parts that are empty because they failed, not because there is no
 * data: {@code latest}, {@code history}, {@code metadata}. {@code metadata} is null then.
 */
public record TitleOverview(
        String tenantId,
        String titleId,
        TitleMetadata metadata,
        List<LatestMetric> latest,
        List<MetricPoint> history,
        List<String> degraded) {

    public TitleOverview {
        latest = List.copyOf(latest);
        history = List.copyOf(history);
        degraded = List.copyOf(degraded);
    }
}

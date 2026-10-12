/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.sink.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * One aggregate as the stores hold it. The fields are exactly the columns of
 * {@code hot-store-contract.md}; identity is (tenant, title, region, metric, windowStart).
 *
 * @param titleId empty when the aggregate has no title
 * @param region empty when the aggregate spans all regions; the contract's column is NOT NULL
 * @param windowEnd exclusive
 */
public record MetricWindow(
        String tenantId,
        String titleId,
        String region,
        String metric,
        Instant windowStart,
        Instant windowEnd,
        double value,
        long sampleCount,
        Instant computedAt) {

    public MetricWindow {
        titleId = titleId == null ? "" : titleId;
        region = region == null ? "" : region;
        requireText(tenantId, "tenantId", true);
        requireText(metric, "metric", true);
        requireText(titleId, "titleId", false);
        requireText(region, "region", false);
        Objects.requireNonNull(windowStart, "windowStart");
        Objects.requireNonNull(windowEnd, "windowEnd");
        Objects.requireNonNull(computedAt, "computedAt");
        if (!windowEnd.isAfter(windowStart)) {
            throw new IllegalArgumentException("windowEnd must be after windowStart");
        }
    }

    /** Postgres text cannot hold NUL: such a value would be rejected on every attempt, forever. */
    private static void requireText(String value, String name, boolean notBlank) {
        if (value == null || (notBlank && value.isBlank())) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException(name + " must not contain NUL");
        }
    }

    /** The series this window belongs to, as the live feed and the latest-value hash name it. */
    public String seriesField() {
        return titleId + ":" + region + ":" + metric;
    }
}

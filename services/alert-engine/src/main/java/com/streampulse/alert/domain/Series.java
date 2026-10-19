/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.domain;

import java.util.Objects;

/**
 * One metric stream of one tenant, narrowed by title and region. A null title or region means
 * "across all", which is how the aggregate contract spells it.
 */
public record Series(String tenantId, String titleId, String region, String metric) {

    public static final String ALL = "*";

    public Series {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(metric, "metric");
    }

    /** {@code titleId:region:metric}, with {@code *} for "all". Shown to users and used in ids. */
    public String label() {
        return (titleId == null ? ALL : titleId) + ":" + (region == null ? ALL : region) + ":" + metric;
    }
}

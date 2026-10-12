/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.query.domain;

import java.time.Instant;

/** Builders so a test states only the fields it is about. */
public final class MetricUpdates {

    private MetricUpdates() {}

    public static MetricUpdate of(String id, String tenantId, String titleId, String metric, double value) {
        return new MetricUpdate(
                EntryId.parse(id),
                tenantId,
                titleId,
                "us-east-1",
                metric,
                Instant.parse("2026-01-15T12:00:00Z"),
                Instant.parse("2026-01-15T12:00:10Z"),
                value,
                10,
                Instant.parse("2026-01-15T12:00:16Z"));
    }
}

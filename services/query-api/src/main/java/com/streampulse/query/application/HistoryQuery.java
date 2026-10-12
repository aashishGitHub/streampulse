/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.query.application;

import java.time.Instant;

/** {@code metric}, {@code titleId} and {@code region} are optional filters (null means any). */
public record HistoryQuery(String tenantId, String metric, String titleId, String region, Instant from, Instant to) {}

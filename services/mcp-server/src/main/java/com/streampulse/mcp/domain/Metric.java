/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.mcp.domain;

import java.util.Optional;
import java.util.Set;

/** The metrics query-api produces (api/openapi/query-v1.yaml). */
public enum Metric {
    CONCURRENT_VIEWERS,
    REBUFFER_RATIO,
    ERROR_RATE,
    STARTUP_TIME_MS,
    AVG_BITRATE_KBPS;

    /** What get_live_metrics reports. */
    public static final Set<Metric> LIVE = Set.of(CONCURRENT_VIEWERS, ERROR_RATE);

    public static Optional<Metric> parse(String raw) {
        for (Metric m : values()) {
            if (m.name().equals(raw)) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }
}

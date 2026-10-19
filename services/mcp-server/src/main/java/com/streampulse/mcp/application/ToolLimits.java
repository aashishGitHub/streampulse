/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.mcp.application;

import java.time.Duration;

/** Every bound in one place. The defaults are targets, not measured values. */
public record ToolLimits(int maxAlerts, int maxMetricRows, int maxRecentWindows, int textChars, Duration lookback) {

    public ToolLimits {
        if (maxAlerts < 1
                || maxMetricRows < 1
                || maxRecentWindows < 1
                || textChars < 1
                || lookback.isNegative()
                || lookback.isZero()) {
            throw new IllegalArgumentException("limits must be positive");
        }
    }

    public static ToolLimits defaults() {
        return new ToolLimits(50, 100, 20, 200, Duration.ofMinutes(15));
    }
}

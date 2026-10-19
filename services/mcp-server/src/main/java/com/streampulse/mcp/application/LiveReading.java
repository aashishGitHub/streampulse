/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.mcp.application;

import com.streampulse.mcp.domain.MetricWindow;

public record LiveReading(MetricWindow window, long ageSeconds, AgeBasis basis) {}

/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.mcp.application;

import com.streampulse.platform.domain.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public record LiveMetrics(
        TenantId tenant,
        Optional<String> titleId,
        Instant at,
        List<LiveReading> readings,
        int total,
        int omitted,
        List<String> degraded) {}

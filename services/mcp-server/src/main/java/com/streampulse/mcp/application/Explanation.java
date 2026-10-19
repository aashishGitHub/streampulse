/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.mcp.application;

import com.streampulse.mcp.domain.AlertSnapshot;
import com.streampulse.mcp.domain.Series;
import com.streampulse.platform.domain.TenantId;
import java.time.Instant;
import java.util.Optional;

public record Explanation(
        TenantId tenant,
        String requestedAlertId,
        Instant at,
        Optional<AlertSnapshot> alert,
        long activeForSeconds,
        Optional<AlertSnapshot> rootCause,
        Optional<Series> series,
        RecentMetrics recent) {}

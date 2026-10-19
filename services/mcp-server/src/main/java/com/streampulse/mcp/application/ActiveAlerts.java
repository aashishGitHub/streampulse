/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.mcp.application;

import com.streampulse.mcp.domain.AlertSnapshot;
import com.streampulse.platform.domain.TenantId;
import java.time.Instant;
import java.util.List;

/** {@code *Total} are counts after the tenant filter and before the item limit; {@code omitted} is the cut. */
public record ActiveAlerts(
        TenantId tenant,
        Instant at,
        List<AlertSnapshot> firing,
        List<AlertSnapshot> suppressed,
        int firingTotal,
        int suppressedTotal,
        int omitted) {}

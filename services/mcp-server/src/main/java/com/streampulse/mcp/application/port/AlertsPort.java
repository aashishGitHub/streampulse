/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.mcp.application.port;

import com.streampulse.mcp.application.BackendFailure;
import com.streampulse.mcp.domain.AlertSnapshot;
import com.streampulse.platform.domain.TenantId;
import java.util.List;

/** Read-only by construction: one query method. ArchUnit rejects write-sounding names here. */
public interface AlertsPort {

    /** @throws BackendFailure when alert-engine gives no usable answer */
    List<AlertSnapshot> activeAlerts(TenantId tenant);
}

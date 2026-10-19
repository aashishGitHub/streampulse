/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.compactor.domain;

import com.streampulse.platform.domain.TenantId;
import java.time.LocalDate;

/** One tenant's one hour: the unit of compaction. */
public record PartitionId(TenantId tenant, LocalDate date, int hour) {

    public PartitionId {
        if (hour < 0 || hour > 23) {
            throw new IllegalArgumentException("hour must be 0-23: " + hour);
        }
    }

    public String prefix() {
        return "tenant=%s/dt=%s/hour=%02d/".formatted(tenant.value(), date, hour);
    }
}

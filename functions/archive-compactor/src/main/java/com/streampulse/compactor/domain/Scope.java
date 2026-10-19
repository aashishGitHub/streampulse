/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.compactor.domain;

import com.streampulse.platform.domain.TenantId;
import java.time.LocalDate;

/** What a run covers. A date needs a tenant, because the key layout puts the tenant first. */
public record Scope(TenantId tenant, LocalDate date) {

    public Scope {
        if (date != null && tenant == null) {
            throw new IllegalArgumentException("a date scope needs a tenant");
        }
    }

    public static Scope all() {
        return new Scope(null, null);
    }

    public static Scope of(TenantId tenant, LocalDate date) {
        return new Scope(tenant, date);
    }

    public String prefix() {
        if (tenant == null) {
            return "";
        }
        return date == null
                ? "tenant=%s/".formatted(tenant.value())
                : "tenant=%s/dt=%s/".formatted(tenant.value(), date);
    }
}

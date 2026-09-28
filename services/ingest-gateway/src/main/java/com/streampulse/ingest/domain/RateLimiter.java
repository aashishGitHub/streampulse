/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.domain;

import com.streampulse.platform.domain.TenantId;

/** Outbound port: per-tenant admission control. */
public interface RateLimiter {

    /**
     * Attempts to consume {@code permits} from the tenant's budget.
     *
     * @return true if allowed. Implementations must be <b>atomic</b>: a read-then-write
     *     would let concurrent requests both see spare capacity and both proceed.
     */
    boolean tryConsume(TenantId tenant, int permits);

    /** Always allows. For unit tests that are not about rate limiting. */
    static RateLimiter allowAll() {
        return (tenant, permits) -> true;
    }
}

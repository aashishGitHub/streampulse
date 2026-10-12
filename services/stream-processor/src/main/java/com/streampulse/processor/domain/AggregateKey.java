/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.processor.domain;

import com.streampulse.platform.domain.TenantId;

/**
 * The unit a metric is aggregated over.
 *
 * @param titleId nullable; null is its own group so events without a title never inflate a real one
 */
public record AggregateKey(String tenantId, String titleId, String region) {

    public static AggregateKey of(Observation event) {
        return new AggregateKey(new TenantId(event.tenantId()).value(), event.titleId(), event.region());
    }

    /**
     * The grouping key, as a string.
     *
     * <p>The parts are not recovered from it: tenant ids cannot contain a colon, but a title or
     * region might. Anything that needs the parts reads them from {@link WindowAccumulator#key()}.
     */
    public String asString() {
        return tenantId + ":" + (titleId == null ? "" : titleId) + ":" + region;
    }
}

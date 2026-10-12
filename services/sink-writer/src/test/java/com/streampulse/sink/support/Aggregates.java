/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.sink.support;

import com.streampulse.contracts.codec.AvroBinaryCodec;
import com.streampulse.contracts.v1.MetricAggregate;
import com.streampulse.contracts.v1.MetricName;
import com.streampulse.sink.domain.MetricWindow;
import java.time.Instant;

/** Test fixtures: one aggregate, as a domain value and as the wire bytes the processor publishes. */
public final class Aggregates {

    public static final Instant WINDOW_START = Instant.parse("2026-01-15T12:00:00Z");
    private static final AvroBinaryCodec<MetricAggregate> CODEC = AvroBinaryCodec.of(MetricAggregate.class);

    private Aggregates() {}

    public static MetricAggregate.Builder avro(String tenant, String title, String region, Instant windowStart) {
        return MetricAggregate.newBuilder()
                .setTenantId(tenant)
                .setTitleId(title)
                .setRegion(region)
                .setMetric(MetricName.CONCURRENT_VIEWERS)
                .setWindowStart(windowStart)
                .setWindowEndExclusive(windowStart.plusSeconds(10))
                .setValue(42.5)
                .setSampleCount(17)
                .setComputedAt(windowStart.plusSeconds(15))
                .setIsFinal(true);
    }

    public static byte[] bytes(MetricAggregate aggregate) {
        return CODEC.encode(aggregate);
    }

    public static MetricWindow window(String tenant, String title, String region, Instant windowStart, double value) {
        return new MetricWindow(
                tenant,
                title,
                region,
                "CONCURRENT_VIEWERS",
                windowStart,
                windowStart.plusSeconds(10),
                value,
                17,
                windowStart.plusSeconds(15));
    }

    /** The key the stream processor gives it: tenant:title:region:metric:windowStartMillis. */
    public static String key(MetricAggregate a) {
        return a.getTenantId() + ":" + a.getTitleId() + ":" + a.getRegion() + ":" + a.getMetric() + ":"
                + a.getWindowStart().toEpochMilli();
    }
}

/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.support;

import com.streampulse.contracts.codec.AvroBinaryCodec;
import com.streampulse.contracts.v1.AlertEvent;
import com.streampulse.contracts.v1.ChangeOperation;
import com.streampulse.contracts.v1.ConfigChange;
import com.streampulse.contracts.v1.EntityType;
import com.streampulse.contracts.v1.MetricAggregate;
import com.streampulse.contracts.v1.MetricName;
import java.time.Instant;

/** Bytes as they appear on the topics. */
public final class Wire {

    private static final AvroBinaryCodec<MetricAggregate> AGGREGATE = AvroBinaryCodec.of(MetricAggregate.class);
    private static final AvroBinaryCodec<ConfigChange> CONFIG = AvroBinaryCodec.of(ConfigChange.class);
    private static final AvroBinaryCodec<AlertEvent> ALERT = AvroBinaryCodec.of(AlertEvent.class);

    private Wire() {}

    public static MetricAggregate.Builder aggregate(
            String tenant, String title, String region, MetricName metric, int windowIndex, double value) {
        Instant start = Fixtures.T0.plus(Fixtures.WINDOW.multipliedBy(windowIndex));
        return MetricAggregate.newBuilder()
                .setTenantId(tenant)
                .setTitleId(title)
                .setRegion(region)
                .setMetric(metric)
                .setWindowStart(start)
                .setWindowEndExclusive(start.plus(Fixtures.WINDOW))
                .setValue(value)
                .setSampleCount(10)
                .setComputedAt(start.plus(Fixtures.WINDOW).plusSeconds(5))
                .setIsFinal(true);
    }

    public static byte[] bytes(MetricAggregate aggregate) {
        return AGGREGATE.encode(aggregate);
    }

    public static String key(MetricAggregate a) {
        return a.getTenantId() + ":" + a.getTitleId() + ":" + a.getRegion() + ":" + a.getMetric() + ":"
                + a.getWindowStart().toEpochMilli();
    }

    public static ConfigChange.Builder change(
            EntityType type, String entityId, ChangeOperation operation, String payload, long version) {
        return ConfigChange.newBuilder()
                .setChangeId("change-" + entityId + "-" + version)
                .setEntityType(type)
                .setEntityId(entityId)
                .setOperation(operation)
                .setPayload(payload)
                .setVersion(version)
                .setChangedAt(Fixtures.T0);
    }

    public static byte[] ruleChange(String ruleId, String tenant, long version, String payload) {
        return CONFIG.encode(change(EntityType.ALERT_RULE, ruleId, ChangeOperation.CREATED, payload, version)
                .setTenantId(tenant)
                .build());
    }

    public static byte[] bytes(ConfigChange change) {
        return CONFIG.encode(change);
    }

    public static AlertEvent alert(byte[] bytes) {
        return ALERT.decode(bytes);
    }

    /** A rule in the control-plane's JSON shape. */
    public static String thresholdRule(
            String tenant, String metric, String comparator, double threshold, String category) {
        return """
                {"ruleId":"ignored-by-key","tenantId":"%s","metric":"%s","comparator":"%s","threshold":%s,\
                "severity":"CRITICAL","category":"%s"}"""
                .formatted(tenant, metric, comparator, threshold, category);
    }
}

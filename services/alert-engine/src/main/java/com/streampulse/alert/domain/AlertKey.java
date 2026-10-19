/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Identity of an alert slot: one rule on one series. At most one incident is active per slot. */
public record AlertKey(String ruleId, Series series) {

    public AlertKey {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(series, "series");
    }

    public String tenantId() {
        return series.tenantId();
    }

    /** The Kafka key of the alert topic. Both series of one rule share it, which keeps a rule's events ordered. */
    public String topicKey() {
        return series.tenantId() + ":" + ruleId;
    }

    /**
     * Deterministic: the same slot and the same starting window always give the same id, so a
     * restarted or duplicate engine names an incident identically and consumers can de-duplicate.
     */
    public String incidentId(Instant windowStart) {
        String name = series.tenantId() + "|" + ruleId + "|" + series.label() + "|" + windowStart.toEpochMilli();
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString();
    }
}

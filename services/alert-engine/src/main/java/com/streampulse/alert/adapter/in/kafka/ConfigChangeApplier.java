/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.adapter.in.kafka;

import com.streampulse.alert.application.AlertProcessor;
import com.streampulse.alert.application.DeliveryException;
import com.streampulse.alert.domain.AlertMetrics;
import com.streampulse.alert.domain.AlertRule;
import com.streampulse.alert.domain.RuleBook;
import com.streampulse.alert.domain.graph.CyclicDependencyException;
import com.streampulse.contracts.codec.AvroBinaryCodec;
import com.streampulse.contracts.codec.AvroDecodeException;
import com.streampulse.contracts.v1.ChangeOperation;
import com.streampulse.contracts.v1.ConfigChange;
import com.streampulse.contracts.v1.EntityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies one record of the compacted config topic to the {@link RuleBook}. Idempotent: records are
 * full snapshots, a replay re-applies the same state, and a version older than the one held is
 * ignored, so reading from the beginning (or an out-of-order replay) converges.
 *
 * <p>A delete arrives two ways and both are handled: a Kafka tombstone (null value, which is what
 * compaction needs) and a {@code ConfigChange} whose operation is DELETED or whose payload is null.
 * A tombstone carries no entity type, so it removes by key whatever the type; that is safe only
 * while rule ids and other entity ids do not collide.
 */
public final class ConfigChangeApplier {

    private static final Logger log = LoggerFactory.getLogger(ConfigChangeApplier.class);

    private final RuleBook rules;
    private final AlertProcessor processor;
    private final RuleJsonParser parser;
    private final AlertMetrics metrics;
    private final AvroBinaryCodec<ConfigChange> codec = AvroBinaryCodec.of(ConfigChange.class);

    public ConfigChangeApplier(RuleBook rules, AlertProcessor processor, RuleJsonParser parser, AlertMetrics metrics) {
        this.rules = rules;
        this.processor = processor;
        this.parser = parser;
        this.metrics = metrics;
    }

    public void apply(String key, byte[] value) {
        if (key == null) {
            metrics.rejected("config", "null-key");
            return;
        }
        if (value == null) {
            remove(key);
            return;
        }

        ConfigChange change;
        try {
            change = codec.decode(value);
        } catch (AvroDecodeException e) {
            metrics.rejected("config", "decode");
            log.warn("skipping config record key={}: not a ConfigChange", key);
            return;
        }
        if (change.getEntityType() != EntityType.ALERT_RULE) {
            return;
        }
        if (change.getOperation() == ChangeOperation.DELETED || change.getPayload() == null) {
            remove(key);
            return;
        }
        put(key, change);
    }

    private void put(String key, ConfigChange change) {
        AlertRule rule;
        try {
            rule = parser.parse(
                    key,
                    change.getTenantId() == null ? null : change.getTenantId().toString(),
                    change.getPayload().toString());
        } catch (RuleParseException e) {
            metrics.rejected("config", "parse-" + e.reason());
            log.warn("skipping rule {}: {}", key, e.getMessage());
            return;
        }

        try {
            metrics.ruleApplied(rules.put(rule, change.getVersion()).name().toLowerCase(java.util.Locale.ROOT));
        } catch (CyclicDependencyException e) {
            metrics.rejected("config", "cycle");
            log.error("rejecting rule {}: {}", key, e.getMessage());
        }
    }

    private void remove(String ruleId) {
        rules.remove(ruleId)
                .ifPresentOrElse(
                        rule -> {
                            metrics.ruleApplied("removed");
                            try {
                                processor.ruleRemoved(rule);
                            } catch (DeliveryException e) {
                                // State is already updated; the outbox holds the resolutions and the next
                                // processed aggregate delivers them.
                                log.warn("rule {} removed; delivering its resolutions failed, will retry", ruleId, e);
                            }
                        },
                        () -> metrics.ruleApplied("ignored"));
    }
}

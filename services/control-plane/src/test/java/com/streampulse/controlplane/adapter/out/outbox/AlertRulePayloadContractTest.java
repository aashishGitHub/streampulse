/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.adapter.out.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.streampulse.contracts.codec.AvroBinaryCodec;
import com.streampulse.contracts.v1.ChangeOperation;
import com.streampulse.contracts.v1.ConfigChange;
import com.streampulse.contracts.v1.EntityType;
import com.streampulse.controlplane.domain.AlertRule;
import com.streampulse.controlplane.domain.AlertSeverity;
import com.streampulse.controlplane.domain.Category;
import com.streampulse.controlplane.domain.ChangeEvent;
import com.streampulse.controlplane.domain.Comparison;
import com.streampulse.controlplane.domain.Operation;
import com.streampulse.platform.domain.TenantId;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The ALERT_RULE payload is what the alert engine's rule parser reads. The field names are asserted
 * here by hand, not by importing the engine (a service module cannot be depended on): ruleId is the
 * record key, and the payload carries tenantId, metric, comparator, threshold, severity, category,
 * dependsOn, and a {@code kind} that is the CONDITION type (THRESHOLD), never the entity kind.
 */
class AlertRulePayloadContractTest {

    private static final Instant AT = Instant.parse("2026-10-12T10:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AvroConfigChangeEncoder encoder = new AvroConfigChangeEncoder(JSON);
    private final AvroBinaryCodec<ConfigChange> codec = AvroBinaryCodec.of(ConfigChange.class);

    private static AlertRule rule() {
        return new AlertRule(
                "r-1",
                TenantId.of("acme"),
                "error_rate",
                Comparison.GTE,
                0.05,
                AlertSeverity.CRITICAL,
                Category.PLAYER,
                Set.of(Category.PACKAGER, Category.CDN),
                4);
    }

    @Test
    @DisplayName("a rule's ConfigChange is keyed by the bare rule id and carries the JSON the alert engine parses")
    void payloadHasTheFieldsTheEngineReads() throws Exception {
        ConfigChange decoded =
                codec.decode(encoder.encode(ChangeEvent.of(UUID.randomUUID(), Operation.UPDATED, rule(), AT)));

        assertThat(decoded.getEntityType()).isEqualTo(EntityType.ALERT_RULE);
        assertThat(decoded.getOperation()).isEqualTo(ChangeOperation.UPDATED);
        assertThat(decoded.getEntityId().toString())
                .as("the record key is the rule id")
                .isEqualTo("r-1");
        assertThat(decoded.getTenantId().toString()).isEqualTo("acme");
        assertThat(decoded.getVersion()).isEqualTo(4);

        JsonNode payload = JSON.readTree(decoded.getPayload().toString());
        assertThat(payload.get("ruleId").asText()).isEqualTo("r-1");
        assertThat(payload.get("tenantId").asText()).isEqualTo("acme");
        assertThat(payload.get("metric").asText()).isEqualTo("error_rate");
        assertThat(payload.get("comparator").asText()).isEqualTo("GTE");
        assertThat(payload.get("threshold").isNumber())
                .as("the engine requires a JSON number")
                .isTrue();
        assertThat(payload.get("threshold").asDouble()).isEqualTo(0.05);
        assertThat(payload.get("severity").asText()).isEqualTo("CRITICAL");
        assertThat(payload.get("category").asText()).isEqualTo("PLAYER");
        assertThat(payload.get("dependsOn").isArray()).isTrue();
        assertThat(payload.get("dependsOn")).extracting(JsonNode::asText).containsExactly("CDN", "PACKAGER");
        assertThat(payload.get("kind").asText())
                .as(
                        "the engine reads kind as THRESHOLD | RATE_OF_CHANGE | ABSENCE; an entity kind here would reject the rule")
                .isEqualTo("THRESHOLD");
    }

    @Test
    @DisplayName("a rule's delete is a null value; the key (rule id) is all the engine needs to remove it")
    void deleteIsATombstone() {
        assertThat(encoder.encode(ChangeEvent.of(UUID.randomUUID(), Operation.DELETED, rule(), AT)))
                .isNull();
        assertThat(rule().entityId()).isEqualTo("r-1");
    }
}

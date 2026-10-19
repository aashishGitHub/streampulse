/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.adapter.out;

import static com.streampulse.alert.support.Fixtures.T0;
import static org.assertj.core.api.Assertions.assertThat;

import com.streampulse.alert.adapter.out.kafka.AlertEventMapper;
import com.streampulse.alert.domain.Alert;
import com.streampulse.alert.domain.AlertKey;
import com.streampulse.alert.domain.AlertState;
import com.streampulse.alert.domain.Series;
import com.streampulse.alert.domain.Severity;
import com.streampulse.alert.support.Wire;
import com.streampulse.contracts.codec.AvroBinaryCodec;
import com.streampulse.contracts.v1.AlertCategory;
import com.streampulse.contracts.v1.AlertEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AlertEventMapperTest {

    private final AlertEventMapper mapper = new AlertEventMapper();
    private final AlertKey key = new AlertKey("r1", new Series("streamflix", "title-1", null, "ERROR_RATE"));

    private Alert alert(String node, AlertState state) {
        var firing = new Alert(
                key,
                "id-1",
                AlertState.FIRING,
                Severity.WARNING,
                node,
                "My rule",
                "summary",
                T0,
                T0,
                T0.plusSeconds(5),
                null,
                null,
                0.2,
                0.05);
        return switch (state) {
            case FIRING -> firing;
            case SUPPRESSED -> firing.suppressed("root-id", T0.plusSeconds(5));
            case RESOLVED -> firing.resolved(T0.plusSeconds(5));
        };
    }

    @Test
    @DisplayName("every field of a firing alert reaches the contract, and the bytes round-trip")
    void firing() {
        AlertEvent event =
                Wire.alert(AvroBinaryCodec.of(AlertEvent.class).encode(mapper.toAvro(alert("CDN", AlertState.FIRING))));

        assertThat(event.getAlertId().toString()).isEqualTo("id-1");
        assertThat(event.getTenantId().toString()).isEqualTo("streamflix");
        assertThat(event.getRuleId().toString()).isEqualTo("r1");
        assertThat(event.getRuleName().toString()).isEqualTo("My rule");
        assertThat(event.getState()).isEqualTo(com.streampulse.contracts.v1.AlertState.FIRING);
        assertThat(event.getSeverity()).isEqualTo(com.streampulse.contracts.v1.Severity.WARNING);
        assertThat(event.getCategory()).isEqualTo(AlertCategory.CDN);
        assertThat(event.getTitleId().toString()).isEqualTo("title-1");
        assertThat(event.getRegion()).isNull();
        assertThat(event.getObservedValue()).isEqualTo(0.2);
        assertThat(event.getThreshold()).isEqualTo(0.05);
        assertThat(event.getWindowStart()).isEqualTo(T0);
        assertThat(event.getRaisedAt()).isEqualTo(T0.plusSeconds(5));
        assertThat(event.getSuppressedBy()).isNull();
    }

    @Test
    @DisplayName("suppressed names its cause; resolved is stamped with the resolution time")
    void otherStates() {
        assertThat(mapper.toAvro(alert("PLAYER", AlertState.SUPPRESSED))
                        .getSuppressedBy()
                        .toString())
                .isEqualTo("root-id");
        assertThat(mapper.toAvro(alert("PLAYER", AlertState.RESOLVED)).getState())
                .isEqualTo(com.streampulse.contracts.v1.AlertState.RESOLVED);
    }

    @Test
    @DisplayName("a node the contract has no category for is reported as unknown")
    void unknownCategory() {
        assertThat(mapper.toAvro(alert("UNCATEGORISED", AlertState.FIRING)).getCategory())
                .isEqualTo(AlertCategory.CATEGORY_UNKNOWN);
    }

    @Test
    @DisplayName("the Kafka key is tenant:rule, shared by every series of the rule")
    void topicKey() {
        assertThat(key.topicKey()).isEqualTo("streamflix:r1");
    }
}

/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.streampulse.alert.domain.AlertRule;
import com.streampulse.alert.domain.Comparison;
import com.streampulse.alert.domain.Condition;
import com.streampulse.alert.domain.Condition.RateOfChange.Direction;
import com.streampulse.alert.domain.Severity;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RuleJsonParserTest {

    private final RuleJsonParser parser = new RuleJsonParser(new ObjectMapper());

    @Test
    @DisplayName("the control-plane AlertRule shape parses into a threshold rule")
    void controlPlaneShape() throws Exception {
        AlertRule rule = parser.parse(
                "r1",
                null,
                """
                {"ruleId":"r1","tenantId":"streamflix","metric":"error_rate","comparator":"GTE","threshold":0.05,
                 "severity":"CRITICAL","category":"packager","dependsOn":["cdn"]}""");

        assertThat(rule.ruleId()).isEqualTo("r1");
        assertThat(rule.tenantId()).isEqualTo("streamflix");
        assertThat(rule.metric()).isEqualTo("ERROR_RATE");
        assertThat(rule.condition()).isEqualTo(new Condition.Threshold(Comparison.GTE, 0.05));
        assertThat(rule.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(rule.node()).isEqualTo("PACKAGER");
        assertThat(rule.upstream()).isEqualTo(Set.of("CDN"));
        assertThat(rule.name()).isEqualTo("r1");
    }

    @Test
    @DisplayName("defaults: GT, WARNING, uncategorised, tenant from the record")
    void defaults() throws Exception {
        AlertRule rule = parser.parse("r1", "streamflix", "{\"metric\":\"ERROR_RATE\",\"threshold\":1}");

        assertThat(rule.condition()).isEqualTo(new Condition.Threshold(Comparison.GT, 1));
        assertThat(rule.severity()).isEqualTo(Severity.WARNING);
        assertThat(rule.node()).isEqualTo(AlertRule.UNCATEGORISED);
        assertThat(rule.tenantId()).isEqualTo("streamflix");
        assertThat(rule.upstream()).isEmpty();
    }

    @Test
    @DisplayName("rate-of-change and absence kinds, with optional title and region")
    void otherKinds() throws Exception {
        AlertRule roc = parser.parse(
                "r2",
                "t",
                """
                {"kind":"RATE_OF_CHANGE","metric":"CONCURRENT_VIEWERS","fraction":0.4,"direction":"FALL",
                 "titleId":"title-9","region":"eu-west-1","name":"drop"}""");
        AlertRule absence =
                parser.parse("r3", "t", "{\"kind\":\"ABSENCE\",\"metric\":\"CONCURRENT_VIEWERS\",\"windows\":3}");

        assertThat(roc.condition()).isEqualTo(new Condition.RateOfChange(Direction.FALL, 0.4));
        assertThat(roc.titleId()).isEqualTo("title-9");
        assertThat(roc.region()).isEqualTo("eu-west-1");
        assertThat(roc.name()).isEqualTo("drop");
        assertThat(absence.condition()).isEqualTo(new Condition.Absence(3));
    }

    @Test
    @DisplayName("unknown fields are ignored")
    void unknownFieldsIgnored() throws Exception {
        assertThat(parser.parse("r1", "t", "{\"metric\":\"M\",\"threshold\":1,\"futureField\":{\"a\":1}}"))
                .isNotNull();
    }

    @Test
    @DisplayName("bad input is a RuleParseException with a short reason, never anything else")
    void badInput() {
        assertThatThrownBy(() -> parser.parse("r", "t", "{nope"))
                .isInstanceOf(RuleParseException.class)
                .extracting("reason")
                .isEqualTo("json");
        assertThatThrownBy(() -> parser.parse("r", "t", "[1]"))
                .isInstanceOf(RuleParseException.class)
                .extracting("reason")
                .isEqualTo("json");
        assertThatThrownBy(() -> parser.parse("r", "t", "{\"threshold\":1}"))
                .isInstanceOf(RuleParseException.class)
                .extracting("reason")
                .isEqualTo("invalid");
        assertThatThrownBy(() -> parser.parse("r", "t", "{\"metric\":\"M\"}")).isInstanceOf(RuleParseException.class);
        assertThatThrownBy(() -> parser.parse("r", "t", "{\"metric\":\"M\",\"threshold\":1,\"comparator\":\"WAT\"}"))
                .isInstanceOf(RuleParseException.class);
        assertThatThrownBy(() -> parser.parse("r", "t", "{\"metric\":\"M\",\"threshold\":1,\"severity\":\"WAT\"}"))
                .isInstanceOf(RuleParseException.class);
        assertThatThrownBy(() -> parser.parse("r", "t", "{\"kind\":\"ABSENCE\",\"metric\":\"M\",\"windows\":0}"))
                .isInstanceOf(RuleParseException.class);
        assertThatThrownBy(() -> parser.parse("r", "t", "{\"kind\":\"WAT\",\"metric\":\"M\"}"))
                .isInstanceOf(RuleParseException.class);
        assertThatThrownBy(() -> parser.parse("r", null, "{\"metric\":\"M\",\"threshold\":1}"))
                .isInstanceOf(RuleParseException.class);
        assertThatThrownBy(() -> parser.parse("r", "t", "{\"metric\":\"M\",\"threshold\":1,\"dependsOn\":\"CDN\"}"))
                .isInstanceOf(RuleParseException.class);
    }

    @Test
    @DisplayName("the control plane's snapshot payload parses, and the entityKind discriminator is ignored")
    void controlPlanePayloadWithEntityKind() throws Exception {
        AlertRule rule = parser.parse(
                "rule-42",
                "ignored-fallback",
                """
                {"entityKind":"ALERT_RULE","ruleId":"rule-42","tenantId":"streamflix","metric":"ERROR_RATE",
                 "comparator":"GT","threshold":0.05,"severity":"CRITICAL","category":"PLAYER",
                 "dependsOn":["CDN","PACKAGER"],"kind":"THRESHOLD"}""");

        assertThat(rule.ruleId()).isEqualTo("rule-42");
        assertThat(rule.tenantId()).isEqualTo("streamflix");
        assertThat(rule.metric()).isEqualTo("ERROR_RATE");
        assertThat(rule.condition()).isEqualTo(new Condition.Threshold(Comparison.GT, 0.05));
        assertThat(rule.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(rule.node()).isEqualTo("PLAYER");
        assertThat(rule.upstream()).isEqualTo(Set.of("CDN", "PACKAGER"));
    }
}

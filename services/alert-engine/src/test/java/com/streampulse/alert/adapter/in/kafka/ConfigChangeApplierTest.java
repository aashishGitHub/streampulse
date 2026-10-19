/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.adapter.in.kafka;

import static com.streampulse.alert.support.Fixtures.T0;
import static com.streampulse.alert.support.Fixtures.TENANT;
import static com.streampulse.alert.support.Fixtures.obs;
import static com.streampulse.alert.support.Wire.change;
import static com.streampulse.alert.support.Wire.ruleChange;
import static com.streampulse.alert.support.Wire.thresholdRule;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.streampulse.alert.application.AlertChannel;
import com.streampulse.alert.application.AlertOutbox;
import com.streampulse.alert.application.AlertProcessor;
import com.streampulse.alert.domain.Alert;
import com.streampulse.alert.domain.AlertEngine;
import com.streampulse.alert.domain.AlertState;
import com.streampulse.alert.domain.RuleBook;
import com.streampulse.alert.domain.evaluator.Evaluators;
import com.streampulse.alert.support.MutableClock;
import com.streampulse.alert.support.RecordingMetrics;
import com.streampulse.contracts.v1.ChangeOperation;
import com.streampulse.contracts.v1.EntityType;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConfigChangeApplierTest {

    private final RuleBook rules = new RuleBook();
    private final RecordingMetrics metrics = new RecordingMetrics();
    private final List<Alert> delivered = new ArrayList<>();
    private final AlertEngine engine =
            new AlertEngine(rules, new Evaluators(Duration.ofSeconds(5)), new MutableClock(T0), metrics);
    private final AlertProcessor processor = new AlertProcessor(
            engine,
            new AlertOutbox(
                    List.of(new AlertChannel() {
                        @Override
                        public String name() {
                            return "fake";
                        }

                        @Override
                        public void deliver(Alert alert) {
                            delivered.add(alert);
                        }
                    }),
                    metrics));
    private final ConfigChangeApplier applier =
            new ConfigChangeApplier(rules, processor, new RuleJsonParser(new ObjectMapper()), metrics);

    private static String rule(double threshold, String category) {
        return thresholdRule(TENANT, "ERROR_RATE", "GT", threshold, category);
    }

    @Test
    @DisplayName("a created rule is evaluated by the next aggregate")
    void ruleIsApplied() {
        applier.apply("r1", ruleChange("r1", TENANT, 1, rule(0.05, "CDN")));

        assertThat(rules.find("r1")).isPresent();
        processor.process(obs("ERROR_RATE", 0, 0.2));
        assertThat(delivered).extracting(Alert::state).containsExactly(AlertState.FIRING);
    }

    @Test
    @DisplayName("the record key names the rule, not the payload's ruleId")
    void keyWins() {
        applier.apply("the-key", ruleChange("the-key", TENANT, 1, rule(0.05, "CDN")));

        assertThat(rules.find("the-key")).isPresent();
        assertThat(rules.find("ignored-by-key")).isEmpty();
    }

    @Test
    @DisplayName("a newer version replaces; an older one replayed afterwards is ignored")
    void versions() {
        applier.apply("r1", ruleChange("r1", TENANT, 2, rule(0.2, "CDN")));
        applier.apply("r1", ruleChange("r1", TENANT, 1, rule(0.9, "CDN")));

        assertThat(rules.find("r1").orElseThrow().condition().describe()).contains("0.2");
        assertThat(metrics.events).contains("rule:applied", "rule:stale");
    }

    @Test
    @DisplayName("a Kafka tombstone removes the rule and resolves its alerts")
    void tombstoneRemovesTheRule() {
        applier.apply("r1", ruleChange("r1", TENANT, 1, rule(0.05, "CDN")));
        processor.process(obs("ERROR_RATE", 0, 0.2));

        applier.apply("r1", null);

        assertThat(rules.find("r1")).isEmpty();
        assertThat(delivered).extracting(Alert::state).containsExactly(AlertState.FIRING, AlertState.RESOLVED);
        assertThat(metrics.events).contains("rule:removed");
    }

    @Test
    @DisplayName("a DELETED change, or a change with a null payload, removes the rule too")
    void deletedOperation() {
        applier.apply("r1", ruleChange("r1", TENANT, 1, rule(0.05, "CDN")));
        applier.apply("r2", ruleChange("r2", TENANT, 1, rule(0.05, "CDN")));

        applier.apply(
                "r1",
                com.streampulse.alert.support.Wire.bytes(
                        change(EntityType.ALERT_RULE, "r1", ChangeOperation.DELETED, "{}", 2)
                                .build()));
        applier.apply(
                "r2",
                com.streampulse.alert.support.Wire.bytes(
                        change(EntityType.ALERT_RULE, "r2", ChangeOperation.UPDATED, null, 2)
                                .build()));

        assertThat(rules.size()).isZero();
    }

    @Test
    @DisplayName("deleting a rule that is not there is a no-op")
    void deleteUnknown() {
        applier.apply("ghost", null);

        assertThat(metrics.events).contains("rule:ignored");
    }

    @Test
    @DisplayName("other entity types on the same topic are ignored")
    void otherEntityTypes() {
        applier.apply(
                "t1",
                com.streampulse.alert.support.Wire.bytes(
                        change(EntityType.TENANT, "t1", ChangeOperation.CREATED, "{\"x\":1}", 1)
                                .build()));

        assertThat(rules.size()).isZero();
        assertThat(metrics.events).noneMatch(e -> e.startsWith("rejected"));
    }

    @Test
    @DisplayName("a rule with a garbage payload is skipped and counted; the next rule still loads")
    void badPayloadIsSkipped() {
        applier.apply("bad", ruleChange("bad", TENANT, 1, "{not json"));
        applier.apply("r1", ruleChange("r1", TENANT, 1, rule(0.05, "CDN")));

        assertThat(rules.find("bad")).isEmpty();
        assertThat(rules.find("r1")).isPresent();
        assertThat(metrics.events).contains("rejected:config:parse-json");
    }

    @Test
    @DisplayName("bytes that are not a ConfigChange are skipped and counted")
    void undecodable() {
        applier.apply("x", "garbage".getBytes(StandardCharsets.UTF_8));

        assertThat(metrics.events).contains("rejected:config:decode");
    }

    @Test
    @DisplayName("a rule whose dependencies close a cycle is rejected, the earlier rules stay")
    void cycleIsRejected() {
        applier.apply(
                "pkg",
                ruleChange(
                        "pkg",
                        TENANT,
                        1,
                        """
                {"metric":"ERROR_RATE","threshold":1,"category":"PACKAGER","dependsOn":["CDN"]}"""));
        applier.apply(
                "cdn",
                ruleChange(
                        "cdn",
                        TENANT,
                        1,
                        """
                {"metric":"ERROR_RATE","threshold":1,"category":"CDN","dependsOn":["PACKAGER"]}"""));

        assertThat(rules.find("pkg")).isPresent();
        assertThat(rules.find("cdn")).isEmpty();
        assertThat(metrics.events).contains("rejected:config:cycle");
    }

    @Test
    @DisplayName("a null key is rejected")
    void nullKey() {
        applier.apply(null, ruleChange("r1", TENANT, 1, rule(0.05, "CDN")));

        assertThat(rules.size()).isZero();
    }

    @Test
    @DisplayName("tombstone then re-create at version 0: the re-created rule takes effect")
    void recreateAfterTombstoneAtVersionZero() {
        applier.apply("r1", ruleChange("r1", TENANT, 3, rule(0.05, "CDN")));
        applier.apply("r1", null);

        applier.apply("r1", ruleChange("r1", TENANT, 0, rule(0.7, "CDN")));

        assertThat(rules.find("r1").orElseThrow().condition().describe()).contains("0.7");
        assertThat(metrics.events).doesNotContain("rule:stale");
    }

    @Test
    @DisplayName("a DELETED change then re-create at version 0 takes effect too")
    void recreateAfterDeletedChangeAtVersionZero() {
        applier.apply("r1", ruleChange("r1", TENANT, 4, rule(0.05, "CDN")));
        applier.apply(
                "r1",
                com.streampulse.alert.support.Wire.bytes(
                        change(EntityType.ALERT_RULE, "r1", ChangeOperation.DELETED, null, 5)
                                .build()));

        applier.apply("r1", ruleChange("r1", TENANT, 0, rule(0.7, "CDN")));

        assertThat(rules.find("r1").orElseThrow().condition().describe()).contains("0.7");
    }

    @Test
    @DisplayName("update to version 1, then a stale version 0 redelivery is ignored")
    void staleVersionZeroIsIgnored() {
        applier.apply("r1", ruleChange("r1", TENANT, 0, rule(0.05, "CDN")));
        applier.apply("r1", ruleChange("r1", TENANT, 1, rule(0.2, "CDN")));

        applier.apply("r1", ruleChange("r1", TENANT, 0, rule(0.05, "CDN")));

        assertThat(rules.find("r1").orElseThrow().condition().describe()).contains("0.2");
        assertThat(metrics.events).contains("rule:stale");
    }
}

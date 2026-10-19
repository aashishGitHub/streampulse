/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.adapter.in.kafka;

import static com.streampulse.alert.support.Fixtures.T0;
import static com.streampulse.alert.support.Fixtures.TENANT;
import static com.streampulse.alert.support.Fixtures.errorRateAbove;
import static com.streampulse.alert.support.Wire.aggregate;
import static com.streampulse.alert.support.Wire.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streampulse.alert.application.AlertChannel;
import com.streampulse.alert.application.AlertOutbox;
import com.streampulse.alert.application.AlertProcessor;
import com.streampulse.alert.application.DeliveryException;
import com.streampulse.alert.domain.Alert;
import com.streampulse.alert.domain.AlertEngine;
import com.streampulse.alert.domain.RuleBook;
import com.streampulse.alert.domain.evaluator.Evaluators;
import com.streampulse.alert.support.MutableClock;
import com.streampulse.alert.support.RecordingMetrics;
import com.streampulse.contracts.v1.MetricName;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AggregateListenerTest {

    private final List<String> order = new ArrayList<>();
    private boolean down;
    private final RecordingMetrics metrics = new RecordingMetrics();
    private final RuleBook rules = new RuleBook();
    private final AlertProcessor processor = new AlertProcessor(
            new AlertEngine(rules, new Evaluators(Duration.ofSeconds(5)), new MutableClock(T0), metrics),
            new AlertOutbox(
                    List.of(new AlertChannel() {
                        @Override
                        public String name() {
                            return "fake";
                        }

                        @Override
                        public void deliver(Alert alert) {
                            if (down) {
                                throw new DeliveryException("down", null);
                            }
                            order.add("delivered");
                        }
                    }),
                    metrics));
    private final AggregateListener listener = new AggregateListener(new AggregateDecoder(), processor, metrics);

    private ConsumerRecord<String, byte[]> record(byte[] value) {
        return new ConsumerRecord<>("telemetry.agg.v1", 0, 7L, "key", value);
    }

    private byte[] breach(int window) {
        return bytes(aggregate(TENANT, "title-1", "us-east-1", MetricName.ERROR_RATE, window, 0.5)
                .build());
    }

    @Test
    @DisplayName("the offset is acknowledged only after the alert was delivered")
    void acksAfterDelivery() {
        rules.put(errorRateAbove("r1", 0.05, "CDN"), 1);

        listener.onRecord(record(breach(0)), () -> order.add("ack"));

        assertThat(order).containsExactly("delivered", "ack");
    }

    @Test
    @DisplayName("a delivery failure is not acknowledged and surfaces, so the container redelivers")
    void deliveryFailureIsNotAcknowledged() {
        rules.put(errorRateAbove("r1", 0.05, "CDN"), 1);
        down = true;

        assertThatThrownBy(() -> listener.onRecord(record(breach(0)), () -> order.add("ack")))
                .isInstanceOf(DeliveryException.class);

        assertThat(order).isEmpty();

        down = false;
        listener.onRecord(record(breach(0)), () -> order.add("ack"));
        assertThat(order).containsExactly("delivered", "ack");
    }

    @Test
    @DisplayName("an undecodable record is counted, acknowledged and never blocks the partition")
    void undecodableRecordIsAcknowledged() {
        listener.onRecord(record("garbage".getBytes(StandardCharsets.UTF_8)), () -> order.add("ack"));

        assertThat(order).containsExactly("ack");
        assertThat(metrics.events).contains("rejected:aggregate:decode");
    }

    @Test
    @DisplayName("a record that changes nothing is still acknowledged")
    void noRuleStillAcks() {
        listener.onRecord(record(breach(0)), () -> order.add("ack"));

        assertThat(order).containsExactly("ack");
    }
}

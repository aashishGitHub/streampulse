/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.application;

import static com.streampulse.alert.support.Alerts.firing;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streampulse.alert.domain.Alert;
import com.streampulse.alert.support.RecordingMetrics;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AlertOutboxTest {

    static final class FakeChannel implements AlertChannel {
        final String name;
        final List<String> delivered = new ArrayList<>();
        boolean failing;

        FakeChannel(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void deliver(Alert alert) {
            if (failing) {
                throw new DeliveryException(name + " down", null);
            }
            delivered.add(alert.alertId());
        }
    }

    private final FakeChannel kafka = new FakeChannel("kafka");
    private final FakeChannel sqs = new FakeChannel("sqs");
    private final RecordingMetrics metrics = new RecordingMetrics();
    private final AlertOutbox outbox = new AlertOutbox(List.of(kafka, sqs), metrics);

    @Test
    @DisplayName("every channel gets every alert, in order")
    void deliversToAllInOrder() {
        outbox.add(List.of(firing("t", "a"), firing("t", "b")));

        outbox.drain();

        assertThat(kafka.delivered).containsExactly("a", "b");
        assertThat(sqs.delivered).containsExactly("a", "b");
        assertThat(outbox.size()).isZero();
    }

    @Test
    @DisplayName("a failed channel is retried alone; the channel that succeeded is not given the alert twice")
    void failedChannelIsRetriedAloneAndOthersNotRepeated() {
        sqs.failing = true;
        outbox.add(List.of(firing("t", "a")));

        assertThatThrownBy(outbox::drain).isInstanceOf(DeliveryException.class).hasMessageContaining("sqs");
        assertThat(kafka.delivered).containsExactly("a");
        assertThat(outbox.size()).isEqualTo(1);

        sqs.failing = false;
        outbox.drain();

        assertThat(kafka.delivered).as("kafka must not see it again").containsExactly("a");
        assertThat(sqs.delivered).containsExactly("a");
        assertThat(outbox.size()).isZero();
    }

    @Test
    @DisplayName("a failing channel does not hold back the healthy one, and never skips ahead of its own backlog")
    void perChannelOrderAndIsolation() {
        kafka.failing = true;
        outbox.add(List.of(firing("t", "a"), firing("t", "b")));

        assertThatThrownBy(outbox::drain).isInstanceOf(DeliveryException.class);
        assertThat(sqs.delivered).containsExactly("a", "b");
        assertThat(kafka.delivered).isEmpty();

        outbox.add(List.of(firing("t", "c")));
        kafka.failing = false;
        outbox.drain();

        assertThat(kafka.delivered).containsExactly("a", "b", "c");
        assertThat(sqs.delivered).containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("a runtime failure inside a channel is reported as a delivery failure and counted")
    void runtimeFailureIsWrapped() {
        var broken = new AlertChannel() {
            @Override
            public String name() {
                return "broken";
            }

            @Override
            public void deliver(Alert alert) {
                throw new IllegalStateException("boom");
            }
        };
        var outbox = new AlertOutbox(List.of(broken), metrics);
        outbox.add(List.of(firing("t", "a")));

        assertThatThrownBy(outbox::drain)
                .isInstanceOf(DeliveryException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(metrics.events).contains("deliveryFailed:broken");
    }

    @Test
    @DisplayName("draining an empty outbox is a no-op")
    void emptyDrain() {
        outbox.drain();

        assertThat(kafka.delivered).isEmpty();
    }
}

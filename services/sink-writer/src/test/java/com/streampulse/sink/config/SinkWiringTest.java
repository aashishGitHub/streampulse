/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.sink.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.streampulse.sink.adapter.in.kafka.AggregateBatchListener;
import com.streampulse.sink.lifecycle.BatchWriterLifecycle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthEndpointGroups;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ContainerProperties;

/**
 * The Spring context assembles with no Docker: the listener containers are not started, the
 * schema is not applied, and Hikari and Lettuce connect lazily, so nothing touches a network.
 * Named *Test, not *IT.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.kafka.listener.auto-startup=false", "spring.sql.init.mode=never"})
class SinkWiringTest {

    @Autowired
    private HealthEndpointGroups groups;

    @Autowired
    private ConcurrentKafkaListenerContainerFactory<Object, Object> factory;

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @Autowired
    private AggregateBatchListener listener;

    @Autowired
    private BatchWriterLifecycle lifecycle;

    @Test
    @DisplayName("the listener container factory is a batch listener with manual acknowledgement")
    void factoryIsBatchWithManualAck() {
        assertThat(factory.isBatchListener()).isTrue();
        assertThat(factory.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.MANUAL);
    }

    @Test
    @DisplayName("the aggregate listener is registered, but not running")
    void listenerIsRegisteredButNotStarted() {
        assertThat(listener).isNotNull();
        assertThat(registry.getListenerContainer("aggregate-sink")).isNotNull();
        assertThat(registry.getListenerContainer("aggregate-sink").isRunning()).isFalse();
    }

    @Test
    @DisplayName("the consumer is configured for manual commits, read_committed and cooperative rebalancing")
    void consumerSettings() {
        var config = factory.getConsumerFactory().getConfigurationProperties();
        assertThat(String.valueOf(config.get("group.id"))).isEqualTo("sink-writer");
        assertThat(registry.getListenerContainer("aggregate-sink")
                        .getContainerProperties()
                        .getGroupId())
                .as("the listener id must not override the group id")
                .isNull();
        assertThat(String.valueOf(config.get("enable.auto.commit"))).isEqualTo("false");
        assertThat(String.valueOf(config.get("isolation.level"))).isEqualTo("read_committed");
        assertThat(String.valueOf(config.get("max.poll.records"))).isEqualTo("500");
        assertThat(String.valueOf(config.get("partition.assignment.strategy")))
                .isEqualTo("org.apache.kafka.clients.consumer.CooperativeStickyAssignor");
    }

    @Test
    @DisplayName("max.poll.interval.ms exceeds the poll budget plus the longest redelivery backoff")
    void pollIntervalCoversTheBudget() {
        var config = factory.getConsumerFactory().getConfigurationProperties();
        long maxPollInterval = Long.parseLong(String.valueOf(config.get("max.poll.interval.ms")));

        assertThat(maxPollInterval).isGreaterThan(30_000 + 30_000 + 20_000);
    }

    @Test
    @DisplayName("the writer lifecycle is registered and stops after the listener container")
    void writerLifecycleIsRegistered() {
        assertThat(lifecycle.getPhase())
                .isLessThan(org.springframework.kafka.listener.AbstractMessageListenerContainer.DEFAULT_PHASE);
    }

    @Test
    @DisplayName("liveness depends on no store; readiness is the availability state")
    void healthGroups() {
        assertThat(groups.get("liveness").isMember("livenessState")).isTrue();
        assertThat(groups.get("liveness").isMember("db")).isFalse();
        assertThat(groups.get("liveness").isMember("redis")).isFalse();
        assertThat(groups.get("readiness").isMember("readinessState")).isTrue();
    }
}

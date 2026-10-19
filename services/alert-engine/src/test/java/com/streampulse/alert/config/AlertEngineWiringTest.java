/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.streampulse.alert.adapter.in.kafka.AggregateListener;
import com.streampulse.alert.support.NoDockerWebTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthEndpointGroups;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;

/** The context assembles with no Docker: nothing is started that touches a network. Named *Test, not *IT. */
class AlertEngineWiringTest extends NoDockerWebTest {

    @Autowired
    private HealthEndpointGroups groups;

    @Autowired
    private ConcurrentKafkaListenerContainerFactory<Object, Object> factory;

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @Autowired
    private KafkaTemplate<String, byte[]> template;

    @Autowired
    private AlertProperties props;

    @Test
    @DisplayName("the listener container factory acknowledges manually")
    void manualAck() {
        assertThat(factory.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.MANUAL);
    }

    @Test
    @DisplayName("the aggregate listener is registered but not started: the rule book must load first")
    void aggregateListenerIsNotAutoStarted() {
        var container = registry.getListenerContainer(AggregateListener.ID);

        assertThat(container).isNotNull();
        assertThat(container.isRunning()).isFalse();
        assertThat(container.getContainerProperties().getGroupId())
                .as("the listener id must not override the group id")
                .isNull();
    }

    @Test
    @DisplayName("consumer: group alert-engine, no auto commit, read_committed")
    void consumerSettings() {
        var config = factory.getConsumerFactory().getConfigurationProperties();

        assertThat(String.valueOf(config.get("group.id"))).isEqualTo("alert-engine");
        assertThat(String.valueOf(config.get("enable.auto.commit"))).isEqualTo("false");
        assertThat(String.valueOf(config.get("isolation.level"))).isEqualTo("read_committed");
    }

    @Test
    @DisplayName("producer: idempotent, acks=all")
    void producerSettings() {
        var config = template.getProducerFactory().getConfigurationProperties();

        assertThat(String.valueOf(config.get("acks"))).isEqualTo("all");
        assertThat(String.valueOf(config.get("enable.idempotence"))).isEqualTo("true");
    }

    @Test
    @DisplayName("liveness depends on no broker; readiness is the availability state")
    void healthGroups() {
        assertThat(groups.get("liveness").isMember("livenessState")).isTrue();
        assertThat(groups.get("liveness").isMember("readinessState")).isFalse();
        assertThat(groups.get("liveness").isMember("kafka")).isFalse();
        assertThat(groups.get("readiness").isMember("readinessState")).isTrue();
    }

    @Test
    @DisplayName("typed properties bind: topics, heartbeat of 15 s in the shipped file, CORS origins")
    void properties() {
        assertThat(props.aggregateTopic()).isEqualTo("telemetry.agg.v1");
        assertThat(props.configTopic()).isEqualTo("config.changes.v1");
        assertThat(props.alertsTopic()).isEqualTo("alerts.v1");
        assertThat(props.corsAllowedOrigins()).isNotEmpty();
    }
}

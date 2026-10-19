/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.streampulse.feed.adapter.in.kafka.WatchEventListener;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.HealthEndpointGroups;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The Spring context assembles with no Docker: the listener containers are not started, Flyway is
 * off, and Hikari and Lettuce connect lazily, so nothing touches a network. Named *Test, not *IT.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.kafka.listener.auto-startup=false", "spring.flyway.enabled=false"})
class FeedWiringTest {

    @Autowired
    private HealthEndpointGroups groups;

    @Autowired
    private ConcurrentKafkaListenerContainerFactory<Object, Object> factory;

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @Autowired
    private WatchEventListener listener;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private FeedProperties properties;

    @Test
    @DisplayName("the listener container factory is a batch listener with manual acknowledgement")
    void factoryIsBatchWithManualAck() {
        assertThat(factory.isBatchListener()).isTrue();
        assertThat(factory.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.MANUAL);
    }

    @Test
    @DisplayName("the watch listener is registered, but not running")
    void listenerIsRegisteredButNotStarted() {
        assertThat(listener).isNotNull();
        assertThat(registry.getListenerContainer("watch-fanout")).isNotNull();
        assertThat(registry.getListenerContainer("watch-fanout").isRunning()).isFalse();
    }

    @Test
    @DisplayName("the consumer is configured for manual commits, read_committed and cooperative rebalancing")
    void consumerSettings() {
        var config = factory.getConsumerFactory().getConfigurationProperties();
        assertThat(String.valueOf(config.get("group.id"))).isEqualTo("activity-feed");
        assertThat(registry.getListenerContainer("watch-fanout")
                        .getContainerProperties()
                        .getGroupId())
                .as("the listener id must not override the group id")
                .isNull();
        assertThat(String.valueOf(config.get("enable.auto.commit"))).isEqualTo("false");
        assertThat(String.valueOf(config.get("isolation.level"))).isEqualTo("read_committed");
        assertThat(String.valueOf(config.get("partition.assignment.strategy")))
                .isEqualTo("org.apache.kafka.clients.consumer.CooperativeStickyAssignor");
    }

    @Test
    @DisplayName("the four endpoints are mapped")
    void endpointsAreMapped() {
        Set<String> routes = mappings.getHandlerMethods().keySet().stream()
                .map(RequestMappingInfo::toString)
                .collect(Collectors.toSet());

        assertThat(String.join("\n", routes))
                .contains("POST [/v1/watching]")
                .contains("POST [/v1/users/{id}/follow]")
                .contains("DELETE [/v1/users/{id}/follow]")
                .contains("GET [/v1/users/{id}/followers]")
                .contains("GET [/v1/feed]");
    }

    @Test
    @DisplayName("the metrics are registered at startup")
    void metricsAreRegistered() {
        assertThat(meters.find("streampulse.feed.fanout.writes").counter()).isNotNull();
        assertThat(meters.find("streampulse.feed.read.latency").timer()).isNotNull();
    }

    @Test
    @DisplayName("the celebrity threshold is the documented target")
    void thresholdIsTheTarget() {
        assertThat(properties.celebrityThreshold()).isEqualTo(10_000);
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

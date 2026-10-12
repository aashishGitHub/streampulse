/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.processor.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.streams.StreamsBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthEndpointGroups;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The Spring context assembles, with no broker: auto-startup is off, so Kafka Streams is
 * configured but never started. Catches a wiring or property mistake in milliseconds
 * instead of at deploy time. Named *Test, not *IT: it needs no Docker.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.kafka.streams.auto-startup=false"})
class StreamProcessorWiringTest {

    @Autowired
    private HealthEndpointGroups groups;

    @Autowired
    private StreamsBuilder builder;

    @Test
    @DisplayName("the context assembles and the topology is declared on the managed builder")
    void contextAssembles() {
        String description = builder.build().describe().toString();

        assertThat(description).contains("telemetry.raw.v1", "telemetry.agg.v1", "telemetry.raw.v1.dlt");
    }

    @Test
    @DisplayName("readiness includes the streams state; liveness does not")
    void readinessIncludesStreamsStateLivenessDoesNot() {
        assertThat(groups.get("readiness").isMember("streamsState")).isTrue();
        assertThat(groups.get("liveness").isMember("streamsState")).isFalse();
    }
}

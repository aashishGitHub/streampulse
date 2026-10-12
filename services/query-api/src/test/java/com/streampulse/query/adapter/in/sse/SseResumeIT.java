/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.query.adapter.in.sse;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * Real Redis, real Netty. Proves resume (replay after Last-Event-ID, then live) through the shared feed,
 * and tenant isolation on the wire. Does not prove behaviour under a Redis outage or at fan-out scale.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class SseResumeIT {

    private static final String STREAM = "sp:agg:live";

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private WebTestClient client;

    @Autowired
    private ReactiveStringRedisTemplate redis;

    private String add(String tenant, double value) {
        Map<String, String> fields = Map.of(
                "tenantId", tenant,
                "titleId", "title-1",
                "region", "us-east-1",
                "metric", "ERROR_RATE",
                "windowStart", "1768478400000",
                "windowEnd", "1768478410000",
                "value", Double.toString(value),
                "sampleCount", "40",
                "computedAt", "1768478416000");
        return redis.opsForStream()
                .add(MapRecord.create(STREAM, fields))
                .block(Duration.ofSeconds(5))
                .getValue();
    }

    @Test
    @DisplayName("Last-Event-ID resumes after that entry, then continues live; other tenants never appear")
    void resumeThenLive() {
        String first = add("t1", 0.1);
        String second = add("t1", 0.2);
        add("t2", 0.9);
        String third = add("t1", 0.3);

        Flux<ServerSentEvent<String>> events = client.get()
                .uri("/v1/stream/metrics?tenantId=t1")
                .header("Last-Event-ID", first)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus()
                .isOk()
                .returnResult(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .getResponseBody();

        String[] live = new String[1];
        StepVerifier.create(events)
                .assertNext(event -> assertThat(event.id()).isEqualTo(second))
                .assertNext(event -> {
                    assertThat(event.id()).isEqualTo(third);
                    assertThat(event.event()).isEqualTo("metric");
                    assertThat(event.data()).contains("\"value\":0.3").contains("\"metric\":\"ERROR_RATE\"");
                })
                .then(() -> {
                    live[0] = add("t1", 0.4);
                    add("t2", 0.8);
                })
                .assertNext(event -> assertThat(event.id()).isEqualTo(live[0]))
                .expectNoEvent(Duration.ofMillis(700))
                .thenCancel()
                .verify(Duration.ofSeconds(20));
    }
}

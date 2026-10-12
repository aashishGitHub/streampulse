/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.query.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.streampulse.query.domain.LatestMetric;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Reads the hash exactly as the hot-store contract says sink-writer writes it. */
@Testcontainers
class RedisLatestMetricsIT {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory factory;
    private static ReactiveStringRedisTemplate template;

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        template = new ReactiveStringRedisTemplate(factory);
    }

    @AfterAll
    static void disconnect() {
        factory.destroy();
    }

    private static String json(long start, double value) {
        return "{\"windowStart\":%d,\"windowEnd\":%d,\"value\":%s,\"sampleCount\":40,\"computedAt\":%d}"
                .formatted(start, start + 10_000, value, start + 16_000);
    }

    @Test
    @DisplayName("returns only the requested title's series, parsed from the hash")
    void readsTheTitlesSeries() {
        long start = Instant.parse("2026-01-15T12:00:00Z").toEpochMilli();
        template.opsForHash()
                .put("sp:latest:t1", "title-1:us-east-1:ERROR_RATE", json(start, 0.02))
                .block();
        template.opsForHash()
                .put("sp:latest:t1", "title-1:eu-west-1:CONCURRENT_VIEWERS", json(start, 900))
                .block();
        template.opsForHash()
                .put("sp:latest:t1", "title-2:us-east-1:ERROR_RATE", json(start, 0.5))
                .block();
        template.opsForHash()
                .put("sp:latest:t2", "title-1:us-east-1:ERROR_RATE", json(start, 0.9))
                .block();

        List<LatestMetric> latest = new RedisLatestMetrics(
                        template, new ObjectMapper(), "sp:latest:", Duration.ofSeconds(2))
                .latest("t1", "title-1");

        assertThat(latest).hasSize(2);
        assertThat(latest)
                .extracting(LatestMetric::metric)
                .containsExactlyInAnyOrder("ERROR_RATE", "CONCURRENT_VIEWERS");
        LatestMetric errorRate = latest.stream()
                .filter(m -> m.metric().equals("ERROR_RATE"))
                .findFirst()
                .orElseThrow();
        assertThat(errorRate.region()).isEqualTo("us-east-1");
        assertThat(errorRate.value()).isEqualTo(0.02);
        assertThat(errorRate.sampleCount()).isEqualTo(40);
        assertThat(errorRate.windowStart()).isEqualTo(Instant.ofEpochMilli(start));
        assertThat(errorRate.windowEnd()).isEqualTo(Instant.ofEpochMilli(start + 10_000));
    }

    @Test
    @DisplayName("a title id containing a colon is matched exactly, because the field is parsed from the right")
    void titleIdMayContainAColon() {
        long start = Instant.parse("2026-01-15T12:00:00Z").toEpochMilli();
        template.opsForHash()
                .put("sp:latest:t3", "show:42:us-east-1:ERROR_RATE", json(start, 0.1))
                .block();
        template.opsForHash()
                .put("sp:latest:t3", "show:us-east-1:ERROR_RATE", json(start, 0.2))
                .block();

        List<LatestMetric> latest = new RedisLatestMetrics(
                        template, new ObjectMapper(), "sp:latest:", Duration.ofSeconds(2))
                .latest("t3", "show:42");

        assertThat(latest).hasSize(1);
        assertThat(latest.get(0).value()).isEqualTo(0.1);
    }

    @Test
    @DisplayName("an unknown tenant is an empty list, not an error; an unreadable field is skipped")
    void emptyAndMalformed() {
        template.opsForHash()
                .put("sp:latest:t4", "title-1:us-east-1:ERROR_RATE", "{not json")
                .block();
        var adapter = new RedisLatestMetrics(template, new ObjectMapper(), "sp:latest:", Duration.ofSeconds(2));

        assertThat(adapter.latest("nobody", "title-1")).isEmpty();
        assertThat(adapter.latest("t4", "title-1")).isEmpty();
    }
}

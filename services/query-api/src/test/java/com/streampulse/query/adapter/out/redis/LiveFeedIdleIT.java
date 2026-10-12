/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.query.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Found by running the real stack, not by the other tests: the live feed blocks on the stream
 * (XREAD BLOCK) and the receiver's default block time equalled the Redis command timeout, so an
 * IDLE feed timed out and retried forever. Every other test finishes in less time than the
 * command timeout, so none could see it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.data.redis.timeout=1s")
@Testcontainers
class LiveFeedIdleIT {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private RedisLiveFeed feed;

    @Test
    @DisplayName("an idle live feed outlasts several Redis command timeouts without a single failed read")
    void idleFeedDoesNotTimeOut() throws InterruptedException {
        // Three command timeouts of silence on an empty stream.
        Thread.sleep(Duration.ofSeconds(3).toMillis());

        assertThat(feed.readFailures()).isZero();
    }
}

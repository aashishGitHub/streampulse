/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One container of each kind per test JVM, started on first use and reaped by Testcontainers' Ryuk
 * sidecar at exit. The machine is shared with other builds running containers, so starting
 * three per test class would triple the load for nothing: every test uses its own ids instead of
 * a clean store.
 */
public final class ItContainers {

    public static final String WATCH_TOPIC = "activity.watch.v1";
    public static final String WATCH_DLT = "activity.watch.v1.dlt";

    private static KafkaContainer kafka;
    private static PostgreSQLContainer<?> postgres;
    private static GenericContainer<?> redis;

    private ItContainers() {}

    public static synchronized KafkaContainer kafka() throws Exception {
        if (kafka == null) {
            var container = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));
            container.start();
            try (var admin =
                    AdminClient.create(Map.<String, Object>of("bootstrap.servers", container.getBootstrapServers()))) {
                var topics = new ArrayList<NewTopic>();
                // Partition counts as in api/asyncapi/streampulse-topics.yaml.
                topics.add(new NewTopic(WATCH_TOPIC, 6, (short) 1));
                topics.add(new NewTopic(WATCH_DLT, 3, (short) 1));
                admin.createTopics(topics).all().get(30, TimeUnit.SECONDS);
            }
            kafka = container;
        }
        return kafka;
    }

    public static synchronized PostgreSQLContainer<?> postgres() {
        if (postgres == null) {
            var container = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
            container.start();
            postgres = container;
        }
        return postgres;
    }

    public static synchronized GenericContainer<?> redis() {
        if (redis == null) {
            var container = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1))
                    .withStartupTimeout(Duration.ofSeconds(60));
            container.start();
            redis = container;
        }
        return redis;
    }
}

/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert;

import static com.streampulse.alert.support.Fixtures.TENANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.streampulse.alert.adapter.in.kafka.ConfigChangeApplier;
import com.streampulse.alert.adapter.in.kafka.RuleBookLoader;
import com.streampulse.alert.adapter.in.kafka.RuleJsonParser;
import com.streampulse.alert.application.AlertOutbox;
import com.streampulse.alert.application.AlertProcessor;
import com.streampulse.alert.domain.AlertEngine;
import com.streampulse.alert.domain.AlertMetrics;
import com.streampulse.alert.domain.RuleBook;
import com.streampulse.alert.domain.evaluator.Evaluators;
import com.streampulse.alert.support.ItSupport;
import com.streampulse.alert.support.MutableClock;
import com.streampulse.alert.support.RecordingMetrics;
import com.streampulse.alert.support.Wire;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/** The rule book is rebuilt from the compacted topic, tombstones and bad records included, and keeps following. */
@Testcontainers
class RuleBookLoaderIT {

    private static final String CONFIG = "config.changes.v1";

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));

    @BeforeAll
    static void topic() throws Exception {
        ItSupport.createTopic(KAFKA.getBootstrapServers(), CONFIG, 3, Map.of("cleanup.policy", "compact"));
    }

    private static final class Harness {
        final RuleBook book = new RuleBook();
        final RecordingMetrics metrics = new RecordingMetrics();
        final AtomicInteger sizeWhenLoaded = new AtomicInteger(-1);
        final RuleBookLoader loader;

        Harness() {
            var engine =
                    new AlertEngine(book, new Evaluators(Duration.ofSeconds(5)), Clock.systemUTC(), AlertMetrics.NONE);
            var processor = new AlertProcessor(engine, new AlertOutbox(List.of(), AlertMetrics.NONE));
            var applier = new ConfigChangeApplier(book, processor, new RuleJsonParser(new ObjectMapper()), metrics);
            loader = new RuleBookLoader(
                    Map.of(
                            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                            KAFKA.getBootstrapServers(),
                            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                            StringDeserializer.class,
                            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                            ByteArrayDeserializer.class,
                            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                            false,
                            ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG,
                            false),
                    CONFIG,
                    applier,
                    Duration.ofMillis(200));
            loader.start(() -> sizeWhenLoaded.set(book.size()));
        }
    }

    private static void send(String key, byte[] value) throws Exception {
        ItSupport.send(KAFKA.getBootstrapServers(), CONFIG, key, value);
    }

    private static String rule(double threshold) {
        return Wire.thresholdRule(TENANT, "ERROR_RATE", "GT", threshold, "CDN");
    }

    @Test
    @DisplayName(
            "a restart rebuilds the book from the beginning: latest versions, tombstones, bad records skipped; then it follows")
    void restartRebuildsTheBookIncludingTombstones() throws Exception {
        send("a", Wire.ruleChange("a", TENANT, 1, rule(0.1)));
        send("b", Wire.ruleChange("b", TENANT, 1, rule(0.1)));
        send("a", Wire.ruleChange("a", TENANT, 2, rule(0.3)));
        send("b", null);
        send("junk", "not avro".getBytes(StandardCharsets.UTF_8));
        send("c", Wire.ruleChange("c", TENANT, 1, "{broken"));

        for (int run = 1; run <= 2; run++) {
            var harness = new Harness();
            try {
                await().atMost(Duration.ofSeconds(60)).until(harness.loader::isLoaded);

                assertThat(harness.sizeWhenLoaded.get())
                        .as(
                                "run %d: loaded only once the whole topic was applied (a, plus the rules added by earlier runs)",
                                run)
                        .isEqualTo(run);
                assertThat(harness.book.find("a").orElseThrow().condition().describe())
                        .contains("0.3");
                assertThat(harness.book.find("b")).as("tombstoned").isEmpty();
                assertThat(harness.metrics.events).contains("rejected:config:decode", "rejected:config:parse-json");

                String followed = "d" + run;
                send(followed, Wire.ruleChange(followed, TENANT, 1, rule(0.5)));
                await().atMost(Duration.ofSeconds(30))
                        .until(() -> harness.book.find(followed).isPresent());
            } finally {
                harness.loader.close();
            }
        }
    }

    @Test
    @DisplayName(
            "delete then re-create at version 0, and a stale version 0 after version 1: a fresh load gets the right rules")
    void recreatedRuleAndStaleReplayAreHandledOnReload() throws Exception {
        send("x", Wire.ruleChange("x", TENANT, 5, rule(0.1)));
        send("x", null);
        send("x", Wire.ruleChange("x", TENANT, 0, rule(0.8)));
        send("y", Wire.ruleChange("y", TENANT, 0, rule(0.1)));
        send("y", Wire.ruleChange("y", TENANT, 1, rule(0.4)));
        send("y", Wire.ruleChange("y", TENANT, 0, rule(0.1)));

        var harness = new Harness();
        try {
            await().atMost(Duration.ofSeconds(60)).until(harness.loader::isLoaded);

            assertThat(harness.book.find("x").orElseThrow().condition().describe())
                    .contains("0.8");
            assertThat(harness.book.find("y").orElseThrow().condition().describe())
                    .contains("0.4");
        } finally {
            harness.loader.close();
        }
    }

    @Test
    @DisplayName("an empty config topic is loaded at once")
    void emptyTopicIsLoaded() throws Exception {
        ItSupport.createTopic(KAFKA.getBootstrapServers(), "config.empty.v1", 1, Map.of());
        var book = new RuleBook();
        var loader = new RuleBookLoader(
                Map.of(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                        KAFKA.getBootstrapServers(),
                        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                        StringDeserializer.class,
                        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                        ByteArrayDeserializer.class,
                        ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                        false),
                "config.empty.v1",
                new ConfigChangeApplier(
                        book,
                        new AlertProcessor(
                                new AlertEngine(
                                        book,
                                        new Evaluators(Duration.ofSeconds(5)),
                                        new MutableClock(java.time.Instant.EPOCH),
                                        AlertMetrics.NONE),
                                new AlertOutbox(List.of(), AlertMetrics.NONE)),
                        new RuleJsonParser(new ObjectMapper()),
                        AlertMetrics.NONE),
                Duration.ofMillis(200));
        try {
            loader.start(() -> {});

            await().atMost(Duration.ofSeconds(30)).until(loader::isLoaded);
        } finally {
            loader.close();
        }
    }
}

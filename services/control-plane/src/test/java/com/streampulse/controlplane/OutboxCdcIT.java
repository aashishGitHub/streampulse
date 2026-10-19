/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.streampulse.contracts.codec.AvroBinaryCodec;
import com.streampulse.contracts.v1.ChangeOperation;
import com.streampulse.contracts.v1.ConfigChange;
import com.streampulse.contracts.v1.EntityType;
import com.streampulse.controlplane.application.TenantService;
import com.streampulse.controlplane.domain.Tenant;
import com.streampulse.controlplane.support.PostgresIT;
import com.streampulse.platform.domain.TenantId;
import io.debezium.testing.testcontainers.Connector;
import io.debezium.testing.testcontainers.ConnectorConfiguration;
import io.debezium.testing.testcontainers.DebeziumContainer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The real CDC path, automated: Postgres (wal_level=logical) -> Debezium Connect running the connector
 * file from docker/debezium -> Kafka topic. The connector JSON is the one the compose stack registers,
 * not a copy, so this test and the stack cannot drift apart. Same image tags as docker/compose.yml.
 */
class OutboxCdcIT extends PostgresIT {

    private static final String TOPIC = "config.changes.v1";
    private static final String CONNECTOR = "control-plane-outbox";
    private static final Path CONNECTOR_FILE = Path.of("../../docker/debezium/outbox-connector.json");

    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1")
            .withNetwork(NETWORK)
            .withNetworkAliases("kafka")
            .withListener("kafka:19092");

    private static final DebeziumContainer CONNECT = new DebeziumContainer("quay.io/debezium/connect:3.0.8.Final")
            .withNetwork(NETWORK)
            .withKafka(NETWORK, "kafka:19092")
            .withEnv("CONNECT_CONFIG_STORAGE_REPLICATION_FACTOR", "1")
            .withEnv("CONNECT_OFFSET_STORAGE_REPLICATION_FACTOR", "1")
            .withEnv("CONNECT_STATUS_STORAGE_REPLICATION_FACTOR", "1");

    private static final AvroBinaryCodec<ConfigChange> CODEC = AvroBinaryCodec.of(ConfigChange.class);

    @Autowired
    private TenantService tenants;

    @BeforeAll
    static void startRelay() throws Exception {
        KAFKA.start();
        try (var admin = AdminClient.create(Map.<String, Object>of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1).configs(Map.of("cleanup.policy", "compact"))))
                    .all()
                    .get(30, TimeUnit.SECONDS);
        }
        CONNECT.start();
    }

    /** After the Spring context: the connector's filtered publication needs control_plane.outbox to exist (Flyway). */
    private void registerConnector() throws Exception {
        Map<String, Object> file =
                new ObjectMapper().readValue(Files.readString(CONNECTOR_FILE), new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> config = new java.util.HashMap<>((Map<String, Object>) file.get("config"));
        config.put("database.user", POSTGRES.getUsername());
        config.put("database.password", POSTGRES.getPassword());
        config.put("database.dbname", POSTGRES.getDatabaseName());
        CONNECT.registerConnector(CONNECTOR, ConnectorConfiguration.from(config));
        try {
            await().atMost(Duration.ofSeconds(60))
                    .untilAsserted(() -> assertThat(CONNECT.getConnectorTaskState(CONNECTOR, 0))
                            .isEqualTo(Connector.State.RUNNING));
        } catch (RuntimeException | AssertionError e) {
            String status = java.net.http.HttpClient.newHttpClient()
                    .send(
                            java.net.http.HttpRequest.newBuilder(
                                            java.net.URI.create(CONNECT.getConnectorStatusUri(CONNECTOR)))
                                    .build(),
                            java.net.http.HttpResponse.BodyHandlers.ofString())
                    .body();
            throw new IllegalStateException("connector task not running: " + status, e);
        }
    }

    private List<ConsumerRecord<String, byte[]>> recordsForKey(String key, int expected) {
        var props = new Properties();
        props.put("bootstrap.servers", KAFKA.getBootstrapServers());
        props.put("group.id", "cdc-it-" + UUID.randomUUID());
        props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        props.put("value.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer");
        var seen = new ArrayList<ConsumerRecord<String, byte[]>>();
        try (var consumer = new KafkaConsumer<String, byte[]>(props)) {
            var partitions = consumer.partitionsFor(TOPIC).stream()
                    .map(i -> new TopicPartition(TOPIC, i.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            await().atMost(Duration.ofSeconds(60))
                    .pollInterval(Duration.ofMillis(300))
                    .until(() -> {
                        consumer.poll(Duration.ofMillis(300)).forEach(r -> {
                            if (key.equals(r.key())) {
                                seen.add(r);
                            }
                        });
                        return seen.size() >= expected;
                    });
        }
        return seen;
    }

    @Test
    @DisplayName(
            "create, update, delete reach the topic as raw Avro, in order, and the delete is a null-value tombstone")
    void outboxRowsBecomeAvroRecordsAndATombstone() throws Exception {
        assertThat(jdbc.queryForObject("show wal_level", String.class)).isEqualTo("logical");
        registerConnector();
        TenantId id = TenantId.of("cdc-tenant");

        tenants.create(Tenant.draft("cdc-tenant", "CDC Tenant", null, null));
        tenants.update(id, "CDC Tenant 2", 7L, null);
        tenants.delete(id);

        List<ConsumerRecord<String, byte[]>> records = recordsForKey("tenant/cdc-tenant", 3);

        assertThat(records).hasSize(3);
        assertThat(records)
                .extracting(ConsumerRecord::partition)
                .as("one key, one partition")
                .containsOnly(records.getFirst().partition());
        assertThat(records).extracting(ConsumerRecord::offset).isSorted();

        ConfigChange created = CODEC.decode(records.get(0).value());
        assertThat(created.getOperation()).isEqualTo(ChangeOperation.CREATED);
        assertThat(created.getEntityType()).isEqualTo(EntityType.TENANT);
        assertThat(created.getEntityId().toString()).isEqualTo("tenant/cdc-tenant");
        assertThat(created.getPayload().toString()).contains("\"displayName\":\"CDC Tenant\"");

        ConfigChange updated = CODEC.decode(records.get(1).value());
        assertThat(updated.getOperation()).isEqualTo(ChangeOperation.UPDATED);
        assertThat(updated.getVersion()).isEqualTo(1);
        assertThat(updated.getPayload().toString()).contains("\"displayName\":\"CDC Tenant 2\"");

        assertThat(records.get(2).value())
                .as("a delete is a tombstone: null value")
                .isNull();

        List<String> outboxIds =
                jdbc.queryForList("select id::text from control_plane.outbox order by created_at", String.class);
        assertThat(created.getChangeId().toString()).isEqualTo(outboxIds.get(0));
        assertThat(updated.getChangeId().toString()).isEqualTo(outboxIds.get(1));
    }
}

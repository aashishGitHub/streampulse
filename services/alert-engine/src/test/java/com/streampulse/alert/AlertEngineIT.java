/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert;

import static com.streampulse.alert.support.Wire.aggregate;
import static com.streampulse.alert.support.Wire.bytes;
import static com.streampulse.alert.support.Wire.key;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.streampulse.alert.adapter.in.kafka.AggregateListener;
import com.streampulse.alert.domain.RuleBook;
import com.streampulse.alert.support.ItSupport;
import com.streampulse.alert.support.Wire;
import com.streampulse.contracts.v1.AlertCategory;
import com.streampulse.contracts.v1.AlertState;
import com.streampulse.contracts.v1.MetricName;
import com.streampulse.contracts.v1.Severity;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

/**
 * The whole service against a real broker and moto: a rule arrives on the config topic, aggregates
 * on the aggregate topic, and alerts come out on the alert topic, the queue and the stream.
 *
 * <p>Proves: wiring, Avro on both sides, manual-ack consumption after the rule book loaded,
 * idempotence under a real redelivery, and API-level SQS behaviour. Does not prove IAM or SQS
 * throttling (moto evaluates neither, ADR-0003) or behaviour across a broker outage.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"streampulse.alerts.sweep-interval=1h", "streampulse.alerts.sse.heartbeat=1s"})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AlertEngineIT {

    private static final String AGG = "telemetry.agg.v1";
    private static final String CONFIG = "config.changes.v1";
    private static final String ALERTS = "alerts.v1";

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));

    @Container
    static final GenericContainer<?> MOTO =
            new GenericContainer<>(DockerImageName.parse("motoserver/moto:5.2.2")).withExposedPorts(5000);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add(
                "streampulse.aws.endpoint-override", () -> "http://" + MOTO.getHost() + ":" + MOTO.getMappedPort(5000));
    }

    @BeforeAll
    static void createTopics() throws Exception {
        ItSupport.createTopic(KAFKA.getBootstrapServers(), AGG, 6, Map.of());
        ItSupport.createTopic(KAFKA.getBootstrapServers(), CONFIG, 3, Map.of("cleanup.policy", "compact"));
        ItSupport.createTopic(KAFKA.getBootstrapServers(), ALERTS, 3, Map.of());
    }

    @LocalServerPort
    private int port;

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @Autowired
    private RuleBook rules;

    @Autowired
    private MeterRegistry meters;

    private final ObjectMapper json = new ObjectMapper();

    private static String bootstrap() {
        return KAFKA.getBootstrapServers();
    }

    private void awaitListenerAssigned() {
        await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
            var container = registry.getListenerContainer(AggregateListener.ID);
            assertThat(container.isRunning()).isTrue();
            assertThat(container.getAssignedPartitions()).hasSize(6);
        });
    }

    private void publishRule(String ruleId, String tenant, String payload) throws Exception {
        ItSupport.send(bootstrap(), CONFIG, ruleId, Wire.ruleChange(ruleId, tenant, 1, payload));
        await().atMost(Duration.ofSeconds(30)).until(() -> rules.find(ruleId).isPresent());
    }

    private void publishAggregate(String tenant, MetricName metric, int window, double value) throws Exception {
        var aggregate =
                aggregate(tenant, "title-1", "us-east-1", metric, window, value).build();
        ItSupport.send(bootstrap(), AGG, key(aggregate), bytes(aggregate));
    }

    private List<ItSupport.Alert> alertsOf(String tenant) {
        return ItSupport.readAlerts(bootstrap(), ALERTS).stream()
                .filter(a -> a.event().getTenantId().toString().equals(tenant))
                .toList();
    }

    private static String tenant() {
        return "it-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private SqsClient sqs() {
        return SqsClient.builder()
                .region(Region.US_EAST_1)
                .endpointOverride(URI.create("http://" + MOTO.getHost() + ":" + MOTO.getMappedPort(5000)))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
    }

    private List<JsonNode> sqsMessagesFor(String tenant) {
        try (var sqs = sqs()) {
            String url = sqs.getQueueUrl(
                            GetQueueUrlRequest.builder().queueName("alerts").build())
                    .queueUrl();
            var found = new ArrayList<JsonNode>();
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline && found.isEmpty()) {
                for (Message m : sqs.receiveMessage(ReceiveMessageRequest.builder()
                                .queueUrl(url)
                                .maxNumberOfMessages(10)
                                .waitTimeSeconds(1)
                                .visibilityTimeout(0)
                                .build())
                        .messages()) {
                    JsonNode body = json.readTree(m.body());
                    if (body.path("tenantId").asText().equals(tenant)) {
                        found.add(body);
                    }
                }
            }
            return found;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private List<String> streamLines(String tenant, List<String> into) throws Exception {
        HttpResponse<java.io.InputStream> response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(
                                        URI.create("http://localhost:" + port + "/v1/alerts/stream?tenantId=" + tenant))
                                .header("Accept", "text/event-stream")
                                .build(),
                        HttpResponse.BodyHandlers.ofInputStream());
        Thread.ofVirtual().start(() -> {
            try (var in = new BufferedReader(new InputStreamReader(response.body()))) {
                String line;
                while ((line = in.readLine()) != null) {
                    into.add(line);
                }
            } catch (java.io.IOException ignored) {
                // closed by the test run ending
            }
        });
        return into;
    }

    @Test
    @DisplayName(
            "rule + breaching aggregate -> FIRING on the topic, the queue, the list and the stream; a clear aggregate -> RESOLVED")
    void firesThenResolves() throws Exception {
        String tenant = tenant();
        awaitListenerAssigned();
        List<String> sse = new CopyOnWriteArrayList<>();
        streamLines(tenant, sse);
        await().atMost(Duration.ofSeconds(10)).until(() -> sse.contains(":connected"));

        publishRule("rule-" + tenant, tenant, Wire.thresholdRule(tenant, "ERROR_RATE", "GT", 0.05, "CDN"));
        publishAggregate(tenant, MetricName.ERROR_RATE, 0, 0.2);

        await().atMost(Duration.ofSeconds(60)).until(() -> alertsOf(tenant).size() == 1);
        var firing = alertsOf(tenant).get(0);
        assertThat(firing.key()).isEqualTo(tenant + ":rule-" + tenant);
        assertThat(firing.event().getState()).isEqualTo(AlertState.FIRING);
        assertThat(firing.event().getSeverity()).isEqualTo(Severity.CRITICAL);
        assertThat(firing.event().getCategory()).isEqualTo(AlertCategory.CDN);
        assertThat(firing.event().getObservedValue()).isEqualTo(0.2);
        assertThat(firing.event().getThreshold()).isEqualTo(0.05);
        assertThat(firing.event().getTitleId().toString()).isEqualTo("title-1");

        var queued = sqsMessagesFor(tenant);
        assertThat(queued).isNotEmpty();
        assertThat(queued.get(0).path("alertId").asText())
                .isEqualTo(firing.event().getAlertId().toString());
        assertThat(queued.get(0).path("state").asText()).isEqualTo("FIRING");

        await().atMost(Duration.ofSeconds(10))
                .until(() -> sse.stream().anyMatch(l -> l.startsWith("data:") && l.contains(tenant)));
        assertThat(sse).contains("event:alert");

        var list = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/alerts?tenantId=" + tenant))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(json.readTree(list.body())).hasSize(1);
        assertThat(json.readTree(list.body()).get(0).path("state").asText()).isEqualTo("FIRING");

        publishAggregate(tenant, MetricName.ERROR_RATE, 1, 0.01);

        await().atMost(Duration.ofSeconds(60)).until(() -> alertsOf(tenant).size() == 2);
        var resolved = alertsOf(tenant).stream()
                .filter(a -> a.event().getState() == AlertState.RESOLVED)
                .findFirst()
                .orElseThrow();
        assertThat(resolved.event().getAlertId()).isEqualTo(firing.event().getAlertId());
        assertThat(resolved.key()).isEqualTo(firing.key());
        await().atMost(Duration.ofSeconds(10))
                .until(() -> sse.stream().anyMatch(l -> l.contains("\"state\":\"RESOLVED\"")));
    }

    @Test
    @DisplayName("a redelivered aggregate produces no second alert")
    void duplicateAggregateProducesNoSecondAlert() throws Exception {
        String tenant = tenant();
        awaitListenerAssigned();
        publishRule("rule-" + tenant, tenant, Wire.thresholdRule(tenant, "ERROR_RATE", "GT", 0.05, "CDN"));
        publishAggregate(tenant, MetricName.ERROR_RATE, 0, 0.2);
        await().atMost(Duration.ofSeconds(60)).until(() -> alertsOf(tenant).size() == 1);
        double duplicatesBefore = duplicates();

        publishAggregate(tenant, MetricName.ERROR_RATE, 0, 0.2);
        publishAggregate(tenant, MetricName.ERROR_RATE, 0, 0.2);

        await().atMost(Duration.ofSeconds(60)).until(() -> duplicates() >= duplicatesBefore + 2);
        await().atMost(Duration.ofSeconds(30)).until(() -> ItSupport.groupCaughtUp(bootstrap(), "alert-engine", AGG));
        assertThat(alertsOf(tenant)).hasSize(1);
    }

    private double duplicates() {
        var counter = meters.find("streampulse.alert.evaluations")
                .tags("kind", "threshold", "outcome", "duplicate")
                .counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName("an upstream alert suppresses a downstream one end to end, and releases it when the root resolves")
    void suppressionAndRelease() throws Exception {
        String tenant = tenant();
        awaitListenerAssigned();
        publishRule("cdn-" + tenant, tenant, Wire.thresholdRule(tenant, "ERROR_RATE", "GT", 0.05, "CDN"));
        publishRule(
                "player-" + tenant,
                tenant,
                """
                {"tenantId":"%s","metric":"CONCURRENT_VIEWERS","comparator":"LT","threshold":10,\
                "severity":"WARNING","category":"PLAYER","dependsOn":["CDN"]}"""
                        .formatted(tenant));

        publishAggregate(tenant, MetricName.ERROR_RATE, 0, 0.5);
        await().atMost(Duration.ofSeconds(60)).until(() -> alertsOf(tenant).size() == 1);
        String rootId = alertsOf(tenant).get(0).event().getAlertId().toString();

        publishAggregate(tenant, MetricName.CONCURRENT_VIEWERS, 0, 1);
        await().atMost(Duration.ofSeconds(60)).until(() -> alertsOf(tenant).size() == 2);
        var suppressed = alertsOf(tenant).stream()
                .filter(a -> a.event().getState() == AlertState.SUPPRESSED)
                .findFirst()
                .orElseThrow();
        assertThat(suppressed.event().getSuppressedBy().toString()).isEqualTo(rootId);
        assertThat(suppressed.event().getCategory()).isEqualTo(AlertCategory.PLAYER);

        publishAggregate(tenant, MetricName.ERROR_RATE, 1, 0.0);
        await().atMost(Duration.ofSeconds(60)).until(() -> alertsOf(tenant).size() == 4);
        var states = alertsOf(tenant).stream().map(a -> a.event().getState()).toList();
        assertThat(states)
                .containsExactlyInAnyOrder(
                        AlertState.FIRING, AlertState.SUPPRESSED, AlertState.RESOLVED, AlertState.FIRING);
    }
}

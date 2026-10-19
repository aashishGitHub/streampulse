/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert;

import static com.streampulse.alert.support.Fixtures.errorRateAbove;
import static com.streampulse.alert.support.Fixtures.obs;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.streampulse.alert.domain.AlertEngine;
import com.streampulse.alert.domain.AlertRule;
import com.streampulse.alert.domain.Comparison;
import com.streampulse.alert.domain.Condition;
import com.streampulse.alert.domain.RuleBook;
import com.streampulse.alert.domain.Severity;
import com.streampulse.alert.support.NoDockerWebTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

class AlertApiTest extends NoDockerWebTest {

    @LocalServerPort
    private int port;

    @Autowired
    private RuleBook rules;

    @Autowired
    private AlertEngine engine;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    private HttpResponse<String> get(String path, String accept) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Accept", accept)
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @ParameterizedTest(name = "{0} with no tenantId is 400 problem+json")
    @ValueSource(strings = {"/v1/alerts", "/v1/alerts/stream"})
    @DisplayName("a missing tenantId is a 400 problem document on both operations, for the types each one serves")
    void missingTenant(String path) throws Exception {
        String served = path.endsWith("/stream") ? "text/event-stream" : "application/json";
        for (String accept : new String[] {served, "*/*"}) {
            var response = get(path, accept);

            assertThat(response.statusCode()).as(path + " " + accept).isEqualTo(400);
            assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
            JsonNode problem = json.readTree(response.body());
            assertThat(problem.path("status").asInt()).isEqualTo(400);
            assertThat(problem.path("detail").asText()).contains("tenantId");
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/v1/alerts?tenantId=",
                "/v1/alerts?tenantId=Bad_Tenant",
                "/v1/alerts/stream?tenantId=has%20space"
            })
    @DisplayName("a blank or malformed tenantId is a 400 problem document")
    void malformedTenant(String path) throws Exception {
        var response = get(path, "*/*");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
    }

    @Test
    @DisplayName("an unknown tenant has no alerts: 200 and an empty array")
    void emptyList() throws Exception {
        var response = get("/v1/alerts?tenantId=nobody", "application/json");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).isArray()).isTrue();
        assertThat(json.readTree(response.body())).isEmpty();
    }

    @Test
    @DisplayName("the list is JSON with exactly the documented fields, ISO-8601 instants and explicit nulls")
    void listShapeMatchesTheContract() throws Exception {
        AlertRule rule = new AlertRule(
                "api-r1",
                "api-tenant",
                "API rule",
                "ERROR_RATE",
                new Condition.Threshold(Comparison.GT, 0.05),
                Severity.CRITICAL,
                "CDN",
                Set.of(),
                null,
                null);
        rules.put(rule, 1);
        engine.evaluate(obs("api-tenant", "ERROR_RATE", 0, 0.3));

        var response = get("/v1/alerts?tenantId=api-tenant", "application/json");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        JsonNode list = json.readTree(response.body());
        assertThat(list).hasSize(1);
        JsonNode alert = list.get(0);

        assertThat(fieldNames(alert)).isEqualTo(documentedAlertFields());
        assertThat(alert.path("tenantId").asText()).isEqualTo("api-tenant");
        assertThat(alert.path("ruleId").asText()).isEqualTo("api-r1");
        assertThat(alert.path("series").asText()).isEqualTo("title-1:us-east-1:ERROR_RATE");
        assertThat(alert.path("state").asText()).isEqualTo("FIRING");
        assertThat(alert.path("severity").asText()).isEqualTo("CRITICAL");
        assertThat(java.time.Instant.parse(alert.path("firedAt").asText())).isNotNull();
        assertThat(alert.get("resolvedAt").isNull()).isTrue();
        assertThat(alert.get("suppressedBy").isNull()).isTrue();
    }

    @Test
    @DisplayName("tenants never see each other's alerts")
    void tenantIsolation() throws Exception {
        rules.put(errorRateAbove("iso-r1", 0.05, "CDN"), 1);
        engine.evaluate(obs("streamflix", "ERROR_RATE", 0, 0.9));

        assertThat(json.readTree(get("/v1/alerts?tenantId=someone-else", "application/json")
                        .body()))
                .isEmpty();
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static Set<String> documentedAlertFields() throws Exception {
        JsonNode doc = new ObjectMapper(new YAMLFactory()).readTree(Files.readString(AlertsApiContractTest.DOCUMENT));
        Set<String> fields = new HashSet<>();
        doc.at("/components/schemas/Alert/properties").fieldNames().forEachRemaining(fields::add);
        return fields;
    }
}

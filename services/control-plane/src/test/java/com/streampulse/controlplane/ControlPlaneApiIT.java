/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.streampulse.controlplane.support.PostgresIT;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** The whole stack over HTTP against a real Postgres: controller, validation, service, JPA, Flyway, outbox. */
class ControlPlaneApiIT extends PostgresIT {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};

    @Autowired
    private TestRestTemplate http;

    private ResponseEntity<Map<String, Object>> send(HttpMethod method, String path, Object body) {
        return http.exchange(path, method, body == null ? null : new HttpEntity<>(body), MAP);
    }

    private long outboxRows() {
        return jdbc.queryForObject("select count(*) from control_plane.outbox", Long.class);
    }

    private void tenant(String id, String parent) {
        Map<String, Object> body = parent == null
                ? Map.of("tenantId", id, "displayName", id)
                : Map.of("tenantId", id, "displayName", id, "parentTenantId", parent);
        assertThat(send(HttpMethod.POST, "/v1/admin/tenants", body).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("create answers 201 with a Location; a second create of the same id is 409 and announces nothing")
    void createAndDuplicate() {
        var created = send(HttpMethod.POST, "/v1/admin/tenants", Map.of("tenantId", "acme", "displayName", "Acme"));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getLocation()).hasPath("/v1/admin/tenants/acme");
        assertThat(created.getBody()).containsEntry("version", 0);
        assertThat(outboxRows()).isEqualTo(1);

        var duplicate = send(HttpMethod.POST, "/v1/admin/tenants", Map.of("tenantId", "acme", "displayName", "Again"));
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        assertThat(outboxRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("validation failures are 400 problem+json naming the fields")
    void validation() {
        var response = send(HttpMethod.POST, "/v1/admin/tenants", Map.of("tenantId", "BAD ID", "displayName", ""));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("errors").toString())
                .contains("tenantId")
                .contains("displayName");
    }

    @Test
    @DisplayName("a tenant cannot be re-parented under its own child: 422, state unchanged, nothing announced")
    void tenantCycleIsRejectedOverHttp() {
        tenant("acme", null);
        tenant("kids", "acme");
        long before = outboxRows();

        var response =
                send(HttpMethod.PUT, "/v1/admin/tenants/acme", Map.of("displayName", "Acme", "parentTenantId", "kids"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(send(HttpMethod.GET, "/v1/admin/tenants/acme", null).getBody())
                .containsEntry("parentTenantId", null);
        assertThat(outboxRows()).isEqualTo(before);
    }

    @Test
    @DisplayName("a rule graph cycle is 422; a chain is 201")
    void ruleCycleIsRejectedOverHttp() {
        tenant("acme", null);
        var first = send(
                HttpMethod.POST,
                "/v1/admin/rules",
                Map.of(
                        "ruleId",
                        "r1",
                        "tenantId",
                        "acme",
                        "metric",
                        "error_rate",
                        "threshold",
                        0.1,
                        "category",
                        "PLAYER",
                        "dependsOn",
                        List.of("PACKAGER")));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long before = outboxRows();

        var cyclic = send(
                HttpMethod.POST,
                "/v1/admin/rules",
                Map.of(
                        "ruleId",
                        "r2",
                        "tenantId",
                        "acme",
                        "metric",
                        "error_rate",
                        "threshold",
                        0.1,
                        "category",
                        "PACKAGER",
                        "dependsOn",
                        List.of("PLAYER")));

        assertThat(cyclic.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(cyclic.getBody().get("detail").toString()).contains("cycle");
        assertThat(send(HttpMethod.GET, "/v1/admin/rules/r2", null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(outboxRows()).isEqualTo(before);

        var chain = send(
                HttpMethod.POST,
                "/v1/admin/rules",
                Map.of(
                        "ruleId",
                        "r3",
                        "tenantId",
                        "acme",
                        "metric",
                        "error_rate",
                        "threshold",
                        0.1,
                        "category",
                        "PACKAGER",
                        "dependsOn",
                        List.of("CDN")));
        assertThat(chain.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("deleting a tenant that is still referenced is 409; the tenant stays and no tombstone is announced")
    void deletingATenantThatIsStillReferencedIsRefused() {
        tenant("acme", null);
        send(
                HttpMethod.POST,
                "/v1/admin/titles",
                Map.of("titleId", "t1", "tenantId", "acme", "name", "T", "kind", "VOD"));
        long before = outboxRows();

        var response = send(HttpMethod.DELETE, "/v1/admin/tenants/acme", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(send(HttpMethod.GET, "/v1/admin/tenants/acme", null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(outboxRows()).as("the rolled-back delete announced nothing").isEqualTo(before);
        assertThat(jdbc.queryForObject(
                        "select count(*) from control_plane.outbox where operation = 'DELETED'", Long.class))
                .isZero();
    }

    @Test
    @DisplayName("service nodes: a CDN -> packager -> player chain; a node others depend on cannot be deleted")
    void serviceNodeChain() {
        tenant("acme", null);
        for (var spec : List.of(
                Map.<String, Object>of("nodeId", "cdn", "tenantId", "acme", "name", "CDN", "category", "CDN"),
                Map.<String, Object>of(
                        "nodeId",
                        "pkg",
                        "tenantId",
                        "acme",
                        "name",
                        "Pkg",
                        "category",
                        "PACKAGER",
                        "dependsOn",
                        List.of("cdn")),
                Map.<String, Object>of(
                        "nodeId",
                        "player",
                        "tenantId",
                        "acme",
                        "name",
                        "P",
                        "category",
                        "PLAYER",
                        "dependsOn",
                        List.of("pkg")))) {
            assertThat(send(HttpMethod.POST, "/v1/admin/service-nodes", spec).getStatusCode())
                    .isEqualTo(HttpStatus.CREATED);
        }

        assertThat(send(HttpMethod.DELETE, "/v1/admin/service-nodes/cdn", null).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(send(HttpMethod.GET, "/v1/admin/service-nodes/cdn", null).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        var loop = send(
                HttpMethod.PUT,
                "/v1/admin/service-nodes/cdn",
                Map.of("name", "CDN", "category", "CDN", "dependsOn", List.of("player")));
        assertThat(loop.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("titles and apps: create, update, list, delete")
    void titlesAndApps() {
        tenant("acme", null);
        send(
                HttpMethod.POST,
                "/v1/admin/titles",
                Map.of("titleId", "t1", "tenantId", "acme", "name", "T", "kind", "VOD"));
        var updated = send(HttpMethod.PUT, "/v1/admin/titles/t1", Map.of("name", "Renamed", "kind", "LIVE"));
        assertThat(updated.getBody()).containsEntry("name", "Renamed").containsEntry("version", 1);

        assertThat(send(
                                HttpMethod.POST,
                                "/v1/admin/tenants/acme/apps",
                                Map.of("appId", "web", "name", "Web", "platform", "WEB"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(send(
                                HttpMethod.POST,
                                "/v1/admin/tenants/ghost/apps",
                                Map.of("appId", "x", "name", "X", "platform", "TV"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send(HttpMethod.DELETE, "/v1/admin/titles/t1", null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(send(HttpMethod.DELETE, "/v1/admin/tenants/acme/apps/web", null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("a rule for an unknown tenant is 404")
    void unknownTenant() {
        var response = send(
                HttpMethod.POST,
                "/v1/admin/rules",
                Map.of("ruleId", "r1", "tenantId", "ghost", "metric", "m", "threshold", 1));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("billing usage is 501 and says it is deferred")
    void billingIsDeferred() {
        var response = send(HttpMethod.GET, "/v1/billing/usage?month=2026-10", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
        assertThat(response.getBody().get("detail").toString()).contains("deferred");
    }
}

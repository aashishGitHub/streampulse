/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.mcp.adapter.out.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.streampulse.mcp.domain.Metric;
import com.streampulse.mcp.domain.MetricWindow;
import com.streampulse.mcp.domain.TitleOverview;
import com.streampulse.mcp.support.FakeBackend;
import com.streampulse.mcp.support.FakeBackend.Response;
import com.streampulse.platform.domain.TenantId;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HttpQueryApiClientTest {

    private static final TenantId ACME = TenantId.of("acme");
    private static final String OVERVIEW =
            """
            {"tenantId":"acme","titleId":"title-1","metadata":{"titleId":"title-1","name":"Show"},
             "latest":[
               {"titleId":"title-1","region":"us-east-1","metric":"ERROR_RATE","windowStart":"2026-10-13T11:58:00Z",
                "windowEnd":"2026-10-13T11:59:00Z","value":0.04,"sampleCount":321,"computedAt":"2026-10-13T11:59:05Z"},
               {"titleId":"title-1","region":"us-east-1","metric":"SOME_FUTURE_METRIC","windowStart":"2026-10-13T11:58:00Z",
                "windowEnd":"2026-10-13T11:59:00Z","value":1,"sampleCount":1,"computedAt":"2026-10-13T11:59:05Z"}],
             "history":[],"degraded":["history"]}
            """;
    private static final String WINDOWS =
            """
            {"windows":[
              {"windowStart":"2026-10-13T11:58:00Z","value":5000,"sampleCount":900,"isFinal":true,"titleId":"title-1","region":"us-east-1"},
              {"windowStart":"2026-10-13T11:59:00Z","value":5200,"sampleCount":950,"isFinal":true,"titleId":"","region":""}],
             "source":"store"}
            """;

    private final FakeBackend backend = FakeBackend.start();

    @AfterEach
    void stop() {
        backend.close();
    }

    private HttpQueryApiClient client(URI base, Duration timeout, int maxBytes) {
        return new HttpQueryApiClient(
                new JsonGetter(HttpClient.newHttpClient(), timeout, maxBytes, new ObjectMapper()), base);
    }

    private HttpQueryApiClient client() {
        return client(backend.baseUri(), Duration.ofSeconds(2), 64 * 1024);
    }

    @Test
    @DisplayName(
            "overview: GET /v1/titles/{id}/overview?tenantId=; latest parsed with windowEnd; unknown metrics skipped")
    void overview() {
        backend.respondWith(Response.json(OVERVIEW));

        TitleOverview overview = client().titleOverview(ACME, "title-1");

        assertThat(overview.tenantId()).isEqualTo("acme");
        assertThat(overview.degraded()).containsExactly("history");
        assertThat(overview.latest()).hasSize(1);
        MetricWindow w = overview.latest().get(0);
        assertThat(w.metric()).isEqualTo(Metric.ERROR_RATE);
        assertThat(w.windowEnd()).isEqualTo(Instant.parse("2026-10-13T11:59:00Z"));
        assertThat(w.sampleCount()).isEqualTo(321);
        assertThat(backend.requests()).hasSize(1);
        assertThat(backend.requests().get(0).method()).isEqualTo("GET");
        assertThat(backend.requests().get(0).uri().toString()).isEqualTo("/v1/titles/title-1/overview?tenantId=acme");
    }

    @Test
    @DisplayName("metric windows: path carries tenant and metric, query carries the range and the optional filters")
    void metricWindows() {
        backend.respondWith(Response.json(WINDOWS));
        Instant from = Instant.parse("2026-10-13T11:45:00Z");
        Instant to = Instant.parse("2026-10-13T12:00:00Z");

        List<MetricWindow> windows = client().metricWindows(ACME, Metric.CONCURRENT_VIEWERS, "title-1", null, from, to);

        assertThat(windows).hasSize(2);
        assertThat(windows.get(0).windowEnd()).isNull();
        assertThat(windows.get(0).value()).isEqualTo(5000);
        assertThat(windows.get(1).titleId()).isEmpty();
        FakeBackend.Request sent = backend.requests().get(0);
        assertThat(sent.method()).isEqualTo("GET");
        assertThat(sent.uri().getPath()).isEqualTo("/v1/metrics/acme/CONCURRENT_VIEWERS");
        assertThat(sent.uri().getQuery())
                .isEqualTo("from=2026-10-13T11:45:00Z&to=2026-10-13T12:00:00Z&titleId=title-1");
    }

    @Test
    @DisplayName("4xx, 5xx, timeout, unreachable, malformed and oversized map to named failures of query-api")
    void failures() {
        backend.respondWith(Response.status(404, "application/problem+json", "{\"detail\":\"x\"}"));
        assertThatThrownBy(() -> client().titleOverview(ACME, "t"))
                .hasMessage("query-api rejected the request (status 404)");

        backend.respondWith(Response.status(500, "text/plain", "boom"));
        assertThatThrownBy(() -> client().titleOverview(ACME, "t")).hasMessage("query-api failed (status 500)");

        backend.respondWith(Response.slow(Duration.ofSeconds(5)));
        HttpQueryApiClient fast = client(backend.baseUri(), Duration.ofMillis(300), 64 * 1024);
        assertThatThrownBy(() -> fast.titleOverview(ACME, "t")).hasMessage("query-api timed out");

        HttpQueryApiClient dead = client(FakeBackend.deadUri(), Duration.ofSeconds(2), 64 * 1024);
        assertThatThrownBy(() -> dead.titleOverview(ACME, "t")).hasMessage("query-api unreachable");

        backend.respondWith(Response.json("{\"tenantId\":"));
        assertThatThrownBy(() -> client().titleOverview(ACME, "t"))
                .hasMessage("query-api returned a malformed response");

        backend.respondWith(Response.json("{\"windows\":\"nope\"}"));
        assertThatThrownBy(() -> client().metricWindows(
                                ACME, Metric.ERROR_RATE, null, null, Instant.EPOCH, Instant.EPOCH.plusSeconds(1)))
                .hasMessage("query-api returned a malformed response");

        backend.respondWith(Response.bytes(("{\"windows\":[" + "1,".repeat(100_000) + "1]}").getBytes()));
        HttpQueryApiClient small = client(backend.baseUri(), Duration.ofSeconds(2), 1024);
        assertThatThrownBy(() -> small.titleOverview(ACME, "t"))
                .hasMessage("query-api returned a response that is too large");
    }

    @Test
    @DisplayName("every request this client sends is a GET")
    void onlyGetRequestsAreSent() {
        backend.respondWith(Response.json(OVERVIEW));
        client().titleOverview(ACME, "title-1");
        backend.respondWith(Response.json(WINDOWS));
        client().metricWindows(ACME, Metric.ERROR_RATE, null, "us-east-1", Instant.EPOCH, Instant.EPOCH.plusSeconds(1));
        assertThat(backend.requests()).hasSize(2).allSatisfy(r -> assertThat(r.method())
                .isEqualTo("GET"));
    }
}

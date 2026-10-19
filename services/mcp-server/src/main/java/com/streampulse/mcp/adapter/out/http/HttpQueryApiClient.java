/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.mcp.adapter.out.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.streampulse.mcp.application.BackendFailure;
import com.streampulse.mcp.application.BackendFailure.Category;
import com.streampulse.mcp.application.port.MetricsPort;
import com.streampulse.mcp.domain.Backend;
import com.streampulse.mcp.domain.Metric;
import com.streampulse.mcp.domain.MetricWindow;
import com.streampulse.mcp.domain.TitleOverview;
import com.streampulse.platform.domain.TenantId;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** GET /v1/titles/{id}/overview and GET /v1/metrics/{tenant}/{metric} (api/openapi/query-v1.yaml). */
public final class HttpQueryApiClient implements MetricsPort {

    private final JsonGetter getter;
    private final URI base;

    public HttpQueryApiClient(JsonGetter getter, URI base) {
        this.getter = getter;
        this.base = base;
    }

    @Override
    public TitleOverview titleOverview(TenantId tenant, String titleId) {
        URI uri = URI.create(root() + "/v1/titles/" + enc(titleId) + "/overview?tenantId=" + enc(tenant.value()));
        JsonNode body = getter.get(Backend.QUERY_API, uri);
        if (!body.isObject()
                || !body.path("tenantId").isTextual()
                || !body.path("latest").isArray()) {
            throw malformed();
        }
        List<MetricWindow> latest = new ArrayList<>();
        for (JsonNode row : body.get("latest")) {
            Optional<Metric> metric = Metric.parse(row.path("metric").asText(null));
            if (metric.isEmpty()) {
                continue; // a metric this server does not know is not shown, rather than guessed at
            }
            latest.add(window(row, metric.get(), true));
        }
        List<String> degraded = new ArrayList<>();
        JsonNode degradedNode = body.path("degraded");
        if (degradedNode.isArray()) {
            degradedNode.forEach(d -> degraded.add(d.asText("")));
        }
        return new TitleOverview(body.get("tenantId").asText(), titleId, latest, degraded);
    }

    @Override
    public List<MetricWindow> metricWindows(
            TenantId tenant, Metric metric, String titleId, String region, Instant from, Instant to) {
        StringBuilder query = new StringBuilder("from=")
                .append(enc(from.toString()))
                .append("&to=")
                .append(enc(to.toString()));
        if (titleId != null) {
            query.append("&titleId=").append(enc(titleId));
        }
        if (region != null) {
            query.append("&region=").append(enc(region));
        }
        URI uri = URI.create(root() + "/v1/metrics/" + enc(tenant.value()) + "/" + metric.name() + "?" + query);
        JsonNode body = getter.get(Backend.QUERY_API, uri);
        if (!body.isObject() || !body.path("windows").isArray()) {
            throw malformed();
        }
        List<MetricWindow> windows = new ArrayList<>();
        for (JsonNode row : body.get("windows")) {
            windows.add(window(row, metric, false));
        }
        return windows;
    }

    private static MetricWindow window(JsonNode row, Metric metric, boolean hasEnd) {
        if (!row.isObject()
                || !row.path("value").isNumber()
                || !row.path("sampleCount").isIntegralNumber()) {
            throw malformed();
        }
        try {
            Instant end = hasEnd ? Instant.parse(row.path("windowEnd").asText()) : null;
            return new MetricWindow(
                    row.path("titleId").asText(""),
                    row.path("region").asText(""),
                    metric,
                    Instant.parse(row.path("windowStart").asText()),
                    end,
                    row.get("value").asDouble(),
                    row.get("sampleCount").asLong());
        } catch (DateTimeParseException e) {
            throw malformed();
        }
    }

    private String root() {
        String s = base.toString();
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static BackendFailure malformed() {
        return new BackendFailure(Backend.QUERY_API, Category.MALFORMED, 0);
    }
}

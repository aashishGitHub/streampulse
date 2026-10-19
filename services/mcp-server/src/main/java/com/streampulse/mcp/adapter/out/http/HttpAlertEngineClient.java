/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.mcp.adapter.out.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.streampulse.mcp.application.BackendFailure;
import com.streampulse.mcp.application.BackendFailure.Category;
import com.streampulse.mcp.application.port.AlertsPort;
import com.streampulse.mcp.domain.AlertSnapshot;
import com.streampulse.mcp.domain.AlertState;
import com.streampulse.mcp.domain.Backend;
import com.streampulse.mcp.domain.Severity;
import com.streampulse.platform.domain.TenantId;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/** GET /v1/alerts?tenantId= (api/openapi/alerts-v1.yaml). */
public final class HttpAlertEngineClient implements AlertsPort {

    private final JsonGetter getter;
    private final URI base;

    public HttpAlertEngineClient(JsonGetter getter, URI base) {
        this.getter = getter;
        this.base = base;
    }

    @Override
    public List<AlertSnapshot> activeAlerts(TenantId tenant) {
        URI uri = URI.create(
                trim(base) + "/v1/alerts?tenantId=" + URLEncoder.encode(tenant.value(), StandardCharsets.UTF_8));
        JsonNode body = getter.get(Backend.ALERT_ENGINE, uri);
        if (!body.isArray()) {
            throw malformed();
        }
        List<AlertSnapshot> alerts = new ArrayList<>(body.size());
        for (JsonNode item : body) {
            alerts.add(alert(item));
        }
        return alerts;
    }

    private static AlertSnapshot alert(JsonNode n) {
        if (!n.isObject()) {
            throw malformed();
        }
        try {
            return new AlertSnapshot(
                    text(n, "alertId", true),
                    text(n, "tenantId", true),
                    text(n, "ruleId", false),
                    text(n, "series", false),
                    AlertState.valueOf(text(n, "state", true)),
                    Severity.valueOf(text(n, "severity", true)),
                    text(n, "summary", false),
                    Instant.parse(text(n, "firedAt", true)),
                    instantOrNull(n, "resolvedAt"),
                    text(n, "suppressedBy", false));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw malformed();
        }
    }

    private static String text(JsonNode n, String field, boolean required) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            if (required) {
                throw malformed();
            }
            return null;
        }
        if (!v.isTextual()) {
            throw malformed();
        }
        return v.asText();
    }

    private static Instant instantOrNull(JsonNode n, String field) {
        String v = text(n, field, false);
        return v == null ? null : Instant.parse(v);
    }

    private static String trim(URI base) {
        String s = base.toString();
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static BackendFailure malformed() {
        return new BackendFailure(Backend.ALERT_ENGINE, Category.MALFORMED, 0);
    }
}

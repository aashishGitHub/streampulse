/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.adapter.in.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.streampulse.alert.domain.AlertRule;
import com.streampulse.alert.domain.Comparison;
import com.streampulse.alert.domain.Condition;
import com.streampulse.alert.domain.Condition.RateOfChange.Direction;
import com.streampulse.alert.domain.Severity;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Parses the JSON payload of an ALERT_RULE change. The shape is the control plane's
 * {@code AlertRule} schema (ruleId, tenantId, metric, comparator, threshold, severity, category,
 * dependsOn) plus optional {@code name}, {@code kind}, {@code fraction}, {@code direction},
 * {@code windows}, {@code titleId}, {@code region}. Unknown fields are ignored so the control plane can
 * add fields without breaking this consumer. See specs/06-alert-engine/design.md.
 */
public final class RuleJsonParser {

    private final ObjectMapper mapper;

    public RuleJsonParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * @param ruleId the record key; it, not the payload, names the rule so a tombstone can remove it
     * @param fallbackTenant used when the payload carries no tenant
     */
    public AlertRule parse(String ruleId, String fallbackTenant, String json) throws RuleParseException {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new RuleParseException("json", "payload is not JSON", e);
        }
        if (root == null || !root.isObject()) {
            throw new RuleParseException("json", "payload is not a JSON object", null);
        }

        try {
            String tenant = text(root, "tenantId", fallbackTenant);
            String metric = require(text(root, "metric", null), "metric").toUpperCase(Locale.ROOT);
            String node = text(root, "category", AlertRule.UNCATEGORISED).toUpperCase(Locale.ROOT);
            return new AlertRule(
                    ruleId,
                    require(tenant, "tenantId"),
                    text(root, "name", ruleId),
                    metric,
                    condition(root),
                    Severity.valueOf(text(root, "severity", "WARNING").toUpperCase(Locale.ROOT)),
                    node,
                    upstream(root),
                    text(root, "titleId", null),
                    text(root, "region", null));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new RuleParseException("invalid", e.getMessage(), e);
        }
    }

    private static Condition condition(JsonNode root) {
        String kind = text(root, "kind", "THRESHOLD").toUpperCase(Locale.ROOT);
        return switch (kind) {
            case "THRESHOLD" ->
                new Condition.Threshold(
                        Comparison.valueOf(text(root, "comparator", "GT").toUpperCase(Locale.ROOT)),
                        number(root, "threshold"));
            case "RATE_OF_CHANGE" ->
                new Condition.RateOfChange(
                        Direction.valueOf(text(root, "direction", "EITHER").toUpperCase(Locale.ROOT)),
                        number(root, "fraction"));
            case "ABSENCE" -> new Condition.Absence((int) number(root, "windows"));
            default -> throw new IllegalArgumentException("unknown kind " + kind);
        };
    }

    private static Set<String> upstream(JsonNode root) {
        Set<String> upstream = new LinkedHashSet<>();
        JsonNode dependsOn = root.get("dependsOn");
        if (dependsOn != null && !dependsOn.isNull()) {
            if (!dependsOn.isArray()) {
                throw new IllegalArgumentException("dependsOn must be an array");
            }
            dependsOn.forEach(item -> upstream.add(item.asText().toUpperCase(Locale.ROOT)));
        }
        return upstream;
    }

    private static String text(JsonNode root, String field, String fallback) {
        JsonNode value = root.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? fallback : value.asText();
    }

    private static double number(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isNumber()) {
            throw new IllegalArgumentException(field + " is required and must be a number");
        }
        return value.asDouble();
    }

    private static String require(String value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}

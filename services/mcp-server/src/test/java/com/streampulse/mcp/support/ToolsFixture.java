/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.mcp.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.streampulse.mcp.adapter.in.mcp.McpTools;
import com.streampulse.mcp.adapter.in.mcp.ToolViews;
import com.streampulse.mcp.application.ActiveAlertsService;
import com.streampulse.mcp.application.ExplainAlertService;
import com.streampulse.mcp.application.LiveMetricsService;
import com.streampulse.mcp.application.ToolLimits;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** McpTools wired to fake ports: everything above the HTTP adapters, nothing below. */
public final class ToolsFixture {

    public static final ObjectMapper JSON = new ObjectMapper();

    public final FakePorts.Alerts alerts = new FakePorts.Alerts();
    public final FakePorts.Metrics metrics = new FakePorts.Metrics();
    public final ToolLimits limits;
    public final McpTools tools;

    public ToolsFixture() {
        this(new ToolLimits(50, 100, 20, 200, Duration.ofMinutes(15)));
    }

    public ToolsFixture(ToolLimits limits) {
        this.limits = limits;
        this.tools = new McpTools(
                new ActiveAlertsService(alerts, TestData.CLOCK, limits),
                new LiveMetricsService(metrics, TestData.CLOCK, limits),
                new ExplainAlertService(alerts, metrics, TestData.CLOCK, limits),
                new ToolViews(limits),
                JSON);
    }

    public static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }

    public static JsonNode json(CallToolResult result) {
        try {
            return JSON.readTree(text(result));
        } catch (Exception e) {
            throw new AssertionError("tool result is not JSON: " + text(result), e);
        }
    }

    /** Every JSON path whose string value contains the needle. */
    public static List<String> pathsContaining(JsonNode root, String needle) {
        List<String> paths = new ArrayList<>();
        walk(root, "$", needle, paths);
        return paths;
    }

    private static void walk(JsonNode node, String path, String needle, List<String> out) {
        if (node.isTextual() && node.asText().contains(needle)) {
            out.add(path);
        } else if (node.isObject()) {
            node.properties().forEach(e -> walk(e.getValue(), path + "." + e.getKey(), needle, out));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                walk(node.get(i), path + "[" + i + "]", needle, out);
            }
        }
    }
}

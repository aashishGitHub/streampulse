/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.mcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.streampulse.mcp.adapter.in.mcp.McpTools;
import com.streampulse.mcp.adapter.in.mcp.ToolViews;
import com.streampulse.mcp.adapter.out.http.HttpAlertEngineClient;
import com.streampulse.mcp.adapter.out.http.HttpQueryApiClient;
import com.streampulse.mcp.adapter.out.http.JsonGetter;
import com.streampulse.mcp.application.ActiveAlertsService;
import com.streampulse.mcp.application.ExplainAlertService;
import com.streampulse.mcp.application.LiveMetricsService;
import com.streampulse.mcp.application.ToolLimits;
import com.streampulse.mcp.application.port.AlertsPort;
import com.streampulse.mcp.application.port.MetricsPort;
import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The composition root: the one place that knows which adapter implements which port. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(McpProperties.class)
class McpServerConfig {

    // Deliberately not a bean: Spring AI's server starter builds its own ObjectMapper for the protocol
    // messages, and a second bean of the same type would make that wiring ambiguous.
    private static final ObjectMapper JSON = new ObjectMapper();

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ToolLimits toolLimits(McpProperties p) {
        McpProperties.Limits l = p.limits();
        return new ToolLimits(l.maxAlerts(), l.maxMetricRows(), l.maxRecentWindows(), l.textChars(), l.lookback());
    }

    @Bean
    HttpClient backendHttpClient(McpProperties p) {
        return HttpClient.newBuilder()
                .connectTimeout(p.queryApi().timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Bean
    MetricsPort metricsPort(HttpClient client, McpProperties p) {
        return new HttpQueryApiClient(
                new JsonGetter(client, p.queryApi().timeout(), p.maxBodyBytes(), JSON),
                p.queryApi().baseUrl());
    }

    @Bean
    AlertsPort alertsPort(HttpClient client, McpProperties p) {
        return new HttpAlertEngineClient(
                new JsonGetter(client, p.alertEngine().timeout(), p.maxBodyBytes(), JSON),
                p.alertEngine().baseUrl());
    }

    @Bean
    McpTools mcpTools(AlertsPort alerts, MetricsPort metrics, Clock clock, ToolLimits limits) {
        return new McpTools(
                new ActiveAlertsService(alerts, clock, limits),
                new LiveMetricsService(metrics, clock, limits),
                new ExplainAlertService(alerts, metrics, clock, limits),
                new ToolViews(limits),
                JSON);
    }
}

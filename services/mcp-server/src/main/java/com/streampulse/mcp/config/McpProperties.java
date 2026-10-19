/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.mcp.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Defaults are targets until measured. Backend URLs may come from environment variables (see .mcp.json). */
@ConfigurationProperties("streampulse.mcp")
public record McpProperties(
        @DefaultValue Backend queryApi,
        @DefaultValue Backend alertEngine,
        @DefaultValue("262144") int maxBodyBytes,
        @DefaultValue Limits limits) {

    public record Backend(@DefaultValue("http://localhost:8082") URI baseUrl, @DefaultValue("3s") Duration timeout) {}

    public record Limits(
            @DefaultValue("50") int maxAlerts,
            @DefaultValue("100") int maxMetricRows,
            @DefaultValue("20") int maxRecentWindows,
            @DefaultValue("200") int textChars,
            @DefaultValue("15m") Duration lookback) {}
}

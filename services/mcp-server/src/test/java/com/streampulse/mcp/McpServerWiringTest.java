/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.streampulse.mcp.application.port.AlertsPort;
import com.streampulse.mcp.application.port.MetricsPort;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

/** The whole context starts with no Docker, no backend and no stdin: the HTTP clients connect lazily. */
@SpringBootTest(
        classes = McpServerApplication.class,
        properties = {
            "spring.ai.mcp.server.stdio=false",
            "streampulse.mcp.query-api.base-url=http://127.0.0.1:1",
            "streampulse.mcp.alert-engine.base-url=http://127.0.0.1:1"
        })
class McpServerWiringTest {

    // 'toolSpecs' is what Spring AI builds from @McpTool methods; 'syncTools' is a second list it builds from
    // ToolCallback beans (empty here)
    // ToolCallback beans
    @Autowired
    @Qualifier("toolSpecs")
    List<SyncToolSpecification> tools;

    @Autowired
    AlertsPort alerts;

    @Autowired
    MetricsPort metrics;

    @Test
    @DisplayName("the context starts and registers exactly the three read-only tools")
    void registersThreeReadOnlyTools() {
        assertThat(tools)
                .extracting(t -> t.tool().name())
                .containsExactlyInAnyOrder("get_live_metrics", "list_active_alerts", "explain_alert");
        assertThat(tools).allSatisfy(t -> {
            assertThat(t.tool().annotations().readOnlyHint()).isTrue();
            assertThat(t.tool().annotations().destructiveHint()).isFalse();
        });
    }

    @Test
    @DisplayName("the ports are the HTTP clients, and a dead backend is reported, not thrown raw")
    void portsAreWired() {
        assertThat(alerts).isNotNull();
        assertThat(metrics).isNotNull();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> alerts.activeAlerts(com.streampulse.platform.domain.TenantId.of("acme")))
                .hasMessage("alert-engine unreachable");
    }
}

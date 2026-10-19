/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.mcp.application;

import static com.streampulse.mcp.support.TestData.CLOCK;
import static com.streampulse.mcp.support.TestData.NOW;
import static com.streampulse.mcp.support.TestData.historyWindow;
import static com.streampulse.mcp.support.TestData.window;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streampulse.mcp.domain.Backend;
import com.streampulse.mcp.domain.Metric;
import com.streampulse.mcp.domain.MetricWindow;
import com.streampulse.mcp.domain.TitleOverview;
import com.streampulse.mcp.support.FakePorts;
import com.streampulse.platform.domain.TenantId;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LiveMetricsServiceTest {

    private static final TenantId ACME = TenantId.of("acme");

    private final FakePorts.Metrics metrics = new FakePorts.Metrics();

    private LiveMetricsService service(int maxRows) {
        return new LiveMetricsService(metrics, CLOCK, new ToolLimits(50, maxRows, 20, 200, Duration.ofMinutes(15)));
    }

    @Test
    @DisplayName("without a title: both live metrics are queried over the lookback; newest window per series wins")
    void latestPerSeries() {
        metrics.windows = call -> call.metric() == Metric.ERROR_RATE
                ? List.of(
                        historyWindow("title-1", "us-east-1", Metric.ERROR_RATE, NOW.minusSeconds(240), 0.01),
                        historyWindow("title-1", "us-east-1", Metric.ERROR_RATE, NOW.minusSeconds(180), 0.09),
                        historyWindow("title-1", "eu-west-1", Metric.ERROR_RATE, NOW.minusSeconds(180), 0.02))
                : List.of(
                        historyWindow("title-1", "us-east-1", Metric.CONCURRENT_VIEWERS, NOW.minusSeconds(120), 5000));

        LiveMetrics result = service(100).liveMetrics(ACME, Optional.empty());

        assertThat(metrics.windowCalls)
                .extracting(FakePorts.MetricCall::metric)
                .containsExactlyInAnyOrder(Metric.CONCURRENT_VIEWERS, Metric.ERROR_RATE);
        assertThat(metrics.windowCalls).allSatisfy(c -> {
            assertThat(c.tenant()).isEqualTo(ACME);
            assertThat(c.titleId()).isNull();
            assertThat(c.to()).isEqualTo(NOW);
            assertThat(c.from()).isEqualTo(NOW.minus(Duration.ofMinutes(15)));
        });
        assertThat(result.readings()).hasSize(3);
        LiveReading us = result.readings().stream()
                .filter(r ->
                        r.window().region().equals("us-east-1") && r.window().metric() == Metric.ERROR_RATE)
                .findFirst()
                .orElseThrow();
        assertThat(us.window().value()).isEqualTo(0.09);
        assertThat(us.ageSeconds()).isEqualTo(180);
        assertThat(us.basis()).isEqualTo(AgeBasis.WINDOW_START);
    }

    @Test
    @DisplayName("with a title: the overview is used, age is measured from windowEnd, degraded parts are reported")
    void titleOverview() {
        metrics.overview = new TitleOverview(
                "acme",
                "title-1",
                List.of(
                        window("title-1", "us-east-1", Metric.ERROR_RATE, NOW.minusSeconds(120), 0.05),
                        window("title-1", "us-east-1", Metric.STARTUP_TIME_MS, NOW.minusSeconds(120), 900),
                        window("title-9", "us-east-1", Metric.ERROR_RATE, NOW.minusSeconds(120), 0.5)),
                List.of("history"));

        LiveMetrics result = service(100).liveMetrics(ACME, Optional.of("title-1"));

        assertThat(metrics.overviewCalls).containsExactly("acme/title-1");
        assertThat(metrics.windowCalls).isEmpty();
        assertThat(result.readings()).hasSize(1);
        LiveReading r = result.readings().get(0);
        assertThat(r.basis()).isEqualTo(AgeBasis.WINDOW_END);
        assertThat(r.ageSeconds()).isEqualTo(60);
        assertThat(result.degraded()).containsExactly("history");
    }

    @Test
    @DisplayName("an overview that names another tenant is rejected, not shown")
    void overviewOfAnotherTenant() {
        metrics.overview = new TitleOverview(
                "rival",
                "title-1",
                List.of(window("title-1", "us-east-1", Metric.ERROR_RATE, NOW.minusSeconds(120), 0.05)),
                List.of());
        assertThatThrownBy(() -> service(100).liveMetrics(ACME, Optional.of("title-1")))
                .isInstanceOf(BackendFailure.class)
                .hasMessageContaining("query-api");
    }

    @Test
    @DisplayName("a window in the future (clock skew) has age 0, never negative")
    void ageIsNeverNegative() {
        metrics.windows = call -> List.of(new MetricWindow("t", "r", call.metric(), NOW.plusSeconds(30), null, 1, 1));
        assertThat(service(100).liveMetrics(ACME, Optional.empty()).readings())
                .allSatisfy(r -> assertThat(r.ageSeconds()).isZero());
    }

    @Test
    @DisplayName("over the row limit the result is cut and the cut is counted")
    void truncates() {
        metrics.windows = call -> List.of(
                historyWindow("a", "r", call.metric(), NOW.minusSeconds(60), 1),
                historyWindow("b", "r", call.metric(), NOW.minusSeconds(60), 1),
                historyWindow("c", "r", call.metric(), NOW.minusSeconds(60), 1));
        LiveMetrics result = service(4).liveMetrics(ACME, Optional.empty());
        assertThat(result.readings()).hasSize(4);
        assertThat(result.total()).isEqualTo(6);
        assertThat(result.omitted()).isEqualTo(2);
    }

    @Test
    @DisplayName("one failing query fails the whole call; no partial data is presented as complete")
    void failureFailsTheCall() {
        metrics.fails = new BackendFailure(Backend.QUERY_API, BackendFailure.Category.TIMED_OUT, 0);
        assertThatThrownBy(() -> service(100).liveMetrics(ACME, Optional.empty()))
                .hasMessage("query-api timed out");
    }
}

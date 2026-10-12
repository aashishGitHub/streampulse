/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.query.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.streampulse.query.adapter.in.sse.Coalescer;
import com.streampulse.query.adapter.in.sse.Heartbeat;
import com.streampulse.query.adapter.in.sse.LiveFeed;
import com.streampulse.query.adapter.in.sse.SseStreamController;
import com.streampulse.query.application.BoundedExecutor;
import com.streampulse.query.application.HistoryQuery;
import com.streampulse.query.application.LatestMetricsPort;
import com.streampulse.query.application.MetricHistoryPort;
import com.streampulse.query.application.MetricQueryService;
import com.streampulse.query.application.TitleCatalogPort;
import com.streampulse.query.application.TitleOverviewService;
import com.streampulse.query.domain.EntryId;
import com.streampulse.query.domain.LatestMetric;
import com.streampulse.query.domain.MetricPoint;
import com.streampulse.query.domain.MetricUpdate;
import com.streampulse.query.domain.TitleMetadata;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

class QueryControllersTest {

    private static final Instant WINDOW = Instant.parse("2026-01-15T12:00:00Z");

    private final ThreadPoolExecutor executor = BoundedExecutor.create("blocking", 4, 8);
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        LatestMetricsPort latest = (tenant, title) -> List.of(
                new LatestMetric(title, "us-east-1", "ERROR_RATE", WINDOW, WINDOW.plusSeconds(10), 0.02, 40, WINDOW));
        MetricHistoryPort history = new MetricHistoryPort() {
            @Override
            public List<MetricPoint> range(HistoryQuery query) {
                return List.of(new MetricPoint("title-1", "us-east-1", "ERROR_RATE", WINDOW, 0.02, 40));
            }
        };
        TitleCatalogPort catalog = (tenant, title) -> new TitleMetadata(title, "The Title");
        Clock clock = Clock.systemUTC();
        var overview = new TitleOverviewService(
                latest, history, catalog, executor, clock, Duration.ofMillis(300), Duration.ofMinutes(15));
        var query = new MetricQueryService(history, executor);
        LiveFeed feed = new LiveFeed() {
            @Override
            public Flux<MetricUpdate> live() {
                return Flux.never();
            }

            @Override
            public Flux<MetricUpdate> replayAfter(EntryId after) {
                return Flux.empty();
            }
        };
        var sse =
                new SseStreamController(feed, new Coalescer(Duration.ofMillis(50)), new Heartbeat(Duration.ofHours(1)));

        client = WebTestClient.bindToController(new OverviewController(overview), new MetricQueryController(query), sse)
                .controllerAdvice(new ProblemAdvice())
                .build();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    @DisplayName("the overview answers 200 with the three parts and an empty degraded list")
    void overviewAnswers() {
        client.get()
                .uri("/v1/titles/title-1/overview?tenantId=t1")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.titleId")
                .isEqualTo("title-1")
                .jsonPath("$.metadata.name")
                .isEqualTo("The Title")
                .jsonPath("$.latest[0].metric")
                .isEqualTo("ERROR_RATE")
                .jsonPath("$.history[0].sampleCount")
                .isEqualTo(40)
                .jsonPath("$.degraded.length()")
                .isEqualTo(0);
    }

    @Test
    @DisplayName("a missing tenantId is a 400 problem+json")
    void missingTenantIsBadRequest() {
        client.get()
                .uri("/v1/titles/title-1/overview")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo(400);
    }

    @Test
    @DisplayName("a metric query returns windows oldest first and reports the tier that answered")
    void metricQueryReportsSource() {
        client.get()
                .uri("/v1/metrics/t1/ERROR_RATE?from=2026-01-15T11:00:00Z&to=2026-01-15T13:00:00Z&titleId=title-1")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.source")
                .isEqualTo("store")
                .jsonPath("$.windows[0].value")
                .isEqualTo(0.02)
                .jsonPath("$.windows[0].isFinal")
                .isEqualTo(true)
                .jsonPath("$.windows[0].region")
                .isEqualTo("us-east-1");
    }

    @Test
    @DisplayName("a metric the system does not produce is a 404 problem+json")
    void unknownMetricIsNotFound() {
        client.get()
                .uri("/v1/metrics/t1/STARTUP_TIME_MS?from=2026-01-15T11:00:00Z&to=2026-01-15T13:00:00Z")
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    @DisplayName("an inverted range is a 400 problem+json")
    void invertedRangeIsBadRequest() {
        client.get()
                .uri("/v1/metrics/t1/ERROR_RATE?from=2026-01-15T13:00:00Z&to=2026-01-15T11:00:00Z")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    @DisplayName("an unparseable timestamp is a 400 problem+json")
    void unparseableTimestampIsBadRequest() {
        client.get()
                .uri("/v1/metrics/t1/ERROR_RATE?from=yesterday&to=today")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    @DisplayName("a malformed Last-Event-ID is a 400 problem+json")
    void malformedLastEventIdIsBadRequest() {
        var result = client.get()
                .uri("/v1/stream/metrics?tenantId=t1")
                .header("Last-Event-ID", "not-an-id")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(String.class)
                .returnResult();

        assertThat(result.getResponseBody()).contains("Last-Event-ID");
    }
}

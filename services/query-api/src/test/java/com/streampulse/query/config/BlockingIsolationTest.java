/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.query.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.streampulse.query.application.HistoryQuery;
import com.streampulse.query.application.LatestMetricsPort;
import com.streampulse.query.application.MetricHistoryPort;
import com.streampulse.query.application.TitleCatalogPort;
import com.streampulse.query.domain.LatestMetric;
import com.streampulse.query.domain.MetricPoint;
import com.streampulse.query.domain.TitleMetadata;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;

/**
 * J2: a real HTTP request over real Netty, with every blocking port replaced by one that records its
 * thread. None may run on an event-loop thread; all must run on the named executor. The filter records
 * the request's own thread, so a green result cannot come from a mock server with no event loop.
 *
 * <p>The server's loop threads are named {@code webflux-http-nio-N} (Spring's ReactorResourceFactory), not
 * {@code reactor-http-nio-N} (Reactor Netty's own default); matching on {@code http-nio} covers both.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "streampulse.query.stream.auto-start=false")
class BlockingIsolationTest {

    private static final Queue<String> REQUEST_THREADS = new ConcurrentLinkedQueue<>();
    private static final Queue<String> PORT_THREADS = new ConcurrentLinkedQueue<>();

    @TestConfiguration
    static class Recording {

        @Bean
        WebFilter threadRecordingFilter() {
            return (exchange, chain) -> {
                REQUEST_THREADS.add(Thread.currentThread().getName());
                return chain.filter(exchange);
            };
        }

        @Bean
        @Primary
        LatestMetricsPort recordingLatest() {
            return (tenant, title) -> {
                PORT_THREADS.add(Thread.currentThread().getName());
                return List.<LatestMetric>of();
            };
        }

        @Bean
        @Primary
        MetricHistoryPort recordingHistory() {
            return new MetricHistoryPort() {
                @Override
                public List<MetricPoint> range(HistoryQuery query) {
                    PORT_THREADS.add(Thread.currentThread().getName());
                    return List.of();
                }
            };
        }

        @Bean
        @Primary
        TitleCatalogPort recordingCatalog() {
            return (tenant, title) -> {
                PORT_THREADS.add(Thread.currentThread().getName());
                return new TitleMetadata(title, "x");
            };
        }
    }

    @Autowired
    private WebTestClient client;

    @Test
    @DisplayName("no blocking port ever runs on an event-loop thread")
    void noBlockingPortRunsOnAnEventLoopThread() {
        REQUEST_THREADS.clear();
        PORT_THREADS.clear();

        client.get()
                .uri("/v1/titles/title-1/overview?tenantId=t1")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.degraded.length()")
                .isEqualTo(0);
        client.get()
                .uri("/v1/metrics/t1/ERROR_RATE?from=2026-01-15T11:00:00Z&to=2026-01-15T13:00:00Z")
                .exchange()
                .expectStatus()
                .isOk();

        assertThat(REQUEST_THREADS).isNotEmpty().allMatch(name -> name.contains("http-nio"));
        assertThat(PORT_THREADS).hasSize(4);
        assertThat(PORT_THREADS)
                .noneMatch(name -> name.contains("http-nio"))
                .allMatch(name -> name.startsWith("blocking-"));
    }
}

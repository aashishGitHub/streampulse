/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.query.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.streampulse.query.adapter.in.sse.LiveFeed;
import com.streampulse.query.application.TitleOverviewService;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthEndpointGroups;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.reactive.context.ReactiveWebApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The context assembles with no Redis and no Postgres: the feed is not started and the connection
 * pool opens lazily. Catches a wiring or property mistake in milliseconds. *Test, not *IT: no Docker.
 */
@AutoConfigureObservability
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "streampulse.query.stream.auto-start=false")
class QueryApiWiringTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private HealthEndpointGroups groups;

    @Autowired
    private WebTestClient client;

    @Test
    @DisplayName("the service runs on the reactive stack: spring-boot-starter-web has not crept in")
    void runsOnWebFlux() {
        assertThat(context).isInstanceOf(ReactiveWebApplicationContext.class);
    }

    @Test
    @DisplayName("the framework-free classes are wired by the composition root, with the configured bounds")
    void compositionRootWiresTheCore() {
        assertThat(context.getBean(TitleOverviewService.class)).isNotNull();
        assertThat(context.getBean(LiveFeed.class)).isNotNull();
        ThreadPoolExecutor executor = context.getBean("blockingExecutor", ThreadPoolExecutor.class);
        assertThat(executor.getCorePoolSize()).isEqualTo(8);
        assertThat(executor.getQueue().remainingCapacity()).isEqualTo(16);
    }

    @Test
    @DisplayName("liveness and readiness are the state probes only: no data store can restart the process")
    void probesDependOnNoDataStore() {
        assertThat(groups.get("liveness").isMember("livenessState")).isTrue();
        assertThat(groups.get("liveness").isMember("redis")).isFalse();
        assertThat(groups.get("liveness").isMember("db")).isFalse();
        assertThat(groups.get("readiness").isMember("readinessState")).isTrue();
        assertThat(groups.get("readiness").isMember("redis")).isFalse();
        assertThat(groups.get("readiness").isMember("db")).isFalse();
    }

    @Test
    @DisplayName("the probes answer UP and Prometheus is exposed")
    void probesAndPrometheusRespond() {
        client.get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();
        client.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();
        client.get().uri("/actuator/prometheus").exchange().expectStatus().isOk();
    }
}

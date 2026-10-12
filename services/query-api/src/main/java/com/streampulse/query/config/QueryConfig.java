/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.query.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.streampulse.query.adapter.in.sse.Coalescer;
import com.streampulse.query.adapter.in.sse.Heartbeat;
import com.streampulse.query.adapter.out.catalog.StubTitleCatalog;
import com.streampulse.query.adapter.out.postgres.JdbcMetricHistory;
import com.streampulse.query.adapter.out.redis.RedisLatestMetrics;
import com.streampulse.query.adapter.out.redis.RedisLiveFeed;
import com.streampulse.query.application.BoundedExecutor;
import com.streampulse.query.application.LatestMetricsPort;
import com.streampulse.query.application.MetricHistoryPort;
import com.streampulse.query.application.MetricQueryService;
import com.streampulse.query.application.TitleCatalogPort;
import com.streampulse.query.application.TitleOverviewService;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The composition root. The domain and application classes carry no Spring annotation; they are
 * constructed here, explicitly, so a reader sees every dependency in one place.
 */
@Configuration(proxyBeanMethods = false)
class QueryConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean(destroyMethod = "shutdownNow")
    ThreadPoolExecutor blockingExecutor(QueryProperties properties) {
        return BoundedExecutor.create(
                "blocking", properties.blocking().size(), properties.blocking().queueCapacity());
    }

    @Bean
    LatestMetricsPort latestMetricsPort(
            ReactiveStringRedisTemplate redis, ObjectMapper objectMapper, QueryProperties properties) {
        return new RedisLatestMetrics(redis, objectMapper, properties.latestKeyPrefix(), properties.callTimeout());
    }

    @Bean
    MetricHistoryPort metricHistoryPort(DataSource dataSource, QueryProperties properties) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        // Whole seconds: the JDBC API takes no finer unit. Bounds a stuck query, which orTimeout cannot cancel.
        jdbc.setQueryTimeout((int) Math.max(1, properties.callTimeout().toSeconds()));
        return new JdbcMetricHistory(jdbc, properties.overview().maxHistoryRows());
    }

    @Bean
    TitleCatalogPort titleCatalogPort(QueryProperties properties) {
        return new StubTitleCatalog(properties.overview().catalogLatency());
    }

    @Bean
    TitleOverviewService titleOverviewService(
            LatestMetricsPort latest,
            MetricHistoryPort history,
            TitleCatalogPort catalog,
            ThreadPoolExecutor blockingExecutor,
            Clock clock,
            QueryProperties properties) {
        return new TitleOverviewService(
                latest,
                history,
                catalog,
                blockingExecutor,
                clock,
                properties.overview().budget(),
                properties.overview().historyLookback());
    }

    @Bean
    MetricQueryService metricQueryService(MetricHistoryPort history, ThreadPoolExecutor blockingExecutor) {
        return new MetricQueryService(history, blockingExecutor);
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    RedisLiveFeed liveFeed(
            ReactiveStringRedisTemplate redis, QueryProperties properties, RedisProperties redisProperties) {
        // Half the command timeout: the blocking read must always return before Lettuce gives up on it.
        Duration commandTimeout =
                redisProperties.getTimeout() == null ? Duration.ofSeconds(2) : redisProperties.getTimeout();
        return new RedisLiveFeed(
                redis,
                properties.stream().key(),
                properties.stream().replayLimit(),
                properties.stream().autoStart(),
                commandTimeout.dividedBy(2));
    }

    @Bean
    Coalescer coalescer(QueryProperties properties) {
        return new Coalescer(properties.stream().coalesceWindow());
    }

    @Bean
    Heartbeat heartbeat(QueryProperties properties) {
        return new Heartbeat(properties.stream().heartbeat());
    }
}

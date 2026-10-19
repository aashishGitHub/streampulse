/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.config;

import com.streampulse.feed.adapter.in.kafka.WatchEventDecoder;
import com.streampulse.feed.adapter.in.kafka.WatchEventListener;
import com.streampulse.feed.adapter.out.kafka.KafkaDeadLetterPublisher;
import com.streampulse.feed.adapter.out.kafka.KafkaWatchPublisher;
import com.streampulse.feed.adapter.out.postgres.JdbcFollowGraph;
import com.streampulse.feed.adapter.out.redis.RedisTimelineStore;
import com.streampulse.feed.application.DeadLetterPort;
import com.streampulse.feed.application.FanOutService;
import com.streampulse.feed.application.FeedMetrics;
import com.streampulse.feed.application.FeedService;
import com.streampulse.feed.application.FollowService;
import com.streampulse.feed.application.WatchPublisher;
import com.streampulse.feed.application.WatchService;
import com.streampulse.feed.domain.CursorCodec;
import com.streampulse.feed.domain.FanOutPolicy;
import com.streampulse.feed.domain.FeedMerger;
import com.streampulse.feed.domain.FeedPager;
import com.streampulse.feed.domain.FollowGraph;
import com.streampulse.feed.domain.Ranker;
import com.streampulse.feed.domain.TimelineStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * The composition root. The domain, the services and the adapters carry no wiring annotations;
 * they are assembled here, so each can be built with plain {@code new} in a test.
 */
@Configuration
class FeedConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    FeedMetrics feedMetrics(MeterRegistry registry) {
        return new MicrometerFeedMetrics(registry);
    }

    @Bean
    FanOutPolicy fanOutPolicy(FeedProperties props) {
        return new FanOutPolicy(props.celebrityThreshold());
    }

    @Bean
    FollowGraph followGraph(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, Clock clock) {
        return new JdbcFollowGraph(jdbc, new TransactionTemplate(transactionManager), clock);
    }

    @Bean
    TimelineStore timelineStore(StringRedisTemplate redis, FeedProperties props) {
        return new RedisTimelineStore(
                redis,
                new RedisTimelineStore.Settings(
                        props.timeline().maxLength(),
                        props.timeline().ttl(),
                        props.outbox().maxLength(),
                        props.outbox().ttl()));
    }

    @Bean
    WatchPublisher watchPublisher(KafkaTemplate<String, byte[]> template, FeedProperties props) {
        return new KafkaWatchPublisher(template, props.watchTopic(), props.publishTimeout());
    }

    @Bean
    DeadLetterPort deadLetterPort(KafkaTemplate<String, byte[]> template, FeedProperties props) {
        return new KafkaDeadLetterPublisher(template, props.watchDltTopic(), props.dltSendTimeout());
    }

    @Bean
    WatchService watchService(WatchPublisher publisher, Clock clock) {
        return new WatchService(publisher, clock, UUID::randomUUID);
    }

    @Bean
    FollowService followService(FollowGraph graph, FanOutPolicy policy) {
        return new FollowService(graph, policy);
    }

    @Bean
    FanOutService fanOutService(
            FollowGraph graph, TimelineStore store, FanOutPolicy policy, FeedMetrics metrics, FeedProperties props) {
        return new FanOutService(graph, store, policy, metrics, props.followerPageSize());
    }

    @Bean
    FeedService feedService(
            FollowGraph graph,
            TimelineStore store,
            FanOutPolicy policy,
            FeedMetrics metrics,
            Clock clock,
            FeedProperties props) {
        var read = props.read();
        return new FeedService(
                graph,
                store,
                policy,
                new FeedMerger(),
                new Ranker(props.ranking().halfLife()),
                new FeedPager(),
                new CursorCodec(props.cursorSecret().getBytes(StandardCharsets.UTF_8)),
                metrics,
                clock,
                new FeedService.Limits(
                        read.timelineWindow(), read.celebrityWindow(), read.maxCelebrities(), read.maxFollowees()));
    }

    @Bean
    WatchEventListener watchEventListener(FanOutService fanOut, DeadLetterPort deadLetters, FeedMetrics metrics) {
        return new WatchEventListener(fanOut, new WatchEventDecoder(), deadLetters, metrics);
    }

    @Bean
    CommonErrorHandler watchErrorHandler(FeedProperties props) {
        return neverGiveUpErrorHandler(neverGiveUpBackOff(props.retry()));
    }

    /**
     * Batch listener with manual acknowledgement. Boot's configurer applies the {@code
     * spring.kafka.*} properties first; the two settings the design depends on are then set
     * explicitly, so a stray property cannot quietly turn auto-acknowledge back on.
     */
    @Bean
    ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            CommonErrorHandler errorHandler) {
        var factory = new ConcurrentKafkaListenerContainerFactory<Object, Object>();
        configurer.configure(factory, consumerFactory);
        factory.setBatchListener(true);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }

    /** Exponential, capped, and with no attempt or elapsed-time limit: an outage is waited out. */
    static BackOff neverGiveUpBackOff(FeedProperties.Retry retry) {
        var backOff = new ExponentialBackOff(retry.initialInterval().toMillis(), retry.multiplier());
        backOff.setMaxInterval(retry.maxInterval().toMillis());
        return backOff;
    }

    static DefaultErrorHandler neverGiveUpErrorHandler(BackOff backOff) {
        // The recoverer is what DefaultErrorHandler calls to SKIP a record. Skipping is exactly what
        // this consumer must never do, so it refuses; poison records are dead-lettered by the listener.
        var handler = new DefaultErrorHandler(
                (record, exception) -> {
                    throw new IllegalStateException(
                            "refusing to skip " + record.topic() + "-" + record.partition() + "@" + record.offset(),
                            exception);
                },
                backOff);
        // Spring treats some exception types as "not retryable" and skips straight to the recoverer.
        // Here every exception is retried.
        handler.setClassifications(Map.of(), true);
        return handler;
    }
}

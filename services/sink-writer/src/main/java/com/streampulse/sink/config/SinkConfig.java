/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.sink.config;

import com.streampulse.sink.adapter.in.kafka.AggregateBatchListener;
import com.streampulse.sink.adapter.in.kafka.AggregateDecoder;
import com.streampulse.sink.adapter.in.kafka.PreAckHook;
import com.streampulse.sink.adapter.out.kafka.KafkaDeadLetterSink;
import com.streampulse.sink.adapter.out.postgres.JdbcMetricWindowStore;
import com.streampulse.sink.adapter.out.redis.RedisHotProjection;
import com.streampulse.sink.application.AggregateSink;
import com.streampulse.sink.application.SinkMetrics;
import com.streampulse.sink.application.batch.BatchWriter;
import com.streampulse.sink.application.batch.BatchWriterMetrics;
import com.streampulse.sink.application.batch.DeadLetterSink;
import com.streampulse.sink.domain.AggregateRecord;
import com.streampulse.sink.domain.MetricProjection;
import com.streampulse.sink.domain.MetricWindowStore;
import com.streampulse.sink.lifecycle.BatchWriterLifecycle;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Map;
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
 * The composition root. The domain, the batch writer and the adapters carry no wiring
 * annotations; they are assembled here, so each can be built with plain {@code new} in a test.
 */
@Configuration
class SinkConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    MicrometerSinkMetrics sinkMetrics(MeterRegistry registry) {
        return new MicrometerSinkMetrics(registry);
    }

    @Bean
    MetricWindowStore metricWindowStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        return new JdbcMetricWindowStore(jdbc, new TransactionTemplate(transactionManager));
    }

    @Bean
    MetricProjection metricProjection(StringRedisTemplate redis) {
        return new RedisHotProjection(redis);
    }

    @Bean
    DeadLetterSink<AggregateRecord> deadLetterSink(KafkaTemplate<String, byte[]> template, SinkProperties props) {
        return new KafkaDeadLetterSink(template, props.aggregateDltTopic(), props.dltSendTimeout());
    }

    @Bean
    AggregateSink aggregateSink(
            MetricWindowStore store, MetricProjection projection, SinkMetrics metrics, Clock clock) {
        return new AggregateSink(store, projection, metrics, clock);
    }

    /** Started here and stopped by {@link BatchWriterLifecycle}, after the listener container. */
    @Bean(destroyMethod = "")
    BatchWriter<AggregateRecord> batchWriter(
            AggregateSink sink,
            DeadLetterSink<AggregateRecord> deadLetters,
            BatchWriterMetrics metrics,
            SinkProperties props) {
        var batch = props.batch();
        var config = new BatchWriter.Config(
                batch.maxSize(),
                batch.maxWait(),
                batch.queueCapacity(),
                batch.flushAttempts(),
                batch.retryBackoff(),
                batch.closeTimeout());
        return BatchWriter.start(config, sink, deadLetters, metrics);
    }

    @Bean
    BatchWriterLifecycle batchWriterLifecycle(BatchWriter<AggregateRecord> writer) {
        return new BatchWriterLifecycle(writer);
    }

    @Bean
    PreAckHook preAckHook() {
        return PreAckHook.NONE;
    }

    @Bean
    AggregateBatchListener aggregateBatchListener(
            BatchWriter<AggregateRecord> writer,
            DeadLetterSink<AggregateRecord> deadLetters,
            BatchWriterMetrics metrics,
            PreAckHook preAckHook,
            SinkProperties props) {
        return new AggregateBatchListener(
                writer,
                new AggregateDecoder(),
                deadLetters,
                metrics,
                preAckHook,
                props.offerTimeout(),
                props.pollBudget());
    }

    @Bean
    CommonErrorHandler aggregateErrorHandler(SinkProperties props) {
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
    static BackOff neverGiveUpBackOff(SinkProperties.Retry retry) {
        var backOff = new ExponentialBackOff(retry.initialInterval().toMillis(), retry.multiplier());
        backOff.setMaxInterval(retry.maxInterval().toMillis());
        return backOff;
    }

    static DefaultErrorHandler neverGiveUpErrorHandler(BackOff backOff) {
        // The recoverer is what DefaultErrorHandler calls to SKIP a record. Skipping is exactly what
        // this sink must never do, so it refuses; poison records are dead-lettered by the listener.
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

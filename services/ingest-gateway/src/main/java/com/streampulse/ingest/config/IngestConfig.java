/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.config;

import com.streampulse.ingest.adapter.out.kafka.AvroEventMapper;
import com.streampulse.ingest.adapter.out.kafka.KafkaEventPublisher;
import com.streampulse.ingest.adapter.out.kinesis.KinesisEventPublisher;
import com.streampulse.ingest.adapter.out.redis.RedisIdempotencyStore;
import com.streampulse.ingest.adapter.out.redis.RedisTokenBucketRateLimiter;
import com.streampulse.ingest.application.IngestMetrics;
import com.streampulse.ingest.application.IngestService;
import com.streampulse.ingest.application.IngestUseCase;
import com.streampulse.ingest.domain.EventPublisher;
import com.streampulse.ingest.domain.IdempotencyStore;
import com.streampulse.ingest.domain.RateLimiter;
import com.streampulse.ingest.lifecycle.IngestDrainLifecycle;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient;

/**
 * The composition root: where the object graph is assembled.
 *
 * <h2>Why the domain and application classes have no Spring annotations</h2>
 *
 * {@link IngestService}, {@link KafkaEventPublisher} and {@link RedisIdempotencyStore} carry
 * no {@code @Service} or {@code @Component}. They are wired explicitly here.
 *
 * <p>The benefit is that every one of them can be constructed with plain {@code new} in a
 * unit test — no context, no reflection, milliseconds. {@code IngestServiceTest} runs the
 * entire idempotency and backpressure suite, including a 200-iteration concurrency test,
 * without Spring ever starting.
 *
 * <p>The cost is this file. That is a good trade: framework coupling is concentrated in one
 * reviewable place instead of sprayed across the codebase, and the ArchUnit rule that keeps
 * the domain framework-free has something to protect.
 *
 * <p>This is also the seam where Phase 10 swaps in the Kinesis publisher behind a
 * {@code @Profile}, with no other file changing.
 */
@Configuration
class IngestConfig {

    @Bean
    AvroEventMapper avroEventMapper() {
        return new AvroEventMapper();
    }

    // -----------------------------------------------------------------------
    // THE STRATEGY SEAM.
    //
    // Two implementations of the same port, selected by profile. This is the payoff
    // of the hexagonal design: swapping the entire event backbone changes ONE bean
    // definition and nothing else — not the use case, not the controller, not a test
    // of the business rules.
    //
    //   default          Kinesis  (AWS-native; moto fakes it locally, free)
    //   profile "kafka"  Kafka    (open source; run `make up-core`)
    //
    // ADR-0008 compares them: shards vs partitions, sequence numbers vs offsets,
    // and the per-shard quota that has no Kafka equivalent.
    // -----------------------------------------------------------------------

    @Bean
    @Profile("!kafka")
    EventPublisher kinesisEventPublisher(KinesisAsyncClient kinesis, AwsProperties aws, AvroEventMapper mapper) {
        return new KinesisEventPublisher(kinesis, aws.kinesisStreamName(), mapper);
    }

    @Bean
    @Profile("kafka")
    EventPublisher kafkaEventPublisher(
            KafkaTemplate<String, byte[]> kafkaTemplate, IngestProperties props, AvroEventMapper mapper) {
        return new KafkaEventPublisher(kafkaTemplate, props.rawTopic(), mapper);
    }

    @Bean
    IdempotencyStore idempotencyStore(StringRedisTemplate redis, IngestProperties props) {
        return new RedisIdempotencyStore(redis, props.idempotencyTtl());
    }

    @Bean
    RateLimiter rateLimiter(StringRedisTemplate redis, IngestProperties props) {
        return new RedisTokenBucketRateLimiter(
                redis,
                props.quotaCapacity(),
                props.quotaRefillPerSecond(),
                props.idempotencyTtl().toSeconds(),
                props.failOpenOnIdempotencyError());
    }

    @Bean
    IngestMetrics ingestMetrics(MeterRegistry registry) {
        return new MicrometerIngestMetrics(registry);
    }

    @Bean
    IngestDrainLifecycle ingestDrainLifecycle(
            IngestUseCase useCase, ApplicationContext context, IngestProperties props) {
        // The cast is safe: ingestUseCase() below always returns an IngestService. It is
        // here rather than in the signature so the port stays the type everything else
        // depends on — only the drain needs the implementation's permit count.
        return new IngestDrainLifecycle(
                (IngestService) useCase, context, props.maxInFlightBatches(), props.drainTimeout());
    }

    @Bean
    IngestUseCase ingestUseCase(
            EventPublisher publisher,
            IdempotencyStore store,
            RateLimiter rateLimiter,
            IngestMetrics metrics,
            IngestProperties props) {
        return new IngestService(
                publisher,
                store,
                rateLimiter,
                metrics,
                props.maxInFlightBatches(),
                props.permitWait(),
                props.failOpenOnIdempotencyError());
    }
}

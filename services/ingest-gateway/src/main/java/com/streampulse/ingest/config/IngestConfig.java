/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.config;

import com.streampulse.ingest.adapter.out.kafka.AvroEventMapper;
import com.streampulse.ingest.adapter.out.kafka.KafkaEventPublisher;
import com.streampulse.ingest.adapter.out.kinesis.KinesisEventPublisher;
import com.streampulse.ingest.adapter.out.redis.RedisIdempotencyStore;
import com.streampulse.ingest.adapter.out.redis.RedisTokenBucketRateLimiter;
import com.streampulse.ingest.adapter.out.resilience.ResilientEventPublisher;
import com.streampulse.ingest.application.IngestMetrics;
import com.streampulse.ingest.application.IngestService;
import com.streampulse.ingest.application.IngestUseCase;
import com.streampulse.ingest.domain.EventPublisher;
import com.streampulse.ingest.domain.IdempotencyStore;
import com.streampulse.ingest.domain.RateLimiter;
import com.streampulse.ingest.lifecycle.IngestDrainLifecycle;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
 * <p>This is also the seam that swaps the event backbone: {@code streampulse.backbone}
 * selects the publisher, with no other file changing.
 */
@Configuration
class IngestConfig {

    private static final Logger log = LoggerFactory.getLogger(IngestConfig.class);

    /** One name, used for the breaker, the limiter and therefore the metric tags. */
    private static final String PUBLISHER = "eventPublisher";

    @Bean
    AvroEventMapper avroEventMapper() {
        return new AvroEventMapper();
    }

    // -----------------------------------------------------------------------
    // THE STRATEGY SEAM.
    //
    // Two implementations of the same port, selected by streampulse.backbone. This is
    // the payoff of the hexagonal design: swapping the entire event backbone changes
    // ONE bean definition and nothing else — not the use case, not the controller, not
    // a test of the business rules.
    //
    //   kafka    (default)  open source; run `make up-core`. Needed by stream-processor.
    //   kinesis             AWS-native; moto fakes it locally. Kept as the comparison.
    //
    // A property and not a profile because a profile cannot express "Kafka unless told
    // otherwise" without a negation on the other side, and ADR-0012 made Kafka the
    // default. ADR-0008 (superseded) still holds the shards-vs-partitions comparison.
    // -----------------------------------------------------------------------

    @Bean
    @ConditionalOnProperty(name = "streampulse.backbone", havingValue = "kinesis")
    EventPublisher kinesisEventPublisher(
            KinesisAsyncClient kinesis,
            AwsProperties aws,
            AvroEventMapper mapper,
            CircuitBreaker breaker,
            TimeLimiter timeLimiter,
            Retry retry,
            ScheduledExecutorService publishTimeoutScheduler) {
        return new ResilientEventPublisher(
                new KinesisEventPublisher(kinesis, aws.kinesisStreamName(), mapper),
                breaker,
                timeLimiter,
                retry,
                publishTimeoutScheduler);
    }

    @Bean
    @ConditionalOnProperty(name = "streampulse.backbone", havingValue = "kafka", matchIfMissing = true)
    EventPublisher kafkaEventPublisher(
            KafkaTemplate<String, byte[]> kafkaTemplate,
            IngestProperties props,
            AvroEventMapper mapper,
            CircuitBreaker breaker,
            TimeLimiter timeLimiter,
            Retry retry,
            ScheduledExecutorService publishTimeoutScheduler) {
        return new ResilientEventPublisher(
                new KafkaEventPublisher(kafkaTemplate, props.rawTopic(), mapper),
                breaker,
                timeLimiter,
                retry,
                publishTimeoutScheduler);
    }

    // -----------------------------------------------------------------------
    // RESILIENCE (REQ-IG-21, REQ-RS-02, REQ-RS-06).
    //
    // The decorator is constructed INSIDE each publisher bean rather than as a
    // separate @Primary bean taking an EventPublisher. That would be two beans of
    // the same type where the outer one needs the inner: Spring would offer the
    // decorator itself as a candidate for its own delegate. Wrapping at the point
    // of construction sidesteps the ambiguity entirely and reads in the right
    // order -- "a Kinesis publisher, wrapped in a breaker and a timeout".
    // -----------------------------------------------------------------------

    /**
     * Built from the auto-configured registry rather than {@code CircuitBreaker.of(...)}.
     *
     * <p>That one choice is what makes it appear in {@code /actuator/circuitbreakers}, in the
     * health indicator and as Micrometer gauges, because the starter's metrics binder walks
     * the registry. A standalone instance works identically and is invisible -- which is the
     * worst property a breaker can have, since "is it open right now?" is the first question
     * anyone asks during an incident.
     */
    @Bean
    CircuitBreaker eventPublisherCircuitBreaker(CircuitBreakerRegistry registry, PublisherResilienceProperties props) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                // COUNT_BASED: the last N calls. TIME_BASED would be the last N seconds,
                // which is better for steady traffic and worse here -- ingest is spiky by
                // nature, and a quiet minute would age real failures out of the window.
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(props.slidingWindowSize())
                .minimumNumberOfCalls(props.minimumNumberOfCalls())
                .failureRateThreshold(props.failureRateThreshold())
                .slowCallRateThreshold(props.slowCallRateThreshold())
                .slowCallDurationThreshold(props.slowCallDurationThreshold())
                .waitDurationInOpenState(props.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(props.permittedCallsInHalfOpenState())
                .build();

        CircuitBreaker breaker = registry.circuitBreaker(PUBLISHER, config);

        // One line per TRANSITION, not per rejected call. A breaker opens precisely when
        // calls are failing at volume, so logging each rejection would bury the transition
        // that explains it under thousands of lines of its own consequences.
        breaker.getEventPublisher()
                .onStateTransition(event -> log.warn(
                        "Circuit breaker '{}' {} -- publishing is {}",
                        event.getCircuitBreakerName(),
                        event.getStateTransition(),
                        switch (event.getStateTransition().getToState()) {
                            case OPEN, FORCED_OPEN -> "refused; clients get 503 + Retry-After";
                            case HALF_OPEN -> "being probed with a few trial calls";
                            case CLOSED -> "flowing normally again";
                            default -> "in state " + event.getStateTransition().getToState();
                        }));

        return breaker;
    }

    /**
     * Retry, whose policy (what is worth another attempt) belongs to the publisher and is
     * defined in {@link ResilientEventPublisher#retryPolicy} — see there for why each
     * exclusion exists. Only the numbers are configuration; the judgement is not.
     */
    @Bean
    Retry eventPublisherRetry(RetryRegistry registry, PublisherResilienceProperties props) {
        RetryConfig config = ResilientEventPublisher.retryPolicy(
                props.retryMaxAttempts(),
                IntervalFunction.ofExponentialRandomBackoff(
                        props.retryInitialBackoff(), props.retryBackoffMultiplier(), props.retryJitterFactor()));

        Retry retry = registry.retry(PUBLISHER, config);

        // DEBUG, not WARN: a retry that succeeds is the system absorbing a blip exactly as
        // designed, and the client never learned anything went wrong. What deserves
        // attention is the RATE, which Micrometer already carries via the registry.
        retry.getEventPublisher()
                .onRetry(event -> log.debug(
                        "Retrying publish, attempt {} after {}ms: {}",
                        event.getNumberOfRetryAttempts(),
                        event.getWaitInterval().toMillis(),
                        event.getLastThrowable() == null
                                ? "no throwable"
                                : event.getLastThrowable().toString()));

        return retry;
    }

    @Bean
    TimeLimiter eventPublisherTimeLimiter(TimeLimiterRegistry registry, PublisherResilienceProperties props) {
        TimeLimiterConfig config = TimeLimiterConfig.custom()
                .timeoutDuration(props.callTimeout())
                // Cancel the underlying future so the SDK can stop waiting on a socket nobody
                // will read. It is a REQUEST, not a guarantee -- the record may still land,
                // which is why a retry has to tolerate producing a duplicate.
                .cancelRunningFuture(true)
                .build();
        return registry.timeLimiter(PUBLISHER, config);
    }

    /**
     * Runs only the timeout callbacks, never the publish itself.
     *
     * <p>So one thread is enough regardless of load: each task does nothing but complete a
     * future exceptionally once its deadline passes.
     *
     * <p>{@code destroyMethod = "shutdown"} on purpose. Spring would otherwise call
     * {@code close()}, which since Java 19 waits for termination without a bound -- and a
     * shutdown path that can block indefinitely is how a pod gets SIGKILLed mid-drain,
     * undoing the ordering BS-01 exists to guarantee.
     */
    @Bean(destroyMethod = "shutdown")
    ScheduledExecutorService publishTimeoutScheduler() {
        return Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("publish-timeout").daemon().factory());
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

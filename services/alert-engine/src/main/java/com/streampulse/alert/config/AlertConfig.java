/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.streampulse.alert.adapter.in.kafka.AggregateDecoder;
import com.streampulse.alert.adapter.in.kafka.AggregateListener;
import com.streampulse.alert.adapter.in.kafka.ConfigChangeApplier;
import com.streampulse.alert.adapter.in.kafka.RuleBookLoader;
import com.streampulse.alert.adapter.in.kafka.RuleJsonParser;
import com.streampulse.alert.adapter.in.web.SseStreamer;
import com.streampulse.alert.adapter.out.kafka.AlertEventMapper;
import com.streampulse.alert.adapter.out.kafka.KafkaAlertChannel;
import com.streampulse.alert.application.AlertChannel;
import com.streampulse.alert.application.AlertFeed;
import com.streampulse.alert.application.AlertOutbox;
import com.streampulse.alert.application.AlertProcessor;
import com.streampulse.alert.domain.AlertEngine;
import com.streampulse.alert.domain.AlertMetrics;
import com.streampulse.alert.domain.RuleBook;
import com.streampulse.alert.domain.evaluator.Evaluators;
import com.streampulse.alert.lifecycle.AbsenceSweeper;
import com.streampulse.alert.lifecycle.FeedShutdown;
import com.streampulse.alert.lifecycle.RuleBookLifecycle;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The composition root. The domain, the application layer and the adapters carry no wiring
 * annotations apart from the Kafka listener and the controller; they are assembled here, so each can
 * be built with plain {@code new} in a test.
 */
@Configuration
class AlertConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    MicrometerAlertMetrics alertMetrics(MeterRegistry registry) {
        return new MicrometerAlertMetrics(registry);
    }

    @Bean
    RuleBook ruleBook() {
        return new RuleBook();
    }

    @Bean
    AlertEngine alertEngine(RuleBook rules, Clock clock, AlertMetrics metrics, AlertProperties props) {
        return new AlertEngine(rules, new Evaluators(props.absenceGrace()), clock, metrics);
    }

    @Bean
    AlertFeed alertFeed(AlertProperties props) {
        return new AlertFeed(props.sse().replayBuffer(), props.sse().subscriberBuffer());
    }

    @Bean
    KafkaAlertChannel kafkaAlertChannel(KafkaTemplate<String, byte[]> template, AlertProperties props) {
        return new KafkaAlertChannel(template, props.alertsTopic(), props.deliveryTimeout(), new AlertEventMapper());
    }

    @Bean
    AlertOutbox alertOutbox(List<AlertChannel> channels, AlertMetrics metrics) {
        return new AlertOutbox(channels, metrics);
    }

    @Bean
    AlertProcessor alertProcessor(AlertEngine engine, AlertOutbox outbox) {
        return new AlertProcessor(engine, outbox);
    }

    @Bean
    AggregateListener aggregateListener(AlertProcessor processor, AlertMetrics metrics) {
        return new AggregateListener(new AggregateDecoder(), processor, metrics);
    }

    /**
     * Never gives up and never skips: a failed delivery is retried until the channel recovers, and
     * the engine's idempotence makes each retry safe. Poison input is handled by the listener.
     */
    @Bean
    CommonErrorHandler aggregateErrorHandler() {
        var backOff = new ExponentialBackOff(1_000, 2.0);
        backOff.setMaxInterval(30_000);
        var handler = new DefaultErrorHandler(
                (record, exception) -> {
                    throw new IllegalStateException(
                            "refusing to skip " + record.topic() + "-" + record.partition() + "@" + record.offset(),
                            exception);
                },
                backOff);
        handler.setClassifications(Map.of(), true);
        return handler;
    }

    /**
     * Boot's configurer applies {@code spring.kafka.*} first; the acknowledgement mode the design
     * depends on is then set explicitly, so a stray property cannot turn auto-acknowledge back on.
     */
    @Bean
    ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            CommonErrorHandler errorHandler) {
        var factory = new ConcurrentKafkaListenerContainerFactory<Object, Object>();
        configurer.configure(factory, consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }

    @Bean
    RuleJsonParser ruleJsonParser(ObjectMapper mapper) {
        return new RuleJsonParser(mapper);
    }

    @Bean
    ConfigChangeApplier configChangeApplier(
            RuleBook rules, AlertProcessor processor, RuleJsonParser parser, AlertMetrics metrics) {
        return new ConfigChangeApplier(rules, processor, parser, metrics);
    }

    @Bean
    @ConditionalOnProperty(name = "streampulse.alerts.rulebook.enabled", havingValue = "true", matchIfMissing = true)
    RuleBookLoader ruleBookLoader(
            KafkaProperties kafka, SslBundles sslBundles, ConfigChangeApplier applier, AlertProperties props) {
        Map<String, Object> config = new HashMap<>(kafka.buildConsumerProperties(sslBundles));
        config.remove(ConsumerConfig.GROUP_ID_CONFIG);
        config.put(ConsumerConfig.CLIENT_ID_CONFIG, "alert-engine-rulebook");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        config.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        return new RuleBookLoader(
                config, props.configTopic(), applier, props.rulebook().pollTimeout());
    }

    @Bean
    @ConditionalOnProperty(name = "streampulse.alerts.rulebook.enabled", havingValue = "true", matchIfMissing = true)
    RuleBookLifecycle ruleBookLifecycle(RuleBookLoader loader, KafkaListenerEndpointRegistry registry) {
        return new RuleBookLifecycle(loader, registry);
    }

    @Bean
    AbsenceSweeper absenceSweeper(AlertProcessor processor, AlertProperties props) {
        return new AbsenceSweeper(processor, props.sweepInterval());
    }

    @Bean
    FeedShutdown feedShutdown(AlertFeed feed) {
        return new FeedShutdown(feed);
    }

    // shutdownNow, not the inferred close(): close() waits for every running task, and a stream task
    // only ends when its client does.
    @Bean(destroyMethod = "shutdownNow")
    ExecutorService sseExecutor() {
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("sse-", 0).factory());
    }

    @Bean
    SseStreamer sseStreamer(AlertFeed feed, ExecutorService sseExecutor, AlertProperties props) {
        return new SseStreamer(
                feed, sseExecutor, props.sse().heartbeat(), props.sse().maxLifetime());
    }

    // EventSource from a dashboard on another origin is a CORS request, and it silently fails without this.
    @Bean
    WebMvcConfigurer corsConfigurer(AlertProperties props) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/v1/**")
                        .allowedOrigins(props.corsAllowedOrigins().toArray(String[]::new))
                        .allowedMethods("GET")
                        .allowedHeaders("*");
            }
        };
    }
}

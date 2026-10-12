/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.processor.config;

import com.streampulse.contracts.v1.MetricAggregate;
import com.streampulse.processor.adapter.streams.TelemetryTopology;
import com.streampulse.processor.domain.EventValidator;
import com.streampulse.processor.domain.ProcessorMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.KStream;
import org.springframework.boot.autoconfigure.kafka.StreamsBuilderFactoryBeanCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.kafka.streams.KafkaStreamsMicrometerListener;

/**
 * The composition root. The domain and the topology carry no Spring annotations; they are
 * wired here, so each can be constructed with plain {@code new} in a test.
 */
@Configuration
class ProcessorConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    EventValidator eventValidator(Clock clock, ProcessorProperties props) {
        return new EventValidator(clock, props.maxFutureSkew());
    }

    @Bean
    ProcessorMetrics pipelineMetrics(MeterRegistry registry) {
        return new MicrometerProcessorMetrics(registry);
    }

    /**
     * Declares the topology on the builder Spring manages. The returned stream is not used by
     * anything; spring-kafka's convention is that a bean method taking the builder is where
     * the topology is declared.
     */
    @Bean
    KStream<String, MetricAggregate> aggregateStream(
            StreamsBuilder builder,
            ProcessorProperties props,
            EventValidator validator,
            Clock clock,
            ProcessorMetrics metrics) {
        var config = new TelemetryTopology.Config(
                props.rawTopic(), props.aggregateTopic(), props.rawDltTopic(), props.windowSize(), props.grace());
        return TelemetryTopology.define(builder, config, validator, clock, metrics);
    }

    /** Exposes Kafka Streams' own metrics (lag, dropped records, state) through Micrometer. */
    @Bean
    StreamsBuilderFactoryBeanCustomizer streamsMetrics(MeterRegistry registry) {
        return factoryBean -> factoryBean.addListener(new KafkaStreamsMicrometerListener(registry));
    }

    @Bean
    StreamsStateHealthIndicator streamsState(StreamsBuilderFactoryBean factoryBean) {
        return new StreamsStateHealthIndicator(() -> {
            KafkaStreams streams = factoryBean.getKafkaStreams();
            return streams == null ? KafkaStreams.State.NOT_RUNNING : streams.state();
        });
    }
}

/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.processor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.kafka.annotation.EnableKafkaStreams;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableKafkaStreams
public class StreamProcessorApplication {

    public static void main(String[] args) {
        SpringApplication.run(StreamProcessorApplication.class, args);
    }
}

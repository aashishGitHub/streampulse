/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.streampulse.alert.adapter.out.sqs.SqsAlertChannel;
import com.streampulse.alert.lifecycle.SqsProvisioner;
import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

/**
 * The SQS client, wired like ingest-gateway's Kinesis client: with an endpoint override the client
 * talks to moto with placeholder credentials, otherwise the SDK resolves real ones. Local simulation
 * proves API-level behaviour only; it does not evaluate IAM or throttle (ADR-0003).
 */
@Configuration
@ConditionalOnProperty(name = "streampulse.aws.sqs-enabled", havingValue = "true")
class AwsConfig {

    @Bean(destroyMethod = "close")
    SqsClient sqsClient(AwsProperties props) {
        SqsClientBuilder builder = SqsClient.builder().region(Region.of(props.region()));
        if (props.isLocalSimulation()) {
            builder.endpointOverride(URI.create(props.endpointOverride()))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.builder().build());
        }
        return builder.build();
    }

    @Bean
    SqsAlertChannel sqsAlertChannel(SqsClient sqs, AwsProperties props, ObjectMapper mapper) {
        return new SqsAlertChannel(sqs, props.alertsQueueName(), mapper);
    }

    @Bean
    SqsProvisioner sqsProvisioner(SqsAlertChannel channel) {
        return new SqsProvisioner(channel);
    }
}

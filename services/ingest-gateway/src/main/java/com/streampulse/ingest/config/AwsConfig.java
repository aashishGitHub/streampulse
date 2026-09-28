/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.config;

import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.Protocol;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient;
import software.amazon.awssdk.services.kinesis.KinesisAsyncClientBuilder;

/**
 * AWS SDK clients.
 *
 * <h2>How this runs with no AWS account</h2>
 *
 * When {@code streampulse.aws.endpoint-override} is set, every client is pointed at that URL
 * instead of the real AWS endpoints, and static dummy credentials are used. Locally that URL
 * is <b>moto</b> — a fake AWS API in a container. The SDK cannot tell the difference, so the
 * application code is byte-identical to production: same client, same calls, same error
 * types. Only the endpoint and the credentials differ, and both come from configuration.
 *
 * <p>This is the same technique the author's Go projects use, and it costs nothing: no
 * account, no credit card, no trial clock.
 *
 * <p>In a real deployment the override is absent, so {@link DefaultCredentialsProvider}
 * resolves credentials the normal way — environment, profile, container role, instance role.
 * <b>Never</b> hard-code keys: that is how they end up in git.
 *
 * <h2>What local simulation does NOT prove</h2>
 *
 * moto implements the API surface, not the service. It does not enforce IAM, it does not
 * throttle, and its latency is meaningless. A call that succeeds locally may still be denied
 * in production by a policy moto never evaluated. ADR-0003 spells this out; it is worth
 * repeating wherever someone might mistake a green local run for a deployment guarantee.
 */
@Configuration
class AwsConfig {

    @Bean
    KinesisAsyncClient kinesisAsyncClient(AwsProperties props) {
        KinesisAsyncClientBuilder builder = KinesisAsyncClient.builder().region(Region.of(props.region()));

        if (props.isLocalSimulation()) {
            builder.endpointOverride(URI.create(props.endpointOverride()))
                    // KinesisAsyncClient negotiates HTTP/2 by default, because real Kinesis
                    // supports it. moto is a Flask app that only speaks HTTP/1.1, so the
                    // SDK sends an HTTP/2 connection preface and Netty fails with:
                    //
                    //   "First received frame was not SETTINGS. Hex dump: 485454502f"
                    //
                    // 485454502f is "HTTP/" in ASCII — moto answering in 1.1 to a request
                    // that expected an HTTP/2 SETTINGS frame. Forcing HTTP/1.1 fixes it, and
                    // only applies to local simulation; production keeps HTTP/2.
                    .httpClientBuilder(NettyNioAsyncHttpClient.builder().protocol(Protocol.HTTP1_1))
                    // moto validates that SOME credentials are present, not what they are.
                    // "test"/"test" is the conventional placeholder.
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }

        return builder.build();
    }
}

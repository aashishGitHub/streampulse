/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.compactor.config;

import com.streampulse.compactor.adapter.in.CompactArchiveFunction;
import com.streampulse.compactor.adapter.in.CompactionRequest;
import com.streampulse.compactor.adapter.out.S3ArchiveStore;
import com.streampulse.compactor.domain.ArchiveStore;
import com.streampulse.compactor.domain.CompactionPlanner;
import com.streampulse.compactor.domain.CompactionPolicy;
import com.streampulse.compactor.domain.CompactionReport;
import com.streampulse.compactor.domain.Compactor;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.function.Function;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

@Configuration
@EnableConfigurationProperties(CompactorProperties.class)
class CompactorConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    S3Client s3Client(CompactorProperties props) {
        S3ClientBuilder builder = S3Client.builder().region(Region.of(props.region()));
        if (props.isLocalSimulation()) {
            // Path-style because an emulator has no wildcard DNS for <bucket>.localhost; real S3 keeps virtual-hosted
            // style.
            builder.endpointOverride(URI.create(props.endpointOverride()))
                    .forcePathStyle(true)
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.builder().build());
        }
        return builder.build();
    }

    @Bean
    ArchiveStore archiveStore(S3Client s3, CompactorProperties props) {
        return new S3ArchiveStore(s3, props.bucket());
    }

    @Bean
    Compactor compactor(ArchiveStore store, Clock clock, CompactorProperties props) {
        var planner = new CompactionPlanner(new CompactionPolicy(props.quietPeriod(), props.minInputObjects()));
        // The function's scratch directory: on a real Lambda this is /tmp, which is ephemeral and size-limited.
        return new Compactor(store, planner, clock, Path.of(System.getProperty("java.io.tmpdir")));
    }

    @Bean
    Function<CompactionRequest, CompactionReport> compactArchive(Compactor compactor) {
        return new CompactArchiveFunction(compactor);
    }
}

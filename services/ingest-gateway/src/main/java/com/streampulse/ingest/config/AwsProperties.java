/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * AWS settings.
 *
 * <p>Picked up by {@code @ConfigurationPropertiesScan} on the application class — there is
 * deliberately no {@code @Bean} method for it. Declaring both is how you get
 * <em>"expected single matching bean but found 2"</em>, which is a confusing startup failure
 * for something that looks like it should just work.
 *
 * @param endpointOverride when set, every AWS client talks to this URL instead of real AWS.
 *     Locally that is moto — a fake AWS API in a container, so no account and no credentials
 *     are needed. Absent in production, where the SDK resolves real endpoints.
 * @param region any valid region name. moto ignores it, but the SDK requires one.
 * @param kinesisStreamName the stream ingest publishes to
 */
@ConfigurationProperties(prefix = "streampulse.aws")
@Validated
public record AwsProperties(String endpointOverride, @NotBlank String region, @NotBlank String kinesisStreamName) {

    /** True when pointed at a local fake rather than real AWS. */
    public boolean isLocalSimulation() {
        return endpointOverride != null && !endpointOverride.isBlank();
    }
}

/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param endpointOverride when set, the client talks to this URL (moto locally) with dummy
 *     credentials; absent in a real deployment, where the SDK resolves endpoint and credentials
 * @param sqsEnabled false turns the queue channel off, for the no-Docker wiring test
 */
@Validated
@ConfigurationProperties("streampulse.aws")
public record AwsProperties(
        String endpointOverride, @NotBlank String region, @NotBlank String alertsQueueName, boolean sqsEnabled) {

    public boolean isLocalSimulation() {
        return endpointOverride != null && !endpointOverride.isBlank();
    }
}

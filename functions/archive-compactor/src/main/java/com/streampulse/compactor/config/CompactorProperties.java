/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.compactor.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param bucket the archive bucket
 * @param quietPeriod how long a partition must be idle before it is compacted (a target, not measured)
 * @param minInputObjects raw objects needed in a partition before compacting is worth it
 * @param region AWS region
 * @param endpointOverride set only to point the client at an emulator; absent against real AWS
 */
@ConfigurationProperties("compactor")
public record CompactorProperties(
        String bucket,
        @DefaultValue("PT15M") Duration quietPeriod,
        @DefaultValue("2") int minInputObjects,
        @DefaultValue("us-east-1") String region,
        String endpointOverride) {

    public boolean isLocalSimulation() {
        return endpointOverride != null && !endpointOverride.isBlank();
    }
}

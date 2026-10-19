/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.config;

import com.streampulse.controlplane.domain.ChangeIdGenerator;
import java.time.Clock;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ControlPlaneConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ChangeIdGenerator changeIdGenerator() {
        return UUID::randomUUID;
    }
}

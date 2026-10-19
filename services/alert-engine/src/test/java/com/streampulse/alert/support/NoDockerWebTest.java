/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.support;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * The whole Spring context with nothing behind it: no listener container, no rule book loader and no
 * queue channel, so no network is touched. Subclasses share one cached context.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.kafka.listener.auto-startup=false",
            "streampulse.alerts.rulebook.enabled=false",
            "streampulse.aws.sqs-enabled=false",
            "streampulse.alerts.sweep-interval=1h",
            "streampulse.alerts.sse.heartbeat=300ms"
        })
public abstract class NoDockerWebTest {}

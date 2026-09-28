/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The ingest gateway: StreamPulse's front door.
 *
 * <h2>Why this service exists</h2>
 *
 * Everything upstream of Kafka happens here, so this is where correctness is decided. If it
 * loses an event, no downstream cleverness recovers it. If it accepts a duplicate, every
 * aggregate is wrong. If it buffers without bound under load, it dies with the events still
 * in memory.
 *
 * <h2>Package layout — hexagonal</h2>
 *
 * <pre>
 *   domain/          business rules. NO Spring, NO Kafka, NO Redis imports.
 *                    Unit-testable with plain `new`. Enforced by ArchUnit.
 *   application/     use cases — orchestration over the domain and the ports
 *   adapter/in/      REST controller, gRPC service  (things that CALL us)
 *   adapter/out/     Kafka publisher, Redis idempotency  (things WE call)
 *   config/          Spring wiring — the only place framework annotations cluster
 * </pre>
 *
 * Dependencies point <b>inward</b>. The domain declares the interfaces it needs; the
 * adapters implement them. That inversion is what lets Phase 10 add a Kinesis publisher and
 * BS-09 add a gRPC front end without touching a line of business logic.
 *
 * @see <a href="file:../../../../../../../specs/02-ingest-gateway/design.md">the design</a>
 */
@SpringBootApplication
@ConfigurationPropertiesScan // finds @ConfigurationProperties records without listing each one
public class IngestGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(IngestGatewayApplication.class, args);
    }
}

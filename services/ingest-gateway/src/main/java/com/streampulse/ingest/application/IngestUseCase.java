/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.application;

import com.streampulse.ingest.domain.IngestCommand;
import com.streampulse.ingest.domain.IngestResult;

/**
 * Inbound port: ingest a validated batch.
 *
 * <h2>Why an interface for a single implementation</h2>
 *
 * Because there is more than one caller. The REST controller and the gRPC service (BS-09)
 * both depend on <b>this</b>, not on each other and not on the implementation. That is what
 * makes {@code ProtocolParityTest} possible — the same input through either protocol must
 * produce identical Kafka records, which is only true if neither adapter contains logic.
 */
public interface IngestUseCase {

    /**
     * @throws com.streampulse.ingest.domain.BackpressureException if the gateway is saturated
     */
    IngestResult ingest(IngestCommand command);
}

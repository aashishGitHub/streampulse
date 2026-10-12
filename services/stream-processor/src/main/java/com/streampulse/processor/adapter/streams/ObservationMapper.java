/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.processor.adapter.streams;

import com.streampulse.contracts.v1.EventType;
import com.streampulse.contracts.v1.TelemetryEvent;
import com.streampulse.processor.domain.Observation;

/** The one place the Avro wire type becomes a domain type. */
final class ObservationMapper {

    private ObservationMapper() {}

    static Observation toObservation(TelemetryEvent e) {
        // toString() on every string: Avro decodes them as Utf8, which never equals a String.
        return new Observation(
                e.getEventId().toString(),
                e.getTenantId().toString(),
                e.getSessionId().toString(),
                e.getTitleId() == null ? null : e.getTitleId().toString(),
                e.getRegion().toString(),
                e.getType() == EventType.ERROR,
                e.getOccurredAt());
    }
}

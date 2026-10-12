/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.processor.adapter.streams;

import com.streampulse.contracts.codec.AvroBinaryCodec;
import com.streampulse.contracts.codec.AvroDecodeException;
import com.streampulse.contracts.v1.TelemetryEvent;
import com.streampulse.processor.domain.EventValidator;
import com.streampulse.processor.domain.RejectionReason;
import com.streampulse.processor.domain.Validation;

/**
 * An input record after decoding and validation: either an event, or the original bytes plus
 * why they were refused. The bytes are kept so the dead-letter record is exactly what arrived;
 * re-encoding a decoded event could change it, and an undecodable one cannot be re-encoded.
 */
record Decoded(byte[] raw, TelemetryEvent event, RejectionReason rejection) {

    static Decoded of(byte[] raw, AvroBinaryCodec<TelemetryEvent> codec, EventValidator validator) {
        TelemetryEvent event;
        try {
            event = codec.decode(raw);
        } catch (AvroDecodeException e) {
            return new Decoded(raw, null, RejectionReason.UNDECODABLE);
        }
        Validation verdict = validator.validate(ObservationMapper.toObservation(event));
        if (verdict instanceof Validation.Invalid invalid) {
            return new Decoded(raw, null, invalid.reason());
        }
        return new Decoded(raw, event, null);
    }
}

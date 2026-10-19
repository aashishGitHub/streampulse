/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.adapter.in.kafka;

import com.streampulse.alert.domain.Observation;
import com.streampulse.contracts.codec.AvroBinaryCodec;
import com.streampulse.contracts.codec.AvroDecodeException;
import com.streampulse.contracts.v1.MetricAggregate;
import com.streampulse.contracts.v1.MetricName;

/**
 * Wire bytes to a domain {@link Observation}. Never throws: a record that cannot be used is a
 * {@link Decoded.Rejected} with a short reason, so the listener can count it and move on.
 */
public final class AggregateDecoder {

    public sealed interface Decoded {

        record Valid(Observation observation) implements Decoded {}

        record Rejected(String reason) implements Decoded {}
    }

    private final AvroBinaryCodec<MetricAggregate> codec = AvroBinaryCodec.of(MetricAggregate.class);

    public Decoded decode(byte[] value) {
        if (value == null) {
            return new Decoded.Rejected("null-value");
        }
        MetricAggregate aggregate;
        try {
            aggregate = codec.decode(value);
        } catch (AvroDecodeException e) {
            return new Decoded.Rejected("decode");
        }
        if (!aggregate.getIsFinal()) {
            return new Decoded.Rejected("not-final");
        }
        if (aggregate.getMetric() == MetricName.METRIC_UNKNOWN) {
            return new Decoded.Rejected("unknown-metric");
        }
        try {
            // Avro strings arrive as Utf8, which never equals a String: convert before the domain keys on them.
            return new Decoded.Valid(new Observation(
                    aggregate.getTenantId().toString(),
                    aggregate.getTitleId() == null
                            ? null
                            : aggregate.getTitleId().toString(),
                    aggregate.getRegion() == null ? null : aggregate.getRegion().toString(),
                    aggregate.getMetric().name(),
                    aggregate.getWindowStart(),
                    aggregate.getWindowEndExclusive(),
                    aggregate.getValue()));
        } catch (IllegalArgumentException | NullPointerException e) {
            return new Decoded.Rejected("invalid");
        }
    }
}

/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.sink.adapter.in.kafka;

import com.streampulse.contracts.codec.AvroBinaryCodec;
import com.streampulse.contracts.codec.AvroDecodeException;
import com.streampulse.contracts.v1.MetricAggregate;
import com.streampulse.contracts.v1.MetricName;
import com.streampulse.sink.domain.AggregateRecord;
import com.streampulse.sink.domain.MetricWindow;

/**
 * Bytes to a domain value, or a reason. Anything the durable store would reject on every
 * attempt is rejected here, so a poison record is dead-lettered before it can fail a batch.
 */
public final class AggregateDecoder {

    public sealed interface Decoded {
        record Valid(AggregateRecord record) implements Decoded {}

        record Rejected(String reason) implements Decoded {}
    }

    private final AvroBinaryCodec<MetricAggregate> codec = AvroBinaryCodec.of(MetricAggregate.class);

    public Decoded decode(String key, byte[] bytes) {
        MetricAggregate aggregate;
        try {
            aggregate = codec.decode(bytes);
        } catch (AvroDecodeException e) {
            return new Decoded.Rejected("cannot decode MetricAggregate: " + e.getMessage());
        }
        if (aggregate.getMetric() == MetricName.METRIC_UNKNOWN) {
            return new Decoded.Rejected("unknown metric");
        }
        try {
            // Avro strings arrive as Utf8, which never equals a String: convert before anything compares them.
            var window = new MetricWindow(
                    aggregate.getTenantId().toString(),
                    text(aggregate.getTitleId()),
                    text(aggregate.getRegion()),
                    aggregate.getMetric().name(),
                    aggregate.getWindowStart(),
                    aggregate.getWindowEndExclusive(),
                    aggregate.getValue(),
                    aggregate.getSampleCount(),
                    aggregate.getComputedAt());
            return new Decoded.Valid(new AggregateRecord(key, bytes, window));
        } catch (IllegalArgumentException | NullPointerException e) {
            return new Decoded.Rejected("invalid aggregate: " + e.getMessage());
        }
    }

    private static String text(CharSequence value) {
        return value == null ? null : value.toString();
    }
}

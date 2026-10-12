/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.processor.adapter.streams;

import com.streampulse.contracts.codec.AvroBinaryCodec;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;

/**
 * Adapts {@link AvroBinaryCodec} to Kafka's serde interface, for topics this topology writes
 * itself (the repartition topic and the aggregates). Input topics are decoded by hand in
 * {@link Decoded} instead, so one corrupt record becomes a dead letter rather than a thrown
 * deserialisation exception that stops the stream thread.
 */
final class AvroSerde<T extends SpecificRecord> implements Serde<T> {

    private final AvroBinaryCodec<T> codec;

    AvroSerde(AvroBinaryCodec<T> codec) {
        this.codec = codec;
    }

    @Override
    public Serializer<T> serializer() {
        return (topic, value) -> value == null ? null : codec.encode(value);
    }

    @Override
    public Deserializer<T> deserializer() {
        return (topic, bytes) -> bytes == null ? null : codec.decode(bytes);
    }
}

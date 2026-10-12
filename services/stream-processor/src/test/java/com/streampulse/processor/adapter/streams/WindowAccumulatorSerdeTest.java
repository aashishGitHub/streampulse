/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.processor.adapter.streams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streampulse.processor.domain.AggregateKey;
import com.streampulse.processor.domain.WindowAccumulator;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WindowAccumulatorSerdeTest {

    private final WindowAccumulatorSerde serde = new WindowAccumulatorSerde();

    private WindowAccumulator roundTrip(WindowAccumulator in) {
        return serde.deserializer().deserialize("t", serde.serializer().serialize("t", in));
    }

    @Test
    @DisplayName("a populated accumulator survives a round trip")
    void roundTrips() {
        var in = new WindowAccumulator(
                new AggregateKey("streamflix", "title-1", "us-east-1"), 10, 3, Set.of("s-1", "s-2", "s-3"));

        assertThat(roundTrip(in)).isEqualTo(in);
    }

    @Test
    @DisplayName("a missing title survives as null, not as an empty string")
    void nullTitle() {
        var in = new WindowAccumulator(new AggregateKey("streamflix", null, "us-east-1"), 1, 0, Set.of("s-1"));

        assertThat(roundTrip(in).key().titleId()).isNull();
    }

    @Test
    @DisplayName("the empty accumulator, which has no key yet, survives a round trip")
    void emptyAccumulator() {
        assertThat(roundTrip(WindowAccumulator.empty())).isEqualTo(WindowAccumulator.empty());
    }

    @Test
    @DisplayName("bytes written by an unknown format version are refused rather than misread")
    void unknownVersionRefused() {
        byte[] bytes = serde.serializer().serialize("t", WindowAccumulator.empty());
        bytes[0] = 99;

        assertThatThrownBy(() -> serde.deserializer().deserialize("t", bytes))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("99");
    }

    @Test
    @DisplayName("null in, null out: Kafka Streams uses null for 'no value'")
    void nullPassesThrough() {
        assertThat(serde.serializer().serialize("t", null)).isNull();
        assertThat(serde.deserializer().deserialize("t", null)).isNull();
    }
}

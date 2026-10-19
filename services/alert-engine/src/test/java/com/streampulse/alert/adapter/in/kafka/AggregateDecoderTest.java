/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.adapter.in.kafka;

import static com.streampulse.alert.support.Fixtures.T0;
import static com.streampulse.alert.support.Wire.aggregate;
import static com.streampulse.alert.support.Wire.bytes;
import static org.assertj.core.api.Assertions.assertThat;

import com.streampulse.alert.adapter.in.kafka.AggregateDecoder.Decoded;
import com.streampulse.contracts.v1.MetricName;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AggregateDecoderTest {

    private final AggregateDecoder decoder = new AggregateDecoder();

    @Test
    @DisplayName("a final aggregate becomes an observation, with Utf8 strings turned into Strings")
    void valid() {
        var aggregate = aggregate("streamflix", "title-1", "us-east-1", MetricName.ERROR_RATE, 3, 0.07)
                .build();

        var decoded = decoder.decode(bytes(aggregate));

        assertThat(decoded).isInstanceOf(Decoded.Valid.class);
        var observation = ((Decoded.Valid) decoded).observation();
        assertThat(observation.tenantId()).isEqualTo("streamflix").isInstanceOf(String.class);
        assertThat(observation.titleId()).isEqualTo("title-1");
        assertThat(observation.region()).isEqualTo("us-east-1");
        assertThat(observation.metric()).isEqualTo("ERROR_RATE");
        assertThat(observation.windowStart()).isEqualTo(T0.plusSeconds(30));
        assertThat(observation.value()).isEqualTo(0.07);
    }

    @Test
    @DisplayName("an absent title or region stays null: it means all")
    void nullTitleAndRegion() {
        var aggregate =
                aggregate("t", null, null, MetricName.CONCURRENT_VIEWERS, 0, 5).build();

        var observation = ((Decoded.Valid) decoder.decode(bytes(aggregate))).observation();

        assertThat(observation.titleId()).isNull();
        assertThat(observation.region()).isNull();
    }

    @Test
    @DisplayName("garbage, empty and null values are rejected, not thrown")
    void undecodable() {
        assertThat(decoder.decode("not avro".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(new Decoded.Rejected("decode"));
        assertThat(decoder.decode(new byte[0])).isEqualTo(new Decoded.Rejected("decode"));
        assertThat(decoder.decode(null)).isEqualTo(new Decoded.Rejected("null-value"));
    }

    @Test
    @DisplayName("a provisional window is not evaluated")
    void notFinal() {
        var aggregate = aggregate("t", "x", "r", MetricName.ERROR_RATE, 0, 1)
                .setIsFinal(false)
                .build();

        assertThat(decoder.decode(bytes(aggregate))).isEqualTo(new Decoded.Rejected("not-final"));
    }

    @Test
    @DisplayName("an unknown metric and a non-finite value are rejected")
    void invalid() {
        assertThat(decoder.decode(bytes(aggregate("t", "x", "r", MetricName.METRIC_UNKNOWN, 0, 1)
                        .build())))
                .isEqualTo(new Decoded.Rejected("unknown-metric"));
        assertThat(decoder.decode(bytes(aggregate("t", "x", "r", MetricName.ERROR_RATE, 0, Double.NaN)
                        .build())))
                .isEqualTo(new Decoded.Rejected("invalid"));
    }
}

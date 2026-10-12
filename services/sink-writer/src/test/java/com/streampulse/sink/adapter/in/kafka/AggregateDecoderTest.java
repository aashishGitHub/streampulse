/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.sink.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.streampulse.contracts.v1.MetricName;
import com.streampulse.sink.domain.AggregateRecord;
import com.streampulse.sink.support.Aggregates;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AggregateDecoderTest {

    private final AggregateDecoder decoder = new AggregateDecoder();

    private static byte[] bytesOf(com.streampulse.contracts.v1.MetricAggregate.Builder b) {
        return Aggregates.bytes(b.build());
    }

    @Test
    @DisplayName("a valid aggregate becomes a domain value whose strings are Strings, not Avro Utf8")
    void decodesValidAggregateToPlainStrings() {
        byte[] bytes = bytesOf(Aggregates.avro("streamflix", "title-42", "us-east-1", Aggregates.WINDOW_START));

        var result = decoder.decode("the-key", bytes);

        assertThat(result).isInstanceOf(AggregateDecoder.Decoded.Valid.class);
        AggregateRecord record = ((AggregateDecoder.Decoded.Valid) result).record();
        assertThat(record.key()).isEqualTo("the-key");
        assertThat(record.value()).isSameAs(bytes);
        assertThat(record.window().tenantId()).isEqualTo("streamflix").isInstanceOf(String.class);
        assertThat(record.window().titleId()).isEqualTo("title-42");
        assertThat(record.window().region()).isEqualTo("us-east-1");
        assertThat(record.window().metric()).isEqualTo("CONCURRENT_VIEWERS");
        assertThat(record.window().windowStart()).isEqualTo(Aggregates.WINDOW_START);
        assertThat(record.window().windowEnd()).isEqualTo(Aggregates.WINDOW_START.plusSeconds(10));
        assertThat(record.window().value()).isEqualTo(42.5);
        assertThat(record.window().sampleCount()).isEqualTo(17);
    }

    @Test
    @DisplayName("an absent title or region is stored as the empty string, per the contract's NOT NULL columns")
    void absentTitleAndRegionBecomeEmpty() {
        byte[] bytes = bytesOf(Aggregates.avro("t", null, null, Aggregates.WINDOW_START));

        var window = ((AggregateDecoder.Decoded.Valid) decoder.decode("k", bytes))
                .record()
                .window();

        assertThat(window.titleId()).isEmpty();
        assertThat(window.region()).isEmpty();
    }

    @Test
    @DisplayName("bytes that are not an aggregate are rejected with a reason, not thrown")
    void garbageIsRejected() {
        var result = decoder.decode("k", "not avro at all".getBytes(StandardCharsets.UTF_8));

        assertThat(result).isInstanceOfSatisfying(AggregateDecoder.Decoded.Rejected.class, r -> assertThat(r.reason())
                .contains("decode"));
    }

    @Test
    @DisplayName("a missing payload is rejected")
    void nullPayloadIsRejected() {
        assertThat(decoder.decode("k", null)).isInstanceOf(AggregateDecoder.Decoded.Rejected.class);
    }

    @Test
    @DisplayName("values the store would reject every time are rejected before they can poison a batch")
    void deterministicallyInvalidValuesAreRejected() {
        var blankTenant = bytesOf(Aggregates.avro(" ", "t", "r", Aggregates.WINDOW_START));
        var nulCharacter = bytesOf(Aggregates.avro("ten\u0000ant", "t", "r", Aggregates.WINDOW_START));
        var unknownMetric =
                bytesOf(Aggregates.avro("t", "t", "r", Aggregates.WINDOW_START).setMetric(MetricName.METRIC_UNKNOWN));
        var invertedWindow = bytesOf(Aggregates.avro("t", "t", "r", Aggregates.WINDOW_START)
                .setWindowEndExclusive(Aggregates.WINDOW_START.minusSeconds(1)));

        for (byte[] bytes : new byte[][] {blankTenant, nulCharacter, unknownMetric, invertedWindow}) {
            assertThat(decoder.decode("k", bytes)).isInstanceOf(AggregateDecoder.Decoded.Rejected.class);
        }
    }

    @Test
    @DisplayName("the instant fields survive the round trip at millisecond precision")
    void instantsSurvive() {
        Instant start = Instant.parse("2026-01-15T12:00:07.123Z");

        var window = ((AggregateDecoder.Decoded.Valid)
                        decoder.decode("k", bytesOf(Aggregates.avro("t", "t", "r", start))))
                .record()
                .window();

        assertThat(window.windowStart()).isEqualTo(start);
    }
}

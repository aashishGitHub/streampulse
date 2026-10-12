/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streampulse.contracts.codec.AvroBinaryCodec;
import com.streampulse.contracts.codec.AvroDecodeException;
import com.streampulse.contracts.v1.DeviceType;
import com.streampulse.contracts.v1.EventType;
import com.streampulse.contracts.v1.TelemetryEvent;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AvroBinaryCodecTest {

    private static final AvroBinaryCodec<TelemetryEvent> CODEC = AvroBinaryCodec.of(TelemetryEvent.class);

    private static TelemetryEvent event() {
        Instant at = Instant.parse("2026-01-15T12:00:00Z");
        return TelemetryEvent.newBuilder()
                .setEventId("e-1")
                .setTenantId("streamflix")
                .setSessionId("s-1")
                .setTitleId("t-1")
                .setDeviceType(DeviceType.SMART_TV)
                .setRegion("us-east-1")
                .setOccurredAt(at)
                .setReceivedAt(at.plusMillis(50))
                .setType(EventType.SESSION_START)
                .setPayload(null)
                .build();
    }

    @Test
    @DisplayName("encode then decode returns an equal record")
    void roundTrip() {
        assertThat(CODEC.decode(CODEC.encode(event()))).isEqualTo(event());
    }

    @Test
    @DisplayName("bytes that are not an event fail with AvroDecodeException, never a raw runtime error")
    void garbageIsATypedFailure() {
        byte[] garbage = {0x7f, 0x7f, 0x7f, 0x7f, 0x7f, 0x7f, 0x7f, 0x7f};

        assertThatThrownBy(() -> CODEC.decode(garbage)).isInstanceOf(AvroDecodeException.class);
    }

    @Test
    @DisplayName("trailing bytes after a valid record are rejected, so a corrupted tail cannot pass as an event")
    void trailingBytesAreRejected() {
        byte[] valid = CODEC.encode(event());
        byte[] withTail = java.util.Arrays.copyOf(valid, valid.length + 3);

        assertThatThrownBy(() -> CODEC.decode(withTail)).isInstanceOf(AvroDecodeException.class);
    }

    @Test
    @DisplayName("an empty payload is a decode failure")
    void emptyPayload() {
        assertThatThrownBy(() -> CODEC.decode(new byte[0])).isInstanceOf(AvroDecodeException.class);
    }
}

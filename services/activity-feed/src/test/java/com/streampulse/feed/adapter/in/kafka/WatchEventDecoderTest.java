/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.adapter.in.kafka;

import static com.streampulse.feed.support.Fixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import com.streampulse.contracts.codec.AvroBinaryCodec;
import com.streampulse.contracts.v1.WatchEvent;
import com.streampulse.feed.domain.WatchStarted;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WatchEventDecoderTest {

    private static final AvroBinaryCodec<WatchEvent> CODEC = AvroBinaryCodec.of(WatchEvent.class);
    private static final UUID EVENT = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private final WatchEventDecoder decoder = new WatchEventDecoder();

    private static byte[] bytes(String eventId, String userId, String titleId) {
        return CODEC.encode(WatchEvent.newBuilder()
                .setEventId(eventId)
                .setUserId(userId)
                .setTitleId(titleId)
                .setOccurredAt(NOW)
                .build());
    }

    @Test
    @DisplayName("a valid event decodes to the domain type; Avro's Utf8 strings are converted")
    void decodesAValidEvent() {
        var decoded = decoder.decode(bytes(EVENT.toString(), "alice", "dune"));

        assertThat(decoded).isInstanceOf(WatchEventDecoder.Decoded.Valid.class);
        assertThat(((WatchEventDecoder.Decoded.Valid) decoded).watch())
                .isEqualTo(new WatchStarted(
                        EVENT,
                        new com.streampulse.feed.domain.UserId("alice"),
                        new com.streampulse.feed.domain.TitleId("dune"),
                        NOW));
    }

    @Test
    @DisplayName("bytes that are not an Avro WatchEvent are rejected with a reason")
    void rejectsGarbageBytes() {
        assertRejected(decoder.decode("definitely not avro".getBytes(StandardCharsets.UTF_8)), "cannot decode");
        assertRejected(decoder.decode(new byte[0]), "cannot decode");
        assertRejected(decoder.decode(null), "cannot decode");
    }

    @Test
    @DisplayName("an event id that is not a canonical UUID is rejected")
    void rejectsANonUuidEventId() {
        assertRejected(decoder.decode(bytes("not-a-uuid", "alice", "dune")), "event id");
        assertRejected(decoder.decode(bytes("1-1-1-1-1", "alice", "dune")), "event id");
    }

    @Test
    @DisplayName("a user or title id outside the allowed alphabet is rejected, ':' above all")
    void rejectsInvalidIds() {
        assertRejected(decoder.decode(bytes(EVENT.toString(), "al:ice", "dune")), "user id");
        assertRejected(decoder.decode(bytes(EVENT.toString(), "alice", "")), "title id");
    }

    private static void assertRejected(WatchEventDecoder.Decoded decoded, String reasonContains) {
        assertThat(decoded).isInstanceOf(WatchEventDecoder.Decoded.Rejected.class);
        assertThat(((WatchEventDecoder.Decoded.Rejected) decoded).reason()).contains(reasonContains);
    }
}

/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.adapter.in.kafka;

import com.streampulse.contracts.codec.AvroBinaryCodec;
import com.streampulse.contracts.codec.AvroDecodeException;
import com.streampulse.contracts.v1.WatchEvent;
import com.streampulse.feed.domain.TitleId;
import com.streampulse.feed.domain.UserId;
import com.streampulse.feed.domain.WatchStarted;
import java.util.UUID;

/** Bytes to a domain value, or a reason. Whatever the fan-out would reject on every attempt is rejected here. */
public final class WatchEventDecoder {

    public sealed interface Decoded {
        record Valid(WatchStarted watch) implements Decoded {}

        record Rejected(String reason) implements Decoded {}
    }

    private final AvroBinaryCodec<WatchEvent> codec = AvroBinaryCodec.of(WatchEvent.class);

    public Decoded decode(byte[] bytes) {
        WatchEvent event;
        try {
            event = codec.decode(bytes);
        } catch (AvroDecodeException e) {
            return new Decoded.Rejected("cannot decode WatchEvent: " + e.getMessage());
        }
        // Avro strings arrive as Utf8, which never equals a String: convert before anything compares them.
        UUID eventId = canonicalUuid(event.getEventId().toString());
        if (eventId == null) {
            return new Decoded.Rejected("event id is not a canonical UUID");
        }
        try {
            return new Decoded.Valid(new WatchStarted(
                    eventId,
                    new UserId(event.getUserId().toString()),
                    new TitleId(event.getTitleId().toString()),
                    event.getOccurredAt()));
        } catch (IllegalArgumentException e) {
            return new Decoded.Rejected("invalid watch event: " + e.getMessage());
        }
    }

    private static UUID canonicalUuid(String text) {
        try {
            UUID uuid = UUID.fromString(text);
            return uuid.toString().equals(text) ? uuid : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

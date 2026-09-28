/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.streampulse.contracts.v1.DeviceType;
import com.streampulse.contracts.v1.ErrorEvent;
import com.streampulse.contracts.v1.EventType;
import com.streampulse.contracts.v1.Heartbeat;
import com.streampulse.contracts.v1.TelemetryEvent;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the generated classes actually serialize and deserialize, and measures how big an
 * event is on the wire.
 *
 * <h2>Why the size test matters</h2>
 *
 * A heartbeat is emitted by every player every ~10 seconds. At the scale this system is
 * designed for, its size is the dominant cost driver: it multiplies through network egress,
 * broker disk, replication traffic and S3 storage. Measuring it here — rather than
 * discovering it on the bill — is why the budget is asserted in a test.
 *
 * <p>This is also the number that makes the Phase 9 compression work meaningful: you cannot
 * claim a 30% reduction without a measured baseline.
 */
class SerializationRoundTripTest {

    /**
     * Binary round trip using the SAME schema for writer and reader.
     *
     * <p>Note what Avro does <em>not</em> write: field names. The schema is known to both
     * sides (in production via Schema Registry, by id), so the payload is just values. That
     * is the main reason it is so much smaller than JSON.
     */
    private static <T extends org.apache.avro.specific.SpecificRecord> byte[] serialize(T record, Class<T> type)
            throws IOException {
        var writer = new SpecificDatumWriter<T>(type);
        var out = new ByteArrayOutputStream();
        BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
        writer.write(record, encoder);
        encoder.flush();
        return out.toByteArray();
    }

    private static <T extends org.apache.avro.specific.SpecificRecord> T deserialize(byte[] bytes, Class<T> type)
            throws IOException {
        var reader = new SpecificDatumReader<T>(type);
        BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(bytes, null);
        return reader.read(null, decoder);
    }

    private static TelemetryEvent.Builder baseEvent() {
        // Avro's `timestamp-millis` LOGICAL TYPE generates java.time.Instant accessors,
        // not a bare long. That is worth noticing: the logical type is what gives you a
        // real temporal type on both sides of the wire while still encoding as a single
        // varint. It also keeps us inside the "java.time only" architecture rule.
        Instant now = Instant.parse("2026-01-15T12:00:00Z");
        return TelemetryEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setTenantId("streamflix")
                .setSessionId("sess-" + UUID.randomUUID())
                .setTitleId("title-42")
                .setDeviceType(DeviceType.SMART_TV)
                .setRegion("us-east-1")
                .setOccurredAt(now)
                .setReceivedAt(now.plusMillis(120)); // 120ms transit
    }

    @Test
    @DisplayName("a heartbeat survives a binary round trip unchanged")
    void heartbeatRoundTrips() throws IOException {
        TelemetryEvent original = baseEvent()
                .setType(EventType.HEARTBEAT)
                .setPayload(Heartbeat.newBuilder()
                        .setPositionSeconds(345)
                        .setBufferedSeconds(12)
                        .setBitrateKbps(4500)
                        .setDroppedFrames(0)
                        .build())
                .build();

        TelemetryEvent restored = deserialize(serialize(original, TelemetryEvent.class), TelemetryEvent.class);

        // Avro generates equals() from the schema, so this compares every field including
        // the payload union.
        assertThat(restored).isEqualTo(original);
        assertThat(restored.getPayload()).isInstanceOf(Heartbeat.class);
    }

    @Test
    @DisplayName("an error event round trips, including its optional fields")
    void errorEventRoundTrips() throws IOException {
        TelemetryEvent original = baseEvent()
                .setType(EventType.ERROR)
                .setPayload(ErrorEvent.newBuilder()
                        .setCode("MANIFEST_404")
                        .setMessage("manifest not found at edge")
                        .setFatal(true)
                        .setCdnNode("edge-lhr-07")
                        .build())
                .build();

        TelemetryEvent restored = deserialize(serialize(original, TelemetryEvent.class), TelemetryEvent.class);

        assertThat(restored).isEqualTo(original);
        assertThat(((ErrorEvent) restored.getPayload()).getCdnNode()).hasToString("edge-lhr-07");
    }

    @Test
    @DisplayName("null payload and absent optionals round trip correctly")
    void nullsAreHandled() throws IOException {
        // The union-with-null pattern is only useful if null actually survives the wire.
        TelemetryEvent original = baseEvent()
                .setTitleId(null)
                .setType(EventType.SESSION_START)
                .setPayload(null)
                .build();

        TelemetryEvent restored = deserialize(serialize(original, TelemetryEvent.class), TelemetryEvent.class);

        assertThat(restored.getTitleId()).isNull();
        assertThat(restored.getPayload()).isNull();
        assertThat(restored).isEqualTo(original);
    }

    @Test
    @DisplayName("a typical heartbeat stays within the size budget")
    void heartbeatIsCompact() throws IOException {
        TelemetryEvent event = baseEvent()
                .setType(EventType.HEARTBEAT)
                .setPayload(Heartbeat.newBuilder()
                        .setPositionSeconds(345)
                        .setBufferedSeconds(12)
                        .setBitrateKbps(4500)
                        .setDroppedFrames(0)
                        .build())
                .build();

        byte[] bytes = serialize(event, TelemetryEvent.class);

        // NFR-C1-04. The budget is a design target chosen for this project, not a law —
        // its job is to fail loudly if someone adds a large field to the hottest event in
        // the system without thinking about the cost.
        System.out.printf("Heartbeat serialized size: %d bytes%n", bytes.length);
        assertThat(bytes.length)
                .as("a heartbeat is the highest-volume event; watch its size")
                .isLessThan(400);
    }

    @Test
    @DisplayName("Avro binary is substantially smaller than the equivalent JSON")
    void avroIsSmallerThanJson() throws IOException {
        TelemetryEvent event = baseEvent()
                .setType(EventType.HEARTBEAT)
                .setPayload(Heartbeat.newBuilder()
                        .setPositionSeconds(345)
                        .setBufferedSeconds(12)
                        .setBitrateKbps(4500)
                        .setDroppedFrames(0)
                        .build())
                .build();

        int avroSize = serialize(event, TelemetryEvent.class).length;
        // toString() on a generated class emits the Avro JSON form — close enough to a
        // JSON encoding for an order-of-magnitude comparison.
        int jsonSize = event.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;

        System.out.printf(
                "Avro %d bytes vs JSON ~%d bytes (%.0f%% smaller)%n",
                avroSize, jsonSize, 100.0 * (jsonSize - avroSize) / jsonSize);

        // The saving comes almost entirely from NOT writing field names on every record.
        assertThat(avroSize).isLessThan(jsonSize);
    }
}

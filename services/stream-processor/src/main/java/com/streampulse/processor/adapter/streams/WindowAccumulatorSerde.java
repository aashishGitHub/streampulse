/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.processor.adapter.streams;

import com.streampulse.processor.domain.AggregateKey;
import com.streampulse.processor.domain.WindowAccumulator;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.Set;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;

/**
 * Binary format for the window state store and its changelog topic.
 *
 * <p>The first byte is a format version. State outlives deployments: the changelog is replayed
 * after a restart, so a new release must either read the old bytes or fail loudly. A version
 * byte makes "fail loudly" possible; without it a changed layout is silently misread.
 */
final class WindowAccumulatorSerde implements Serde<WindowAccumulator> {

    private static final byte VERSION = 1;

    @Override
    public Serializer<WindowAccumulator> serializer() {
        return (topic, acc) -> acc == null ? null : write(acc);
    }

    @Override
    public Deserializer<WindowAccumulator> deserializer() {
        return (topic, bytes) -> bytes == null ? null : read(bytes);
    }

    private static byte[] write(WindowAccumulator acc) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeByte(VERSION);
            out.writeBoolean(acc.key() != null);
            if (acc.key() != null) {
                out.writeUTF(acc.key().tenantId());
                out.writeBoolean(acc.key().titleId() != null);
                if (acc.key().titleId() != null) {
                    out.writeUTF(acc.key().titleId());
                }
                out.writeUTF(acc.key().region());
            }
            out.writeLong(acc.events());
            out.writeLong(acc.errors());
            out.writeInt(acc.sessions().size());
            for (String session : acc.sessions()) {
                out.writeUTF(session);
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static WindowAccumulator read(byte[] bytes) {
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            byte version = in.readByte();
            if (version != VERSION) {
                throw new IllegalStateException("Unsupported window state format version " + version);
            }
            AggregateKey key = null;
            if (in.readBoolean()) {
                String tenant = in.readUTF();
                String title = in.readBoolean() ? in.readUTF() : null;
                key = new AggregateKey(tenant, title, in.readUTF());
            }
            long events = in.readLong();
            long errors = in.readLong();
            int count = in.readInt();
            Set<String> sessions = new HashSet<>(Math.max(16, count * 2));
            for (int i = 0; i < count; i++) {
                sessions.add(in.readUTF());
            }
            return new WindowAccumulator(key, events, errors, sessions);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

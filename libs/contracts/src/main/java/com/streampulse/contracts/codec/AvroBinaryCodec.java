/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.contracts.codec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecord;

/**
 * Raw Avro binary, no schema-registry framing: the topic carries exactly the bytes of one
 * record. Writer and reader schema are the same class, so this does not resolve schema
 * evolution across versions; that is what Schema Registry would add.
 *
 * <p>Decoded string fields are {@code org.apache.avro.util.Utf8}, not {@code String}, because
 * the generated classes use Avro's default string type. A {@code Utf8} never equals a
 * {@code String} and has a different hash code, so call {@code toString()} before using one
 * as a map key or comparing it.
 *
 * <p>Framework-free on purpose so the publisher, the stream processor and the sink share one
 * definition of the wire format.
 */
public final class AvroBinaryCodec<T extends SpecificRecord> {

    private final SpecificDatumWriter<T> writer;
    private final SpecificDatumReader<T> reader;
    private final String typeName;

    private AvroBinaryCodec(Class<T> type) {
        this.writer = new SpecificDatumWriter<>(type);
        this.reader = new SpecificDatumReader<>(type);
        this.typeName = type.getSimpleName();
    }

    public static <T extends SpecificRecord> AvroBinaryCodec<T> of(Class<T> type) {
        return new AvroBinaryCodec<>(type);
    }

    public byte[] encode(T record) {
        try {
            var out = new ByteArrayOutputStream();
            BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
            writer.write(record, encoder);
            encoder.flush();
            return out.toByteArray();
        } catch (IOException e) {
            // Writing to memory cannot fail on I/O; a failure here is a schema/programming
            // error and retrying cannot help.
            throw new UncheckedIOException("Failed to encode " + typeName, e);
        }
    }

    public T decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new AvroDecodeException("Empty payload is not a " + typeName);
        }
        try {
            BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(bytes, null);
            T record = reader.read(null, decoder);
            // Avro stops reading when the schema is satisfied and ignores whatever follows.
            // Without this check a truncated-then-padded or corrupted tail decodes "fine".
            if (!decoder.isEnd()) {
                throw new AvroDecodeException("Trailing bytes after a " + typeName);
            }
            return record;
        } catch (AvroDecodeException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            // Avro surfaces malformed input as EOFException, AvroRuntimeException,
            // ArrayIndexOutOfBounds or NegativeArraySize depending on where it breaks.
            throw new AvroDecodeException("Not a valid " + typeName, e);
        }
    }
}

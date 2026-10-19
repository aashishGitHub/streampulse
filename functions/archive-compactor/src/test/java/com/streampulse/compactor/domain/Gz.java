/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.compactor.domain;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Test helper: build and read small gzip NDJSON objects. */
final class Gz {

    private Gz() {}

    static byte[] gzip(String text) {
        try {
            var out = new ByteArrayOutputStream();
            try (var gz = new GZIPOutputStream(out)) {
                gz.write(text.getBytes(StandardCharsets.UTF_8));
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Each line becomes one record, with a trailing newline. */
    static byte[] ndjson(String... lines) {
        return gzip(lines.length == 0 ? "" : String.join("\n", lines) + "\n");
    }

    static String gunzip(byte[] bytes) {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

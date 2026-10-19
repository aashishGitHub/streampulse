/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.compactor.domain;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;

/**
 * Copies gzip NDJSON records without ever holding more than one fixed buffer.
 *
 * <p>Works on bytes, never on decoded text: decoding would silently replace invalid UTF-8 and
 * change a record the archive is supposed to preserve. A record is a non-empty line; every record
 * is written with exactly one trailing newline, so concatenated objects can never glue two
 * records together.
 */
public final class NdjsonMerger {

    private static final int BUFFER = 64 * 1024;

    /** Writes the records of {@code gzipIn}, uncompressed, to {@code out}; returns how many. */
    public long append(InputStream gzipIn, OutputStream out) throws IOException {
        return scan(gzipIn, out);
    }

    public long count(InputStream gzipIn) throws IOException {
        return scan(gzipIn, null);
    }

    private long scan(InputStream gzipIn, OutputStream out) throws IOException {
        long records = 0;
        boolean lineHasContent = false;
        byte[] buffer = new byte[BUFFER];

        try (var in = new GZIPInputStream(gzipIn, BUFFER)) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                int runStart = 0;
                for (int i = 0; i < n; i++) {
                    if (buffer[i] != '\n') {
                        lineHasContent = true;
                    } else if (lineHasContent) {
                        records++;
                        lineHasContent = false;
                        write(out, buffer, runStart, i + 1 - runStart);
                        runStart = i + 1;
                    } else {
                        runStart = i + 1; // an empty line: drop it, newline included
                    }
                }
                if (runStart < n) {
                    write(out, buffer, runStart, n - runStart);
                }
            }
        }

        if (lineHasContent) {
            records++;
            if (out != null) {
                out.write('\n');
            }
        }
        return records;
    }

    private static void write(OutputStream out, byte[] buffer, int offset, int length) throws IOException {
        if (out != null) {
            out.write(buffer, offset, length);
        }
    }
}

/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.compactor.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NdjsonMergerTest {

    private final NdjsonMerger merger = new NdjsonMerger();

    private String append(long[] countOut, byte[]... inputs) throws IOException {
        var out = new ByteArrayOutputStream();
        long total = 0;
        for (byte[] input : inputs) {
            total += merger.append(new ByteArrayInputStream(input), out);
        }
        countOut[0] = total;
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("records are copied in order and counted")
    void copiesAndCounts() throws IOException {
        long[] count = new long[1];

        String merged = append(count, Gz.ndjson("{\"n\":1}", "{\"n\":2}"), Gz.ndjson("{\"n\":3}"));

        assertThat(merged).isEqualTo("{\"n\":1}\n{\"n\":2}\n{\"n\":3}\n");
        assertThat(count[0]).isEqualTo(3);
    }

    @Test
    @DisplayName("an object whose last record has no newline does not glue onto the next object's first record")
    void missingTrailingNewlineDoesNotGlueRecords() throws IOException {
        long[] count = new long[1];

        String merged = append(count, Gz.gzip("{\"n\":1}\n{\"n\":2}"), Gz.gzip("{\"n\":3}\n"));

        assertThat(merged).isEqualTo("{\"n\":1}\n{\"n\":2}\n{\"n\":3}\n");
        assertThat(count[0]).isEqualTo(3);
    }

    @Test
    @DisplayName("empty lines are not records")
    void blankLinesAreNotRecords() throws IOException {
        long[] count = new long[1];

        String merged = append(count, Gz.gzip("\n{\"n\":1}\n\n\n{\"n\":2}\n\n"));

        assertThat(merged).isEqualTo("{\"n\":1}\n{\"n\":2}\n");
        assertThat(count[0]).isEqualTo(2);
    }

    @Test
    @DisplayName("an empty object contributes nothing")
    void emptyObject() throws IOException {
        long[] count = new long[1];

        assertThat(append(count, Gz.gzip(""))).isEmpty();
        assertThat(count[0]).isZero();
    }

    @Test
    @DisplayName("an object made of several gzip members is read to the end")
    void multiMemberGzipIsReadToTheEnd() throws IOException {
        var joined = new ByteArrayOutputStream();
        joined.write(Gz.gzip("{\"n\":1}\n"));
        joined.write(Gz.gzip("{\"n\":2}\n"));
        long[] count = new long[1];

        String merged = append(count, joined.toByteArray());

        assertThat(merged).isEqualTo("{\"n\":1}\n{\"n\":2}\n");
        assertThat(count[0]).isEqualTo(2);
    }

    @Test
    @DisplayName("non-ASCII bytes pass through untouched, because lines are never decoded")
    void bytesAreNotDecoded() throws IOException {
        byte[] invalidUtf8 = {'{', '"', 'a', '"', ':', '"', (byte) 0xC3, (byte) 0x28, '"', '}', '\n'};
        var gz = new ByteArrayOutputStream();
        try (var g = new GZIPOutputStream(gz)) {
            g.write(invalidUtf8);
        }
        var out = new ByteArrayOutputStream();

        merger.append(new ByteArrayInputStream(gz.toByteArray()), out);

        assertThat(out.toByteArray()).isEqualTo(invalidUtf8);
    }

    @Test
    @DisplayName("counting a stream agrees with copying it")
    void countAgreesWithAppend() throws IOException {
        byte[] input = Gz.gzip("\n{\"n\":1}\n{\"n\":2}\n\n{\"n\":3}");

        assertThat(merger.count(new ByteArrayInputStream(input)))
                .isEqualTo(merger.append(new ByteArrayInputStream(input), OutputStream.nullOutputStream()))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("a million records are streamed through a file without being collected")
    void largeInputIsStreamed(@TempDir Path dir) throws IOException {
        // Correctness at volume only. That memory stays flat is true by construction (a fixed
        // buffer in, a gzip stream out) and is NOT measured here.
        int records = 1_000_000;
        Path source = dir.resolve("big.ndjson.gz");
        try (var out = new GZIPOutputStream(Files.newOutputStream(source), 64 * 1024)) {
            for (int i = 0; i < records; i++) {
                out.write(("{\"i\":" + i + ",\"v\":\"x\"}\n").getBytes(StandardCharsets.UTF_8));
            }
        }
        Path target = dir.resolve("merged.ndjson.gz");

        long copied;
        try (InputStream in = Files.newInputStream(source);
                var out = new GZIPOutputStream(Files.newOutputStream(target), 64 * 1024)) {
            copied = merger.append(in, out);
        }

        assertThat(copied).isEqualTo(records);
        try (InputStream in = Files.newInputStream(target)) {
            assertThat(merger.count(in)).isEqualTo(records);
        }
    }
}
